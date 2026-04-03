package com.jaipilot.cli.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.JaiPilotVersionProvider;
import com.jaipilot.cli.http.CurlHttpClient;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

final class GitHubReleaseClient implements ReleaseClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_REPO = "JAIPilot/jaipilot-cli";
    private static final String DEFAULT_API_BASE = "https://api.github.com";
    private static final String DEFAULT_DOWNLOAD_BASE = "https://github.com";

    private final CurlHttpClient curlHttpClient;
    private final String repo;
    private final String apiBase;
    private final String downloadBase;

    GitHubReleaseClient() {
        this(
                new CurlHttpClient(),
                DEFAULT_REPO,
                DEFAULT_API_BASE,
                DEFAULT_DOWNLOAD_BASE
        );
    }

    GitHubReleaseClient(
            CurlHttpClient curlHttpClient,
            String repo,
            String apiBase,
            String downloadBase
    ) {
        this.curlHttpClient = curlHttpClient;
        this.repo = repo;
        this.apiBase = trimTrailingSlash(apiBase);
        this.downloadBase = trimTrailingSlash(downloadBase);
    }

    @Override
    public String fetchLatestVersion(Duration timeout) {
        URI uri = URI.create(apiBase + "/repos/" + repo + "/releases/latest");
        CurlHttpClient.CurlResponse response = send(uri, timeout);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Update check failed with HTTP " + response.statusCode() + ".");
        }
        try {
            JsonNode json = OBJECT_MAPPER.readTree(response.body());
            String tagName = json.path("tag_name").asText(null);
            if (tagName == null || tagName.isBlank()) {
                throw new IllegalStateException("Update check did not return a usable release version.");
            }
            return VersionComparator.normalize(tagName);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse the latest release metadata.", exception);
        }
    }

    @Override
    public URI archiveUri(String version, String platform) {
        String normalizedVersion = VersionComparator.normalize(version);
        return URI.create(
                downloadBase
                        + "/"
                        + repo
                        + "/releases/download/v"
                        + normalizedVersion
                        + "/jaipilot-"
                        + normalizedVersion
                        + "-"
                        + platform
                        + ".tar.gz"
        );
    }

    @Override
    public URI checksumUri(String version, String platform) {
        return URI.create(archiveUri(version, platform).toString() + ".sha256");
    }

    @Override
    public void download(URI source, Path destination, Duration timeout) {
        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare the update download directory.", exception);
        }

        if ("file".equalsIgnoreCase(source.getScheme())) {
            try {
                Files.copy(Path.of(source), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read the local update asset: " + source, exception);
            }
        }

        int statusCode = downloadViaCurl(source, destination, timeout);
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("Update download failed with HTTP " + statusCode + " for " + source + ".");
        }
    }

    private CurlHttpClient.CurlResponse send(URI uri, Duration timeout) {
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
                : timeout;
        try {
            return curlHttpClient.request(
                    "GET",
                    uri,
                    headers(),
                    null,
                    effectiveTimeout
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reach the update server via curl. " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The update request was interrupted.", exception);
        }
    }

    private int downloadViaCurl(URI source, Path destination, Duration timeout) {
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
                : timeout;
        try {
            return curlHttpClient.download(
                    source,
                    headers(),
                    destination,
                    effectiveTimeout
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to download the update via curl. " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The update request was interrupted.", exception);
        }
    }

    private Map<String, String> headers() {
        return Map.of(
                "Accept", "application/vnd.github+json",
                "User-Agent", "jaipilot-cli/" + JaiPilotVersionProvider.resolveVersion()
        );
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Update endpoint base URL must not be blank.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
