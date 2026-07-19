package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

final class GitHubReleaseClient implements StartupUpdateService.LatestReleaseProvider {

    private static final URI LATEST_RELEASE_URI = URI.create(
            "https://api.github.com/repos/JAIPilot/jaipilot-cli/releases/latest"
    );
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI latestReleaseUri;

    GitHubReleaseClient() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new ObjectMapper(),
                LATEST_RELEASE_URI
        );
    }

    GitHubReleaseClient(HttpClient httpClient, ObjectMapper objectMapper, URI latestReleaseUri) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.latestReleaseUri = latestReleaseUri;
    }

    @Override
    public Optional<String> latestVersion() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(latestReleaseUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "jaipilot-cli-update-check")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub release check returned HTTP " + response.statusCode());
        }
        return parseLatestVersion(response.body(), objectMapper);
    }

    static Optional<String> parseLatestVersion(String json, ObjectMapper objectMapper) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        String tagName = root.path("tag_name").asText("");
        return StartupUpdateService.normalizeVersion(tagName);
    }
}
