package com.jaipilot.cli.model;

public record InvokeJunitLlmRequest(
        String sessionId,
        String cutName,
        String testFilePath,
        String cutCode,
        String initialTestClassCode,
        String newTestClassCode,
        String clientLogs
) {
}
