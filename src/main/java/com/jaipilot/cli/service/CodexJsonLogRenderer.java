package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CodexJsonLogRenderer implements ProcessExecutor.OutputListener {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            return line;
        }
        String type = root.path("type").asText("");
        return switch (type) {
            case "thread.started" -> format(TerminalUi.Tone.PRIMARY, "codex", "session started");
            case "turn.started" -> format(TerminalUi.Tone.PRIMARY, "codex", "generation started");
            case "turn.completed" -> format(TerminalUi.Tone.SUCCESS, "codex", "generation completed");
            case "turn.failed" -> format(TerminalUi.Tone.ERROR, "codex", nonBlank(
                    extractText(root),
                    "generation failed"
            ));
            case "error" -> format(TerminalUi.Tone.ERROR, "codex", nonBlank(extractText(root), "unexpected error"));
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
        String command = nonBlank(item.path("command").asText(""), extractText(item));
        if (command == null || command.isBlank()) {
            command = "shell command";
        }
        return switch (state) {
            case "started" -> format(TerminalUi.Tone.PRIMARY, "shell", command);
            case "failed" -> format(TerminalUi.Tone.ERROR, "shell", command);
            default -> null;
        };
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
        return ui.badge(tone, label) + " " + message;
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
