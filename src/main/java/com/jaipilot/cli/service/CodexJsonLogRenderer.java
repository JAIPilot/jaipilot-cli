package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodexJsonLogRenderer implements ProcessExecutor.OutputListener {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_COMMAND_DISPLAY_LENGTH = 240;
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;?\\d]*[ -/]*[@-~]");
    private static final Pattern STRUCTURED_DIAGNOSTIC = Pattern.compile(
            "^(?:\\d{4}-\\d{2}-\\d{2}T\\S+\\s+)?(TRACE|DEBUG|INFO|WARN|WARNING|ERROR)\\s+"
                    + "[\\w.$-]+(?:::[\\w.$-]+)*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SIMPLE_DIAGNOSTIC = Pattern.compile(
            "^(ERROR|WARN|WARNING|INFO)\\s*[:=-]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SHELL_LAUNCHER = Pattern.compile(
            "^(?:(?:['\"]?/usr/bin/env['\"]?\\s+)?['\"]?(?:/bin/)?(?:bash|zsh|sh)['\"]?\\s+"
                    + "['\"]?-(?:lc|cl|c)['\"]?\\s+)(.+)$"
    );
    private static final List<String> SUPPRESSED_DIAGNOSTIC_FRAGMENTS = List.of(
            "state db missing rollout path for thread",
            "failed to delete shell snapshot",
            "failed to remove shell snapshot",
            "unable to remove shell snapshot"
    );

    private final TerminalUi ui;
    private final PrintWriter out;

    public CodexJsonLogRenderer(TerminalUi ui, PrintWriter out) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void onLine(String line) {
        String rendered = render(line);
        if (rendered == null || rendered.isBlank()) {
            return;
        }
        out.println(rendered);
        out.flush();
    }

    String render(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        JsonNode root = parse(line);
        if (root == null) {
            return renderDiagnostic(line);
        }
        String type = root.path("type").asText("");
        return switch (type) {
            case "thread.started" -> format(TerminalUi.Tone.PRIMARY, "codex", "session started");
            case "turn.started" -> format(TerminalUi.Tone.PRIMARY, "codex", "generation started");
            case "turn.completed" -> format(TerminalUi.Tone.SUCCESS, "codex", "generation completed");
            case "turn.failed" -> format(TerminalUi.Tone.ERROR, "codex error", nonBlank(
                    extractFailureText(root),
                    "generation failed"
            ));
            case "error" -> format(
                    TerminalUi.Tone.ERROR,
                    "codex error",
                    nonBlank(extractText(root), "unexpected error")
            );
            default -> renderItemEvent(type, root.path("item"));
        };
    }

    private String renderItemEvent(String eventType, JsonNode item) {
        if (!eventType.startsWith("item.") || item.isMissingNode()) {
            return null;
        }
        String state = eventType.substring("item.".length());
        String itemType = item.path("type").asText("");
        return switch (itemType) {
            case "agent_message" -> renderAgentMessage(state, item);
            case "command_execution" -> renderCommandExecution(state, item);
            case "mcp_tool_call" -> renderMcpToolCall(state, item);
            case "web_search" -> renderWebSearch(state, item);
            case "plan_update" -> renderPlanUpdate(state, item);
            case "file_change" -> renderFileChange(state, item);
            case "reasoning" -> renderReasoning(state);
            default -> null;
        };
    }

    private String renderAgentMessage(String state, JsonNode item) {
        String text = extractText(item);
        if (text == null || text.isBlank()) {
            return null;
        }
        if ("completed".equals(state) || "updated".equals(state) || "started".equals(state)) {
            return format(TerminalUi.Tone.PRIMARY, "agent", text);
        }
        return null;
    }

    private String renderCommandExecution(String state, JsonNode item) {
        String command = normalizeCommand(nonBlank(extractCommand(item), extractText(item)));
        if (command == null || command.isBlank()) {
            command = "shell command";
        }
        boolean failed = "failed".equals(state)
                || "failed".equalsIgnoreCase(item.path("status").asText(""))
                || hasNonZeroExitCode(item);
        if (failed) {
            String exitSuffix = hasNonZeroExitCode(item) ? " (exit " + item.path("exit_code").asInt() + ")" : "";
            return format(TerminalUi.Tone.ERROR, "shell failed", command + exitSuffix);
        }
        return "started".equals(state) ? format(TerminalUi.Tone.PRIMARY, "shell", command) : null;
    }

    private String renderMcpToolCall(String state, JsonNode item) {
        if (!"started".equals(state) && !"failed".equals(state)) {
            return null;
        }
        String server = firstNonBlank(
                item.path("server").asText(""),
                item.path("server_name").asText(""),
                item.path("mcp_server").asText("")
        );
        String tool = firstNonBlank(
                item.path("tool").asText(""),
                item.path("tool_name").asText(""),
                item.path("name").asText("")
        );
        String message = joinNonBlank(" ", server, tool).trim();
        if (message.isBlank()) {
            message = "tool call";
        }
        return format("failed".equals(state) ? TerminalUi.Tone.ERROR : TerminalUi.Tone.PRIMARY, "mcp", message);
    }

    private String renderWebSearch(String state, JsonNode item) {
        if (!"started".equals(state) && !"completed".equals(state)) {
            return null;
        }
        String query = firstNonBlank(
                item.path("query").asText(""),
                extractText(item)
        );
        if (query == null || query.isBlank()) {
            query = "search";
        }
        return format(TerminalUi.Tone.PRIMARY, "search", query);
    }

    private String renderPlanUpdate(String state, JsonNode item) {
        if (!"completed".equals(state) && !"updated".equals(state) && !"started".equals(state)) {
            return null;
        }
        String description = extractPlan(item);
        if (description == null || description.isBlank()) {
            description = nonBlank(extractText(item), "plan updated");
        }
        return format(TerminalUi.Tone.PRIMARY, "plan", description);
    }

    private String renderFileChange(String state, JsonNode item) {
        if (!"completed".equals(state) && !"updated".equals(state)) {
            return null;
        }
        String pathSummary = extractPaths(item);
        if (pathSummary == null || pathSummary.isBlank()) {
            pathSummary = nonBlank(extractText(item), "file changes");
        }
        return format(TerminalUi.Tone.SUCCESS, "edit", pathSummary);
    }

    private String renderReasoning(String state) {
        if (!"started".equals(state) && !"completed".equals(state) && !"updated".equals(state)) {
            return null;
        }
        return format(TerminalUi.Tone.WARN, "reasoning", "internal reasoning hidden");
    }

    private String format(TerminalUi.Tone tone, String label, String message) {
        String normalized = message.replace("\r\n", "\n").replace('\r', '\n');
        String continuationIndent = " ".repeat(label.length() + 3);
        return ui.badge(tone, label) + " " + normalized.replace("\n", "\n" + continuationIndent);
    }

    private String renderDiagnostic(String line) {
        String normalized = normalizeWhitespace(ANSI_ESCAPE.matcher(line).replaceAll(""));
        if (normalized == null || normalized.isBlank() || isKnownInternalNoise(normalized)) {
            return null;
        }

        Matcher structured = STRUCTURED_DIAGNOSTIC.matcher(normalized);
        if (structured.matches()) {
            String severity = structured.group(1).toUpperCase(Locale.ROOT);
            if ("TRACE".equals(severity) || "DEBUG".equals(severity)) {
                return null;
            }
            return format(
                    diagnosticTone(severity),
                    diagnosticLabel(severity),
                    nonBlank(cleanDiagnosticMessage(structured.group(2)), "Codex diagnostic")
            );
        }

        Matcher simple = SIMPLE_DIAGNOSTIC.matcher(normalized);
        if (simple.matches()) {
            String severity = simple.group(1).toUpperCase(Locale.ROOT);
            return format(
                    diagnosticTone(severity),
                    diagnosticLabel(severity),
                    nonBlank(cleanDiagnosticMessage(simple.group(2)), "Codex diagnostic")
            );
        }
        return format(TerminalUi.Tone.PRIMARY, "codex", normalized);
    }

    private TerminalUi.Tone diagnosticTone(String severity) {
        return switch (severity) {
            case "ERROR" -> TerminalUi.Tone.ERROR;
            case "WARN", "WARNING" -> TerminalUi.Tone.WARN;
            default -> TerminalUi.Tone.PRIMARY;
        };
    }

    private String diagnosticLabel(String severity) {
        return switch (severity) {
            case "ERROR" -> "codex error";
            case "WARN", "WARNING" -> "codex warning";
            default -> "codex";
        };
    }

    private String cleanDiagnosticMessage(String message) {
        if (message == null) {
            return null;
        }
        return normalizeWhitespace(message.replaceFirst("(?i)^(?:error|warning|message)\\s*=\\s*", ""));
    }

    private boolean isKnownInternalNoise(String line) {
        String lowerCase = line.toLowerCase(Locale.ROOT);
        return SUPPRESSED_DIAGNOSTIC_FRAGMENTS.stream().anyMatch(lowerCase::contains);
    }

    private String extractCommand(JsonNode item) {
        JsonNode command = item.path("command");
        if (command.isTextual()) {
            return command.asText();
        }
        if (command.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : command) {
                if (part.isValueNode() && !part.asText().isBlank()) {
                    parts.add(part.asText());
                }
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        }
        return null;
    }

    private boolean hasNonZeroExitCode(JsonNode item) {
        JsonNode exitCode = item.path("exit_code");
        return exitCode.isIntegralNumber() && exitCode.asInt() != 0;
    }

    private String normalizeCommand(String command) {
        String normalized = normalizeWhitespace(command);
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }
        Matcher shellLauncher = SHELL_LAUNCHER.matcher(normalized);
        if (shellLauncher.matches()) {
            normalized = shellLauncher.group(1).trim();
        }
        normalized = simplifyShellQuoting(stripMatchingOuterQuotes(normalized));
        normalized = normalizeWhitespace(normalized);
        return abbreviateMiddle(normalized, MAX_COMMAND_DISPLAY_LENGTH);
    }

    private String simplifyShellQuoting(String value) {
        return value
                .replace("'\"'\"'", "'")
                .replace("'\\''", "'")
                .replace("\\\"", "\"");
    }

    private String stripMatchingOuterQuotes(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '\'' || first == '\"') && first == last) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private String abbreviateMiddle(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        String separator = " ... ";
        int tailLength = 64;
        int headLength = maximumLength - separator.length() - tailLength;
        return value.substring(0, headLength).stripTrailing()
                + separator
                + value.substring(value.length() - tailLength).stripLeading();
    }

    private String normalizeWhitespace(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private JsonNode parse(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(trimmed);
        } catch (Exception exception) {
            return null;
        }
    }

    private String extractPlan(JsonNode item) {
        JsonNode steps = item.path("steps");
        if (!steps.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode step : steps) {
            String text = firstNonBlank(
                    step.path("step").asText(""),
                    step.path("text").asText(""),
                    step.path("description").asText("")
            );
            String status = step.path("status").asText("");
            if (text == null || text.isBlank()) {
                continue;
            }
            values.add(status == null || status.isBlank() ? text : status + ": " + text);
        }
        return values.isEmpty() ? null : String.join(" | ", values);
    }

    private String extractPaths(JsonNode item) {
        List<String> paths = new ArrayList<>();
        collectPaths(item, paths);
        if (paths.isEmpty()) {
            return null;
        }
        if (paths.size() == 1) {
            return paths.get(0);
        }
        return paths.get(0) + " +" + (paths.size() - 1) + " more";
    }

    private void collectPaths(JsonNode node, List<String> paths) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            String path = firstNonBlank(
                    node.path("path").asText(""),
                    node.path("file").asText(""),
                    node.path("filepath").asText("")
            );
            if (path != null && !path.isBlank() && !paths.contains(path)) {
                paths.add(path);
            }
            node.elements().forEachRemaining(child -> collectPaths(child, paths));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectPaths(child, paths));
        }
    }

    private String extractText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode child : node) {
                String text = extractText(child);
                if (text != null && !text.isBlank()) {
                    parts.add(text.trim());
                }
            }
            return parts.isEmpty() ? null : String.join("\n", parts);
        }
        if (node.isObject()) {
            String directText = firstNonBlank(
                    node.path("text").asText(""),
                    node.path("message").asText(""),
                    node.path("title").asText(""),
                    node.path("summary").asText("")
            );
            if (directText != null && !directText.isBlank()) {
                return directText;
            }
            String contentText = extractText(node.path("content"));
            if (contentText != null && !contentText.isBlank()) {
                return contentText;
            }
            String outputText = extractText(node.path("output"));
            if (outputText != null && !outputText.isBlank()) {
                return outputText;
            }
            String resultText = extractText(node.path("result"));
            if (resultText != null && !resultText.isBlank()) {
                return resultText;
            }
        }
        return null;
    }

    private String extractFailureText(JsonNode root) {
        return firstNonBlank(
                extractText(root.path("error")),
                extractText(root)
        );
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String joinNonBlank(String delimiter, String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return String.join(delimiter, parts);
    }
}
