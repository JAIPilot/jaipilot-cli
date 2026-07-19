package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitHubReleaseClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesStableVersionFromLatestReleasePayload() throws Exception {
        Optional<String> version = GitHubReleaseClient.parseLatestVersion(
                """
                {
                  "url": "https://api.github.com/repos/JAIPilot/jaipilot-cli/releases/42",
                  "tag_name": "v1.10.2",
                  "name": "JAIPilot 1.10.2"
                }
                """,
                objectMapper
        );

        assertEquals(Optional.of("1.10.2"), version);
    }

    @Test
    void acceptsStableTagWithoutLeadingV() throws Exception {
        Optional<String> version = GitHubReleaseClient.parseLatestVersion(
                "{\"tag_name\":\"2.0.0\"}",
                objectMapper
        );

        assertEquals(Optional.of("2.0.0"), version);
    }

    @Test
    void ignoresMissingOrNonStableReleaseTags() throws Exception {
        assertTrue(GitHubReleaseClient.parseLatestVersion("{}", objectMapper).isEmpty());
        assertTrue(GitHubReleaseClient.parseLatestVersion(
                "{\"tag_name\":\"v2.0.0-rc.1\"}",
                objectMapper
        ).isEmpty());
        assertTrue(GitHubReleaseClient.parseLatestVersion(
                "{\"tag_name\":\"release-current\"}",
                objectMapper
        ).isEmpty());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(
                JsonProcessingException.class,
                () -> GitHubReleaseClient.parseLatestVersion("{not-json", objectMapper)
        );
    }
}
