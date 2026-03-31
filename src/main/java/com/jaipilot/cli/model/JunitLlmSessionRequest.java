package com.jaipilot.cli.model;

import java.nio.file.Path;

public record JunitLlmSessionRequest(
        Path projectRoot,
        Path cutPath,
        Path outputPath,
        JunitLlmOperation operation,
        String sessionId,
        String initialTestClassCode,
        String newTestClassCode,
        String clientLogs
) {
}
