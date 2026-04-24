package com.jaipilot.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
