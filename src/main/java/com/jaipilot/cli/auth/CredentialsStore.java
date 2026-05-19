package com.jaipilot.cli.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.util.JaipilotAuthTokenStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class CredentialsStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path storePath;

    public CredentialsStore() {
        this(JaipilotAuthTokenStore.resolveCredentialsPath());
    }

    CredentialsStore(Path storePath) {
        this.storePath = storePath;
    }

    public TokenInfo load() {
        try {
            if (!Files.isRegularFile(storePath)) {
                return null;
            }
            return OBJECT_MAPPER.readValue(storePath.toFile(), TokenInfo.class);
        } catch (IOException exception) {
            return null;
        }
    }

    public void save(TokenInfo tokenInfo) {
        Path tempFile = null;
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                applyOwnerOnlyPermissions(parent, true);
            }

            byte[] serialized = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(tokenInfo);
            tempFile = Files.createTempFile(
                    parent != null ? parent : Path.of(System.getProperty("user.dir")),
                    storePath.getFileName().toString(),
                    ".tmp"
            );
            Files.write(tempFile, serialized);
            applyOwnerOnlyPermissions(tempFile, false);
            moveIntoPlace(tempFile);
            applyOwnerOnlyPermissions(storePath, false);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save credentials to " + storePath, exception);
        } finally {
            cleanupTempFile(tempFile);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(storePath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clear credentials at " + storePath, exception);
        }
    }

    public Path storePath() {
        return storePath;
    }

    private void moveIntoPlace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, storePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // The final credentials file has already been written or the original failure is more important.
        }
    }

    private void applyOwnerOnlyPermissions(Path path, boolean directory) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try {
            Files.setPosixFilePermissions(path, directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
            return;
        } catch (UnsupportedOperationException | IOException ignored) {
            // Fall back to best-effort owner-only permissions below.
        }

        File file = path.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) {
            file.setExecutable(true, true);
        }
    }
}
