package com.jaipilot.cli.model;

import java.nio.file.Path;

public record JunitLlmSessionResult(
        String sessionId,
        Path outputPath
) {
}
