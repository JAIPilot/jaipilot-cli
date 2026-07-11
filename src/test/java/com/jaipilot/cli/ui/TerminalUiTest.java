package com.jaipilot.cli.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class TerminalUiTest {

    @Test
    void coverageBarUsesAsciiMeterAndPercentage() {
        TerminalUi ui = new TerminalUi(new PrintWriter(new StringWriter(), true), CommandLine.Help.Ansi.OFF, false);

        String rendered = ui.coverageBar(82.5d, 80.0d);

        assertTrue(rendered.contains("["));
        assertTrue(rendered.contains("]"));
        assertTrue(rendered.contains("82.5%"));
        assertTrue(rendered.contains("="));
        assertTrue(rendered.contains("."));
    }

    @Test
    void printTableRendersHeadersAndRows() {
        StringWriter buffer = new StringWriter();
        TerminalUi ui = new TerminalUi(new PrintWriter(buffer, true), CommandLine.Help.Ansi.OFF, false);

        ui.printTable(
                List.of("Class", "Line", "Tests"),
                List.of(List.of("com.example.OrderService", "85.0%", "[test present]"))
        );

        String output = buffer.toString();
        assertTrue(output.contains("Class"));
        assertTrue(output.contains("Line"));
        assertTrue(output.contains("com.example.OrderService"));
        assertTrue(output.contains("[test present]"));
    }
}
