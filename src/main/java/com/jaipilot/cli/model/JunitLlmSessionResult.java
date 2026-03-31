package com.jaipilot.cli.model;

import java.nio.file.Path;
import java.util.List;

public record JunitLlmSessionResult(
        String sessionId,
        Path outputPath,
        List<String> usedContextClassPaths
) {
}
