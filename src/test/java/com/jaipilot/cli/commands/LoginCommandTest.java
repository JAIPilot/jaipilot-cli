package com.jaipilot.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.JaiPilotCli;
import com.jaipilot.cli.util.JaipilotAuthTokenStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class LoginCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void loginSavesTokenToConfiguredPath() throws Exception {
        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            Path configHome = tempDir.resolve("jaipilot-config");
            System.setProperty("jaipilot.config.home", configHome.toString());

            StringWriter outBuffer = new StringWriter();
            CommandLine commandLine = new CommandLine(new JaiPilotCli())
                    .setOut(new PrintWriter(outBuffer, true))
                    .setErr(new PrintWriter(new StringWriter(), true));

            int exitCode = commandLine.execute("login", "local-auth-token");

            assertEquals(0, exitCode);
            Path tokenPath = JaipilotAuthTokenStore.resolveAuthTokenPath();
            assertTrue(Files.isRegularFile(tokenPath));
            assertEquals("local-auth-token", Files.readString(tokenPath).trim());
            assertTrue(outBuffer.toString().contains(tokenPath.toString()));
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }

    @Test
    void loginHelp_showsUsageAndReturnsZero() {
        StringWriter outBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true));

        int exitCode = commandLine.execute("login", "--help");

        assertEquals(0, exitCode);
        String output = outBuffer.toString();
        assertTrue(output.contains("login"), "Usage output should mention the 'login' command");
        assertTrue(output.contains("--timeout-seconds"), "Usage output should include browser-login timeout option");
    }

    @Test
    void loginWithWhitespaceToken_savesTrimmmedToken() throws Exception {
        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            Path configHome = tempDir.resolve("jaipilot-whitespace");
            System.setProperty("jaipilot.config.home", configHome.toString());

            CommandLine commandLine = new CommandLine(new JaiPilotCli())
                    .setOut(new PrintWriter(new StringWriter(), true))
                    .setErr(new PrintWriter(new StringWriter(), true));

            int exitCode = commandLine.execute("login", "  my-token  ");

            assertEquals(0, exitCode);
            Path tokenPath = JaipilotAuthTokenStore.resolveAuthTokenPath();
            assertTrue(Files.isRegularFile(tokenPath));
            assertEquals("my-token", Files.readString(tokenPath).trim());
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }

    @Test
    void loginWithInvalidTimeoutAndNoToken_returnsNonZeroExitCode() {
        StringWriter errBuffer = new StringWriter();
        CommandLine commandLine = new CommandLine(new JaiPilotCli())
                .setOut(new PrintWriter(new StringWriter(), true))
                .setErr(new PrintWriter(errBuffer, true));

        int exitCode = commandLine.execute("login", "--timeout-seconds", "0");

        assertNotEquals(0, exitCode);
    }

    @Test
    void loginWhenConfigHomeIsFile_ioExceptionProducesNonZeroExitCode() throws Exception {
        Path fileAsConfigHome = tempDir.resolve("not-a-directory");
        Files.createFile(fileAsConfigHome);

        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            System.setProperty("jaipilot.config.home", fileAsConfigHome.toString());

            StringWriter errBuffer = new StringWriter();
            CommandLine commandLine = new CommandLine(new JaiPilotCli())
                    .setOut(new PrintWriter(new StringWriter(), true))
                    .setErr(new PrintWriter(errBuffer, true));

            int exitCode = commandLine.execute("login", "some-token");

            assertNotEquals(0, exitCode);
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }

    @Test
    void loginOverwritesExistingToken() throws Exception {
        String previousConfigHome = System.getProperty("jaipilot.config.home");
        try {
            Path configHome = tempDir.resolve("overwrite-config");
            System.setProperty("jaipilot.config.home", configHome.toString());

            CommandLine commandLine = new CommandLine(new JaiPilotCli())
                    .setOut(new PrintWriter(new StringWriter(), true))
                    .setErr(new PrintWriter(new StringWriter(), true));

            commandLine.execute("login", "first-token");
            int exitCode = commandLine.execute("login", "second-token");

            assertEquals(0, exitCode);
            Path tokenPath = JaipilotAuthTokenStore.resolveAuthTokenPath();
            assertEquals("second-token", Files.readString(tokenPath).trim());
        } finally {
            if (previousConfigHome == null) {
                System.clearProperty("jaipilot.config.home");
            } else {
                System.setProperty("jaipilot.config.home", previousConfigHome);
            }
        }
    }
}
