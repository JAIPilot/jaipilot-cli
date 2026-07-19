package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class CodexJsonLogRendererTest {

    private final StringWriter buffer = new StringWriter();
    private final TerminalUi ui = new TerminalUi(new PrintWriter(buffer, true));
    private final CodexJsonLogRenderer renderer = new CodexJsonLogRenderer(ui, new PrintWriter(buffer, true));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rendersAgentMessagesAsReadableLogs() {
        String line = """
                {"type":"item.completed","item":{"type":"agent_message","text":"Generated tests for OrderService."}}
                """.trim();

        String rendered = renderer.render(line);

        assertTrue(rendered.contains("[agent]"));
        assertTrue(rendered.contains("Generated tests for OrderService."));
        assertFalse(rendered.contains("{\"type\""));
    }

    @Test
    void rendersShellCommandsWithoutRawJson() {
        String line = """
                {"type":"item.started","item":{"type":"command_execution","command":"bash -lc ./mvnw -Dtest=OrderServiceTest test"}}
                """.trim();

        String rendered = renderer.render(line);

        assertTrue(rendered.contains("[shell]"));
        assertTrue(rendered.contains("./mvnw -Dtest=OrderServiceTest test"));
        assertFalse(rendered.contains("{\"type\""));
    }

    @Test
    void doesNotLeakReasoningText() {
        String line = """
                {"type":"item.completed","item":{"type":"reasoning","text":"Private hidden reasoning"}}
                """.trim();

        String rendered = renderer.render(line);

        assertTrue(rendered.contains("[reasoning]"));
        assertTrue(rendered.contains("internal reasoning hidden"));
        assertFalse(rendered.contains("Private hidden reasoning"));
    }

    @Test
    void indentsEveryContinuationLineForMessagesAndFailures() {
        String agentMessage = renderer.render("""
                {"type":"item.completed","item":{"type":"agent_message","text":"Created test.\\nRunning verification."}}
                """.trim());
        String failure = renderer.render("""
                {"type":"turn.failed","message":"Generation failed.\\nInspect the command output."}
                """.trim());

        assertEquals("[agent] Created test.\n        Running verification.", agentMessage);
        assertEquals("[codex error] Generation failed.\n              Inspect the command output.", failure);
    }

    @Test
    void rendersNestedTurnFailureMessageFromCodex01441() {
        String line = """
                {"type":"turn.failed","error":{"message":"unexpected status 401 Unauthorized: Missing bearer or basic authentication in header, url: https://api.openai.com/v1/responses, request id: req_e8b171c2ee0a4173a63f12a3a9bfe819"}}
                """.trim();

        String rendered = renderer.render(line);

        assertEquals(
                "[codex error] unexpected status 401 Unauthorized: Missing bearer or basic authentication in header, "
                        + "url: https://api.openai.com/v1/responses, "
                        + "request id: req_e8b171c2ee0a4173a63f12a3a9bfe819",
                rendered
        );
    }

    @Test
    void fallsBackAcrossNestedAndLegacyTurnFailureTextShapes() {
        String textualError = renderer.render("""
                {"type":"turn.failed","error":"connection closed before response completed"}
                """.trim());
        String nestedContent = renderer.render("""
                {"type":"turn.failed","error":{"content":{"text":"request was rejected"}}}
                """.trim());
        String legacyMessage = renderer.render("""
                {"type":"turn.failed","message":"legacy failure message"}
                """.trim());

        assertEquals("[codex error] connection closed before response completed", textualError);
        assertEquals("[codex error] request was rejected", nestedContent);
        assertEquals("[codex error] legacy failure message", legacyMessage);
    }

    @Test
    void rendersStructuredCodexErrorsWithoutTracingMetadata() {
        String first = renderer.render("2026-07-18T14:40:57.353113Z ERROR codex_core::tools::router: "
                + "error=collab spawn failed: no thread with id: worker-12");
        String second = renderer.render("2026-07-18T14:42:13.696197Z ERROR codex_core::tools::router: "
                + "error=timeout_ms must be at least 10000");

        assertEquals("[codex error] collab spawn failed: no thread with id: worker-12", first);
        assertEquals("[codex error] timeout_ms must be at least 10000", second);
    }

    @Test
    void badgesUnstructuredDiagnosticsAndSuppressesKnownInternalNoise() {
        String warning = renderer.render("WARNING: proceeding without the optional shell snapshot");
        String noise = renderer.render("2026-07-18T14:39:01Z WARN codex_core::rollout::list: "
                + "state db missing rollout path for thread abc123");

        assertEquals("[codex warning] proceeding without the optional shell snapshot", warning);
        assertNull(noise);
    }

    @Test
    void rendersCompletedCommandWithFailedStatusAsFailure() {
        String line = commandEvent(
                "item.completed",
                "/bin/zsh -lc \"./mvnw -Dtest=OwnerControllerTest test\"",
                "failed",
                1
        );

        String rendered = renderer.render(line);

        assertTrue(rendered.contains("[shell failed]"));
        assertTrue(rendered.contains("./mvnw -Dtest=OwnerControllerTest test"));
        assertTrue(rendered.contains("(exit 1)"));
    }

    @Test
    void normalizesShellLauncherWhitespaceAndQuoting() {
        String command = "/bin/zsh -lc \"  ./mvnw   -Dtest=OwnerControllerTest\n"
                + " test   &&   printf   'done'  \"";
        String escapedQuote = "'\"'\"'";
        String heavilyQuotedCommand = "/bin/zsh -lc 'printf " + escapedQuote + "hello world" + escapedQuote
                + " && ./mvnw test'";

        String rendered = renderer.render(commandEvent("item.started", command, "in_progress", null));
        String heavilyQuoted = renderer.render(commandEvent(
                "item.started",
                heavilyQuotedCommand,
                "in_progress",
                null
        ));

        assertEquals("[shell] ./mvnw -Dtest=OwnerControllerTest test && printf 'done'", rendered);
        assertEquals("[shell] printf 'hello world' && ./mvnw test", heavilyQuoted);
    }

    @Test
    void capsPathologicalCommandsWhileKeepingTheUsefulTail() {
        String command = "/bin/zsh -lc \"printf '" + "x".repeat(500)
                + "' && ./mvnw -Dtest=OwnerControllerTest test\"";

        String rendered = renderer.render(commandEvent("item.started", command, "in_progress", null));

        assertTrue(rendered.contains(" ... "));
        assertTrue(rendered.endsWith("./mvnw -Dtest=OwnerControllerTest test"));
        assertTrue(rendered.length() <= 250);
    }

    private String commandEvent(String eventType, String command, String status, Integer exitCode) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "command_execution");
        item.put("command", command);
        if (status != null) {
            item.put("status", status);
        }
        if (exitCode != null) {
            item.put("exit_code", exitCode);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", eventType);
        root.set("item", item);
        return root.toString();
    }
}
