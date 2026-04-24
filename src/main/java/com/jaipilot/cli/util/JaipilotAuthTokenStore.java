package com.jaipilot.cli.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JaipilotAuthTokenStore {

    private static final String AUTH_TOKEN_FILENAME = "auth-token";
    private static final String CREDENTIALS_FILENAME = "credentials.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JaipilotAuthTokenStore() {
    }

    public static Path resolveAuthTokenPath() {
        return JaipilotPaths.resolveConfigHome().resolve(AUTH_TOKEN_FILENAME);
    }

    public static Path resolveCredentialsPath() {
        return JaipilotPaths.resolveConfigHome().resolve(CREDENTIALS_FILENAME);
    }

    public static Path saveAuthToken(String authToken) throws IOException {
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalArgumentException("authToken is required");
        }

        Path configHome = JaipilotPaths.resolveConfigHome();
        Files.createDirectories(configHome);

        Path authTokenPath = resolveAuthTokenPath();
        Files.writeString(
                authTokenPath,
                authToken.trim() + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        return authTokenPath;
    }

    public static String readAuthToken() throws IOException {
        Path authTokenPath = resolveAuthTokenPath();
        if (!Files.isRegularFile(authTokenPath)) {
            return null;
        }

        String storedToken = Files.readString(authTokenPath);
        if (storedToken == null) {
            return null;
        }
        String trimmedToken = storedToken.trim();
        return trimmedToken.isBlank() ? null : trimmedToken;
    }

    public static String readBrowserAccessToken() throws IOException {
        Path credentialsPath = resolveCredentialsPath();
        if (!Files.isRegularFile(credentialsPath)) {
            return null;
        }

        JsonNode root = OBJECT_MAPPER.readTree(credentialsPath.toFile());
        JsonNode accessTokenNode = root.path("accessToken");
        if (!accessTokenNode.isTextual()) {
            return null;
        }

        String token = accessTokenNode.asText().trim();
        return token.isBlank() ? null : token;
    }
}
