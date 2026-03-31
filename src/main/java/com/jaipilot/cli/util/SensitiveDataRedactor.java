package com.jaipilot.cli.util;

import java.util.List;
import java.util.regex.Pattern;

public final class SensitiveDataRedactor {

    private static final List<RedactionRule> REDACTION_RULES = List.of(
            rule("(?i)(Authorization\\s*:\\s*Bearer\\s+)([^\\s]+)", "$1[REDACTED]"),
            rule("(?i)(Bearer\\s+)([A-Za-z0-9._~+/=-]+)", "$1[REDACTED]"),
            rule("(?i)(https?://[^\\s:/?#]+:)([^@\\s/]+)(@)", "$1[REDACTED]$3"),
            rule(
                    "(?i)(--(?:api-key|api_key|token|password|passwd|secret|access-token|refresh-token|jwt-token|authorization))(\\s+)([^\\s]+)",
                    "$1$2[REDACTED]"
            ),
            rule(
                    "(?i)(--(?:api-key|api_key|token|password|passwd|secret|access-token|refresh-token|jwt-token|authorization)=)([^\\s]+)",
                    "$1[REDACTED]"
            ),
            rule(
                    "(?i)(-D[^=\\s]*(?:password|passwd|secret|token|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|jwt)[^=\\s]*=)([^\\s]+)",
                    "$1[REDACTED]"
            ),
            rule(
                    "(?im)(\\b[A-Z0-9_]*(?:TOKEN|PASSWORD|PASSWD|SECRET|API[_-]?KEY|ACCESS[_-]?TOKEN|REFRESH[_-]?TOKEN|JWT)[A-Z0-9_]*=)(\\S+)",
                    "$1[REDACTED]"
            ),
            rule(
                    "(?i)((?:\"|\\b)(?:access_token|refresh_token|api_key|apikey|password|passwd|secret|jwt|jwt_token)(?:\"|\\b)\\s*[:=]\\s*\"?)([^\"\\s,}]+)",
                    "$1[REDACTED]"
            )
    );

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String redacted = value;
        for (RedactionRule redactionRule : REDACTION_RULES) {
            redacted = redactionRule.pattern().matcher(redacted).replaceAll(redactionRule.replacement());
        }
        return redacted;
    }

    public static String redactCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        return redact(String.join(" ", command));
    }

    private static RedactionRule rule(String regex, String replacement) {
        return new RedactionRule(Pattern.compile(regex), replacement);
    }

    private record RedactionRule(Pattern pattern, String replacement) {
    }
}
