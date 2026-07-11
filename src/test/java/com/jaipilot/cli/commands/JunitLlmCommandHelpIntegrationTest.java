package com.jaipilot.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.JaiPilotCli;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class JunitLlmCommandHelpIntegrationTest {

    @Test
    void generateHelpShowsNoCustomCommandOptions() {
        String helpOutput = executeHelp("generate");

        assertTrue(helpOutput.contains("--changed"));
        assertTrue(helpOutput.contains("--coverage-below"));
        assertFalse(helpOutput.contains("--output"));
        assertFalse(helpOutput.contains("--build-executable"));
        assertFalse(helpOutput.contains("--build-arg"));
        assertFalse(helpOutput.contains("--maven-executable"));
        assertFalse(helpOutput.contains("--gradle-executable"));
        assertFalse(helpOutput.contains("--timeout-seconds"));
        assertFalse(helpOutput.contains("--attempt-number"));
        assertFalse(helpOutput.contains("--backend-url"));
        assertFalse(helpOutput.contains("--cached-context"));
        assertFalse(helpOutput.contains("--client-logs"));
        assertFalse(helpOutput.contains("--client-logs-file"));
        assertFalse(helpOutput.contains("--context"));
        assertFalse(helpOutput.contains("--cut-name"));
        assertFalse(helpOutput.contains("--jwt-token"));
        assertFalse(helpOutput.contains("--mockito-version"));
        assertFalse(helpOutput.contains("--new-testclass-file"));
        assertFalse(helpOutput.contains("--project-root"));
        assertFalse(helpOutput.contains("--session-id"));
        assertFalse(helpOutput.contains("--test-class-name"));
        assertFalse(helpOutput.contains("--verbose"));
    }

    @Test
    void rootHelpListsGenerateAndDoctorCommands() {
        StringWriter outBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true));

        int exitCode = commandLine.execute("--help");

        assertEquals(0, exitCode);
        assertTrue(outBuffer.toString().contains("generate"));
        assertTrue(outBuffer.toString().contains("doctor"));
        assertTrue(outBuffer.toString().contains("status"));
        assertFalse(outBuffer.toString().contains("login"));
    }

    private String executeHelp(String command) {
        StringWriter outBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true));

        int exitCode = commandLine.execute(command, "--help");

        assertEquals(0, exitCode);
        return outBuffer.toString();
    }
}
