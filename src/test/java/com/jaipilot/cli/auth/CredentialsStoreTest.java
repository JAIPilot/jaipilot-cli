package com.jaipilot.cli.auth;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CredentialsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveLoadAndClearRoundTripsTokenInfo() {
        CredentialsStore credentialsStore = new CredentialsStore(tempDir.resolve("credentials.json"));
        TokenInfo tokenInfo = new TokenInfo("access-token", "refresh-token", 1234L, "user@example.com");

        credentialsStore.save(tokenInfo);

        assertEquals(tokenInfo, credentialsStore.load());

        credentialsStore.clear();

        assertNull(credentialsStore.load());
        assertFalse(java.nio.file.Files.exists(credentialsStore.storePath()));
    }

    @Test
    void saveRestrictsPosixPermissionsWhenSupported() throws Exception {
        CredentialsStore credentialsStore = new CredentialsStore(tempDir.resolve("secure/credentials.json"));

        credentialsStore.save(new TokenInfo("access-token", "refresh-token", 1234L, "user@example.com"));

        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                java.nio.file.Files.getPosixFilePermissions(credentialsStore.storePath().getParent())
        );
        assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                java.nio.file.Files.getPosixFilePermissions(credentialsStore.storePath())
        );
    }
}
