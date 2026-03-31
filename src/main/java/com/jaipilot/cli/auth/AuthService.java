package com.jaipilot.cli.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class AuthService {

    static final long REFRESH_SKEW_SECONDS = 60L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String LOGIN_PATH = "/plugin-login";
    private static final String REFRESH_PATH = "/plugin-refresh";
    private static final String LOGIN_SUCCESS_TEMPLATE_RESOURCE = "/templates/jaipilot-login-success.html";
    private static final String LOGIN_SUCCESS_EMAIL_PLACEHOLDER = "{{SIGNED_IN_EMAIL}}";
    private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String CACHE_CONTROL_NO_STORE = "no-store";
    private static final String CALLBACK_METHODS = "GET, POST";
    private static final String HISTORY_CLEANUP_SCRIPT = """
            <script>
              if (window.history && window.history.replaceState) {
                window.history.replaceState({}, document.title, window.location.pathname);
              }
            </script>
            """;
    private static final String FRAGMENT_BRIDGE_SCRIPT = """
            <script>
              (function() {
                var hash = window.location.hash ? window.location.hash.substring(1) : "";
                if (!hash) {
                  return;
                }

                var payload = {};
                new URLSearchParams(hash).forEach(function(value, key) {
                  payload[key] = value;
                });

                fetch(window.location.pathname, {
                  method: "POST",
                  headers: {"Content-Type": "application/json"},
                  body: JSON.stringify(payload)
                }).then(function(response) {
                  return response.text();
                }).then(function(html) {
                  if (window.history && window.history.replaceState) {
                    window.history.replaceState({}, document.title, window.location.pathname);
                  }
                  document.open();
                  document.write(html);
                  document.close();
                }).catch(function() {
                  var status = document.getElementById("status");
                  if (status) {
                    status.innerHTML = "<h2>JAIPilot login failed</h2><p>Return to your terminal for details.</p>";
                  }
                });
              })();
            </script>
            """;

    private final CredentialsStore credentialsStore;
    private final HttpClient httpClient;
    private final String websiteBase;

    public AuthService(CredentialsStore credentialsStore) {
        this(
                credentialsStore,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .build(),
                resolveWebsiteBase()
        );
    }

    AuthService(CredentialsStore credentialsStore, HttpClient httpClient, String websiteBase) {
        this.credentialsStore = credentialsStore;
        this.httpClient = httpClient;
        this.websiteBase = trimTrailingSlash(websiteBase);
    }

    public TokenInfo startLogin(Duration timeout, PrintWriter out, PrintWriter err) {
        AtomicReference<TokenInfo> tokenReference = new AtomicReference<>();
        AtomicReference<String> errorReference = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String state = UUID.randomUUID().toString();
            int port = server.getAddress().getPort();
            String redirectUri = "http://127.0.0.1:" + port + CALLBACK_PATH;
            String loginUrl = buildLoginUrl(redirectUri, state);

            server.createContext(CALLBACK_PATH, exchange -> {
                boolean callbackCompleted = false;
                try {
                    callbackCompleted = handleLoginCallback(exchange, state, tokenReference, errorReference);
                } catch (Exception exception) {
                    errorReference.compareAndSet(null, "Failed to process the login callback.");
                    if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                        writeHtml(exchange, 500, errorHtml("JAIPilot login failed. Callback handling error."));
                    }
                    callbackCompleted = true;
                } finally {
                    exchange.close();
                    if (callbackCompleted) {
                        latch.countDown();
                        server.stop(0);
                    }
                }
            });
            server.start();

            out.println("Opening browser for JAIPilot login...");
            out.println("If the browser does not open, visit:");
            out.println(loginUrl);
            out.flush();
            openBrowser(loginUrl);

            boolean completed = latch.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                server.stop(0);
                throw new IllegalStateException("Login timed out after " + timeout.getSeconds() + " seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Login was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start login callback server.", exception);
        }

        if (errorReference.get() != null) {
            err.println("ERROR: " + errorReference.get());
            err.flush();
            throw new IllegalStateException(errorReference.get());
        }

        TokenInfo tokenInfo = tokenReference.get();
        if (tokenInfo == null || tokenInfo.accessToken() == null || tokenInfo.accessToken().isBlank()) {
            throw new IllegalStateException("Login did not return usable credentials.");
        }
        return tokenInfo;
    }

    public String ensureFreshAccessToken() {
        TokenInfo tokenInfo = credentialsStore.load();
        if (tokenInfo == null || tokenInfo.accessToken() == null || tokenInfo.accessToken().isBlank()) {
            return null;
        }
        if (!tokenInfo.isExpired(REFRESH_SKEW_SECONDS)) {
            return tokenInfo.accessToken();
        }

        TokenInfo refreshedToken = refresh(tokenInfo);
        if (refreshedToken == null) {
            return null;
        }
        credentialsStore.save(refreshedToken);
        return refreshedToken.accessToken();
    }

    private boolean handleLoginCallback(
            HttpExchange exchange,
            String expectedState,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        String requestMethod = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(requestMethod)) {
            return handleLoginCallbackGet(exchange, expectedState, tokenReference, errorReference);
        }
        if ("POST".equalsIgnoreCase(requestMethod)) {
            return handleLoginCallbackPost(exchange, expectedState, tokenReference, errorReference);
        }

        exchange.getResponseHeaders().add("Allow", CALLBACK_METHODS);
        exchange.sendResponseHeaders(405, -1);
        return false;
    }

    private boolean handleLoginCallbackGet(
            HttpExchange exchange,
            String expectedState,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        Map<String, String> params = splitQuery(exchange.getRequestURI().getRawQuery());
        if (params.isEmpty()) {
            writeHtml(exchange, 200, fragmentBridgeHtml());
            return false;
        }
        return completeLogin(exchange, expectedState, params, tokenReference, errorReference);
    }

    private boolean handleLoginCallbackPost(
            HttpExchange exchange,
            String expectedState,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        Map<String, String> params = readPostedParams(exchange);
        return completeLogin(exchange, expectedState, params, tokenReference, errorReference);
    }

    private boolean completeLogin(
            HttpExchange exchange,
            String expectedState,
            Map<String, String> params,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        if (!expectedState.equals(params.get("state"))) {
            errorReference.set("Login callback state mismatch.");
            writeHtml(exchange, 400, errorHtml("JAIPilot login failed. State mismatch."));
            return true;
        }

        String accessToken = params.getOrDefault("access_token", "");
        String refreshToken = params.getOrDefault("refresh_token", "");
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            errorReference.set("Login callback did not include both access and refresh tokens.");
            writeHtml(exchange, 400, errorHtml("JAIPilot login failed. Missing token data."));
            return true;
        }

        TokenInfo tokenInfo = tokenInfoFrom(params);
        credentialsStore.save(tokenInfo);
        tokenReference.set(tokenInfo);
        writeHtml(exchange, 200, successHtml(tokenInfo.email()));
        return true;
    }

    private TokenInfo refresh(TokenInfo tokenInfo) {
        try {
            String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of("refresh_token", tokenInfo.refreshToken()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(websiteBase + REFRESH_PATH))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", JSON_CONTENT_TYPE)
                    .header("Content-Type", JSON_CONTENT_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() / 100 != 2) {
                return null;
            }

            JsonNode json = OBJECT_MAPPER.readTree(response.body());
            String accessToken = json.path("access_token").asText("");
            if (accessToken.isBlank()) {
                return null;
            }

            String refreshToken = json.path("refresh_token").asText(tokenInfo.refreshToken());
            long expiresAt = json.path("expires_at").asLong(tokenInfo.expiresAtEpochSeconds());
            String email = json.path("email").asText(tokenInfo.email());
            return new TokenInfo(accessToken, refreshToken, expiresAt, email);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private void writeHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", HTML_CONTENT_TYPE);
        exchange.getResponseHeaders().add("Cache-Control", CACHE_CONTROL_NO_STORE);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private Map<String, String> readPostedParams(HttpExchange exchange) throws IOException {
        String requestBody;
        try (InputStream inputStream = exchange.getRequestBody()) {
            requestBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (requestBody.isBlank()) {
            return Map.of();
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains(JSON_CONTENT_TYPE)) {
            JsonNode json = OBJECT_MAPPER.readTree(requestBody);
            Map<String, String> values = new LinkedHashMap<>();
            json.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
            return values;
        }
        return splitQuery(requestBody);
    }

    private String buildLoginUrl(String redirectUri, String state) {
        return websiteBase + LOGIN_PATH
                + "?redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    private TokenInfo tokenInfoFrom(Map<String, String> params) {
        return new TokenInfo(
                params.getOrDefault("access_token", ""),
                params.getOrDefault("refresh_token", ""),
                parseLong(params.get("expires_at"), 0L),
                params.getOrDefault("email", "")
        );
    }

    private static Map<String, String> splitQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String pair : query.split("&")) {
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex < 0) {
                continue;
            }
            String key = urlDecode(pair.substring(0, equalsIndex));
            String value = urlDecode(pair.substring(equalsIndex + 1));
            values.put(key, value);
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static void openBrowser(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // The URL is also printed to the terminal for manual login.
        }
    }

    private static String successHtml(String email) {
        return renderLoginSuccessHtml(email);
    }

    private static String errorHtml(String message) {
        return htmlPage(
                "JAIPilot Login Error",
                """
                %s
                <h2>JAIPilot login failed</h2>
                <p>%s</p>
                <p>Return to your terminal for details.</p>
                """.formatted(HISTORY_CLEANUP_SCRIPT, escapeHtml(message))
        );
    }

    private static String fragmentBridgeHtml() {
        return htmlPage(
                "JAIPilot Connecting",
                """
                <div id="status">
                  <h2>Finishing JAIPilot login...</h2>
                  <p>Waiting for token data from the browser redirect.</p>
                </div>
                %s
                """.formatted(FRAGMENT_BRIDGE_SCRIPT)
        );
    }

    private static String renderLoginSuccessHtml(String email) {
        String safeEmail = escapeHtml(email == null ? "" : email);
        String template = defaultLoginSuccessTemplate();
        try (InputStream inputStream = AuthService.class.getResourceAsStream(LOGIN_SUCCESS_TEMPLATE_RESOURCE)) {
            if (inputStream != null) {
                template = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Fall back to the built-in template if the resource cannot be loaded.
        }
        return template.replace(LOGIN_SUCCESS_EMAIL_PLACEHOLDER, safeEmail);
    }

    private static String defaultLoginSuccessTemplate() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>JAIPilot Connected</title>
                </head>
                <body style="font-family: 'Segoe UI', sans-serif; padding: 24px;">
                  %s
                  <h1>JAIPilot is connected to the CLI</h1>
                  <p>Signed in as: %s</p>
                  <p>You can now safely close this tab and switch back to your terminal</p>
                </body>
                </html>
                """.formatted(HISTORY_CLEANUP_SCRIPT, LOGIN_SUCCESS_EMAIL_PLACEHOLDER);
    }

    private static String htmlPage(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>%s</title>
                </head>
                <body style="font-family: 'Segoe UI', sans-serif; padding: 24px;">
                  %s
                </body>
                </html>
                """.formatted(escapeHtml(title), body);
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String resolveWebsiteBase() {
        String override = System.getenv("JAIPILOT_WEBSITE_BASE");
        if (override == null || override.isBlank()) {
            override = System.getProperty("jaipilot.website.base");
        }
        if (override != null && !override.isBlank()) {
            return override;
        }
        return "https://www.jaipilot.com";
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
