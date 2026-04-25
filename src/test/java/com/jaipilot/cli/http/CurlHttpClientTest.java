package com.jaipilot.cli.http;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurlHttpClientTest {

    @Test
    void requestBuildsExpectedCurlCommandForJsonPost() throws Exception {
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        CurlHttpClient client = new CurlHttpClient((command, timeout) -> {
            capturedCommand.set(List.copyOf(command));
            return new CurlHttpClient.CommandResult(0, "200", "");
        });

        CurlHttpClient.CurlResponse response = client.request(
                "POST",
                URI.create("https://example.com/functions/v1/invoke-junit-llm-cli"),
                Map.of(
                        "Accept", "application/json",
                        "Authorization", "Bearer token-123",
                        "Content-Type", "application/json"
                ),
                "{\"cutName\":\"CrashController\"}",
                Duration.ofSeconds(9)
        );

        List<String> command = capturedCommand.get();
        assertNotNull(command);
        assertEquals(200, response.statusCode());
        assertEquals("curl", command.get(0));
        assertTrue(command.contains("--silent"));
        assertTrue(command.contains("--show-error"));
        assertTrue(command.contains("--location"));
        assertTrue(command.contains("--request"));
        assertTrue(command.contains("POST"));
        assertTrue(command.contains("--header"));
        assertTrue(command.contains("--data-binary"));
        assertTrue(command.stream().anyMatch(arg -> arg.startsWith("@")));
        assertTrue(command.contains("--output"));
        assertTrue(command.contains("--write-out"));
        assertTrue(command.contains("%{http_code}"));
        assertEquals("https://example.com/functions/v1/invoke-junit-llm-cli", command.get(command.size() - 1));
    }

    @Test
    void requestFailsWhenCurlIsMissing() {
        CurlHttpClient client = new CurlHttpClient((command, timeout) -> {
            throw new IOException("Cannot run program \"curl\": error=2, No such file or directory");
        });

        CurlHttpClient.CurlException exception = assertThrows(
                CurlHttpClient.CurlException.class,
                () -> client.request(
                        "GET",
                        URI.create("https://example.com/api"),
                        Map.of(),
                        null,
                        Duration.ofSeconds(5)
                )
        );

        assertEquals(CurlHttpClient.FailureKind.MISSING_CURL, exception.kind());
    }

    @Test
    void requestFailsWhenCurlReturnsNonZeroExitCode() {
        CurlHttpClient client = new CurlHttpClient((command, timeout) ->
                new CurlHttpClient.CommandResult(60, "", "SSL certificate problem"));

        CurlHttpClient.CurlException exception = assertThrows(
                CurlHttpClient.CurlException.class,
                () -> client.request(
                        "GET",
                        URI.create("https://example.com/api"),
                        Map.of(),
                        null,
                        Duration.ofSeconds(5)
                )
        );

        assertEquals(CurlHttpClient.FailureKind.EXECUTION, exception.kind());
        assertEquals(60, exception.exitCode());
        assertTrue(exception.stderr().contains("SSL certificate problem"));
    }

    @Test
    void requestFailsOnInvalidHttpStatusWriteOut() {
        CurlHttpClient client = new CurlHttpClient((command, timeout) ->
                new CurlHttpClient.CommandResult(0, "not-a-status", ""));

        CurlHttpClient.CurlException exception = assertThrows(
                CurlHttpClient.CurlException.class,
                () -> client.request(
                        "GET",
                        URI.create("https://example.com/api"),
                        Map.of(),
                        null,
                        Duration.ofSeconds(5)
                )
        );

        assertEquals(CurlHttpClient.FailureKind.INVALID_RESPONSE, exception.kind());
    }

    @Test
    void downloadBuildsExpectedCurlCommand() throws Exception {
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        CurlHttpClient client = new CurlHttpClient((command, timeout) -> {
            capturedCommand.set(List.copyOf(command));
            return new CurlHttpClient.CommandResult(0, "302", "");
        });

        int statusCode = client.download(
                URI.create("https://example.com/archive.tar.gz"),
                Map.of("User-Agent", "jaipilot-cli/test"),
                Path.of("target/tmp/archive.tar.gz"),
                Duration.ofSeconds(10)
        );

        List<String> command = capturedCommand.get();
        assertNotNull(command);
        assertEquals(302, statusCode);
        assertTrue(command.contains("--request"));
        assertTrue(command.contains("GET"));
        assertTrue(command.contains("--output"));
        assertEquals("https://example.com/archive.tar.gz", command.get(command.size() - 1));
    }
}
