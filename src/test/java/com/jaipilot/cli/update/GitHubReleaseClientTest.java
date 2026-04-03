package com.jaipilot.cli.update;

import com.jaipilot.cli.http.CurlHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubReleaseClientTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchLatestVersionParsesReleaseTag() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/owner/repo/releases/latest", exchange ->
                writeJson(exchange, "{\"tag_name\":\"v1.2.3\"}"));
        server.start();

        GitHubReleaseClient client = new GitHubReleaseClient(
                new CurlHttpClient(),
                "owner/repo",
                baseUrl(),
                baseUrl()
        );

        String latestVersion = client.fetchLatestVersion(Duration.ofSeconds(5));

        assertEquals("1.2.3", latestVersion);
    }

    @Test
    void downloadSavesReleaseAsset() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/owner/repo/releases/download/v1.2.3/jaipilot-1.2.3-linux-x64.tar.gz",
                exchange -> writeBytes(exchange, "archive-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream")
        );
        server.start();

        GitHubReleaseClient client = new GitHubReleaseClient(
                new CurlHttpClient(),
                "owner/repo",
                baseUrl(),
                baseUrl()
        );

        Path destination = tempDir.resolve("jaipilot-1.2.3-linux-x64.tar.gz");
        client.download(client.archiveUri("1.2.3", "linux-x64"), destination, Duration.ofSeconds(5));

        assertEquals("archive-bytes", Files.readString(destination, StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        writeBytes(exchange, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void writeBytes(HttpExchange exchange, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
