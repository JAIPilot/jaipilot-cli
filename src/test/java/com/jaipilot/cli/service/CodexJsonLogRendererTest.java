package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class CodexJsonLogRendererTest {

    private final StringWriter buffer = new StringWriter();
    private final TerminalUi ui = new TerminalUi(new PrintWriter(buffer, true));
    private final CodexJsonLogRenderer renderer = new CodexJsonLogRenderer(ui, new PrintWriter(buffer, true));

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
}
