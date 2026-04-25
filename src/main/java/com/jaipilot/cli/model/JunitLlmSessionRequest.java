package com.jaipilot.cli.model;

import java.nio.file.Path;

public record JunitLlmSessionRequest(
        Path projectRoot,
        Path cutPath,
        String sessionId,
        String testFilePath,
        String initialTestClassCode,
        String newTestClassCode,
        String clientLogs
) {
}
