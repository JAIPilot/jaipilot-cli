package com.jaipilot.cli.model;

import java.util.List;

public record InvokeJunitLlmRequest(
        String sessionId,
        String type,
        String cutName,
        String testClassName,
        String mockitoVersion,
        String cutCode,
        String initialTestClassCode,
        List<String> contextClasses,
        String newTestClassCode,
        String clientLogs
) {
}
