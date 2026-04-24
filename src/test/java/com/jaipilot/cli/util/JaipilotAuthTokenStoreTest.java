package com.jaipilot.cli.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JaipilotAuthTokenStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void readReturnsNullWhenTokenDoesNotExist() throws Exception {
        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            System.setProperty("jaipilot.config.home", tempDir.resolve("config").toString());
            assertNull(JaipilotAuthTokenStore.readAuthToken());
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }

    @Test
    void saveAndReadRoundTrip() throws Exception {
        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            System.setProperty("jaipilot.config.home", tempDir.resolve("config").toString());
            JaipilotAuthTokenStore.saveAuthToken("round-trip-token");
            assertEquals("round-trip-token", JaipilotAuthTokenStore.readAuthToken());
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }

    @Test
    void readBrowserAccessTokenReturnsTokenFromCredentialsJson() throws Exception {
        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            Path configHome = tempDir.resolve("config");
            System.setProperty("jaipilot.config.home", configHome.toString());
            Files.createDirectories(configHome);
            Files.writeString(
                    configHome.resolve("credentials.json"),
                    """
                    {
                      "accessToken": "browser-access-token",
                      "refreshToken": "refresh-token",
                      "expiresAtEpochSeconds": 9999999999
                    }
                    """
            );

            assertEquals("browser-access-token", JaipilotAuthTokenStore.readBrowserAccessToken());
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }

    @Test
    void readBrowserAccessTokenReturnsNullWhenCredentialsMissing() throws Exception {
        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            System.setProperty("jaipilot.config.home", tempDir.resolve("config").toString());
            assertNull(JaipilotAuthTokenStore.readBrowserAccessToken());
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }
}
