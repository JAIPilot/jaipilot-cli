package com.jaipilot.cli.util;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataRedactorTest {

    @Test
    void redactMasksCommonSecretFormats() {
        String original = """
                Authorization: Bearer abc.def.ghi
                refresh_token=refresh-secret
                {"password":"super-secret","access_token":"jwt-secret"}
                https://user:plain-text-password@example.com/repo.git
                """;

        String redacted = SensitiveDataRedactor.redact(original);

        assertTrue(redacted.contains("Authorization: Bearer [REDACTED]"));
        assertTrue(redacted.contains("refresh_token=[REDACTED]"));
        assertTrue(redacted.contains("\"password\":\"[REDACTED]\""));
        assertTrue(redacted.contains("\"access_token\":\"[REDACTED]\""));
        assertTrue(redacted.contains("https://user:[REDACTED]@example.com/repo.git"));
        assertFalse(redacted.contains("plain-text-password"));
        assertFalse(redacted.contains("refresh-secret"));
        assertFalse(redacted.contains("super-secret"));
        assertFalse(redacted.contains("jwt-secret"));
    }

    @Test
    void redactCommandMasksSensitiveFlagsAndSystemProperties() {
        String redacted = SensitiveDataRedactor.redactCommand(List.of(
                "./mvnw",
                "-Drepo.password=hunter2",
                "--jwt-token",
                "abc123"
        ));

        assertEquals("./mvnw -Drepo.password=[REDACTED] --jwt-token [REDACTED]", redacted);
    }
}
