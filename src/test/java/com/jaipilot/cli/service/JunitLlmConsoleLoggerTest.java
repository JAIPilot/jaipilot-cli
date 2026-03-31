package com.jaipilot.cli.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunitLlmConsoleLoggerTest {

    @Test
    void announceTestFileDiffPrintsColoredDiffOnce() {
        StringWriter output = new StringWriter();
        JunitLlmConsoleLogger logger = new JunitLlmConsoleLogger(new PrintWriter(output, true));

        logger.announceTestFileDiff("class OldTest {\n}\n", "class NewTest {\n}\n");

        String text = output.toString();
        assertTrue(text.contains("Source diff:"));
        assertTrue(text.contains("\u001B[31m"));
        assertTrue(text.contains("- class OldTest {"));
        assertTrue(text.contains("\u001B[32m"));
        assertTrue(text.contains("+ class NewTest {"));
        assertFalse(text.contains("No file changes."));
    }
}
