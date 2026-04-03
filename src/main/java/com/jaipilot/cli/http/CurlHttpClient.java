package com.jaipilot.cli.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class CurlHttpClient {

    private static final String CURL_BINARY = "curl";
    private static final String CURL_HTTP_STATUS_FORMAT = "%{http_code}";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_CONNECT_TIMEOUT_SECONDS = 20L;

    interface CommandExecutor {
        CommandResult execute(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    record CommandResult(int exitCode, String stdout, String stderr) {
        CommandResult {
            Objects.requireNonNull(stdout, "stdout");
            Objects.requireNonNull(stderr, "stderr");
        }
    }

    public record CurlResponse(int statusCode, String body) {
        public CurlResponse {
            Objects.requireNonNull(body, "body");
        }
    }

    public enum FailureKind {
        MISSING_CURL,
        TIMEOUT,
        EXECUTION,
        INVALID_RESPONSE
    }

    public static final class CurlException extends IOException {

        private final FailureKind kind;
        private final Integer exitCode;
        private final String stderr;

        private CurlException(
                FailureKind kind,
                String message,
                Integer exitCode,
                String stderr,
                Throwable cause
        ) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.exitCode = exitCode;
            this.stderr = stderr == null ? "" : stderr;
        }

        public FailureKind kind() {
            return kind;
        }

        public Integer exitCode() {
            return exitCode;
        }

        public String stderr() {
            return stderr;
        }

        static CurlException missingCurl(IOException cause) {
            return new CurlException(
                    FailureKind.MISSING_CURL,
                    "JAIPilot requires curl on PATH for network requests.",
                    null,
                    "",
                    cause
            );
        }

        static CurlException timeout(Duration timeout) {
            long timeoutSeconds = timeout == null ? DEFAULT_TIMEOUT.getSeconds() : toSeconds(timeout);
            return new CurlException(
                    FailureKind.TIMEOUT,
                    "curl timed out after " + timeoutSeconds + "s.",
                    null,
                    "",
                    null
            );
        }

        static CurlException execution(int exitCode, String stderr) {
            String message = "curl failed with exit code " + exitCode + ".";
            return new CurlException(FailureKind.EXECUTION, message, exitCode, stderr, null);
        }

        static CurlException invalidResponse(String stdout) {
            return new CurlException(
                    FailureKind.INVALID_RESPONSE,
                    "curl did not return a valid HTTP status code: `" + stdout.trim() + "`.",
                    null,
                    "",
                    null
            );
        }
    }

    private final CommandExecutor commandExecutor;

    public CurlHttpClient() {
        this(new DefaultCommandExecutor());
    }

    CurlHttpClient(CommandExecutor commandExecutor) {
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
    }

    public CurlResponse request(
            String method,
            java.net.URI uri,
            Map<String, String> headers,
            String requestBody,
            Duration timeout
    ) throws IOException, InterruptedException {
        Duration effectiveTimeout = normalizeTimeout(timeout);
        Path responseBodyFile = null;
        Path requestBodyFile = null;
        try {
            responseBodyFile = Files.createTempFile("jaipilot-curl-response-", ".tmp");
            List<String> command = baseCommand(method, uri, headers, effectiveTimeout);

            if (requestBody != null) {
                requestBodyFile = Files.createTempFile("jaipilot-curl-request-", ".json");
                Files.writeString(requestBodyFile, requestBody, StandardCharsets.UTF_8);
                command.add("--data-binary");
                command.add("@" + requestBodyFile);
            }

            command.add("--output");
            command.add(responseBodyFile.toString());
            command.add("--write-out");
            command.add(CURL_HTTP_STATUS_FORMAT);
            command.add(uri.toString());

            CommandResult result = runCommand(command, effectiveTimeout);
            int statusCode = parseStatusCode(result.stdout());
            String body = Files.readString(responseBodyFile, StandardCharsets.UTF_8);
            return new CurlResponse(statusCode, body);
        } finally {
            deleteIfExists(requestBodyFile);
            deleteIfExists(responseBodyFile);
        }
    }

    public int download(
            java.net.URI uri,
            Map<String, String> headers,
            Path destination,
            Duration timeout
    ) throws IOException, InterruptedException {
        Duration effectiveTimeout = normalizeTimeout(timeout);
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }

        List<String> command = baseCommand("GET", uri, headers, effectiveTimeout);
        command.add("--output");
        command.add(destination.toString());
        command.add("--write-out");
        command.add(CURL_HTTP_STATUS_FORMAT);
        command.add(uri.toString());

        CommandResult result = runCommand(command, effectiveTimeout);
        return parseStatusCode(result.stdout());
    }

    private CommandResult runCommand(List<String> command, Duration timeout) throws IOException, InterruptedException {
        CommandResult result;
        try {
            result = commandExecutor.execute(command, timeout);
        } catch (CurlException exception) {
            throw exception;
        } catch (IOException exception) {
            if (isMissingCurl(exception)) {
                throw CurlException.missingCurl(exception);
            }
            throw new CurlException(
                    FailureKind.EXECUTION,
                    "Failed to execute curl.",
                    null,
                    exception.getMessage(),
                    exception
            );
        }

        if (result.exitCode() != 0) {
            throw CurlException.execution(result.exitCode(), result.stderr());
        }
        return result;
    }

    private static List<String> baseCommand(
            String method,
            java.net.URI uri,
            Map<String, String> headers,
            Duration timeout
    ) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        List<String> command = new ArrayList<>();
        command.add(CURL_BINARY);
        command.add("--silent");
        command.add("--show-error");
        command.add("--location");
        command.add("--request");
        command.add(method.toUpperCase(Locale.ROOT));
        command.add("--connect-timeout");
        command.add(String.valueOf(connectTimeoutSeconds(timeout)));
        command.add("--max-time");
        command.add(String.valueOf(toSeconds(timeout)));
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        for (Map.Entry<String, String> header : safeHeaders.entrySet()) {
            command.add("--header");
            command.add(header.getKey() + ": " + header.getValue());
        }
        return command;
    }

    private static int parseStatusCode(String output) throws CurlException {
        String statusText = output == null ? "" : output.trim();
        if (!statusText.matches("\\d{3}")) {
            throw CurlException.invalidResponse(statusText);
        }
        return Integer.parseInt(statusText);
    }

    private static Duration normalizeTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return DEFAULT_TIMEOUT;
        }
        return timeout;
    }

    private static long toSeconds(Duration timeout) {
        long millis = timeout.toMillis();
        long seconds = millis / 1_000L;
        if (millis % 1_000L != 0L) {
            seconds += 1L;
        }
        return Math.max(1L, seconds);
    }

    private static long connectTimeoutSeconds(Duration timeout) {
        return Math.max(1L, Math.min(toSeconds(timeout), MAX_CONNECT_TIMEOUT_SECONDS));
    }

    private static boolean isMissingCurl(IOException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("cannot run program \"curl\"")
                || normalized.contains("error=2")
                || normalized.contains("no such file or directory");
    }

    private static void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    private static final class DefaultCommandExecutor implements CommandExecutor {

        @Override
        public CommandResult execute(List<String> command, Duration timeout) throws IOException, InterruptedException {
            Process process;
            try {
                process = new ProcessBuilder(command).start();
            } catch (IOException exception) {
                if (isMissingCurl(exception)) {
                    throw CurlException.missingCurl(exception);
                }
                throw exception;
            }

            long waitMillis = normalizeTimeout(timeout).toMillis() + 1_000L;
            boolean finished = process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw CurlException.timeout(timeout);
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), stdout, stderr);
        }
    }
}
