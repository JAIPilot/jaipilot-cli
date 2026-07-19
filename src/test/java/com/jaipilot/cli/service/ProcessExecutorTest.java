package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void timeoutTerminatesDescendantProcesses() throws Exception {
        ProcessExecutor executor = new ProcessExecutor();

        ProcessExecutor.ExecutionResult result = executor.execute(
                List.of("sh", "-c", "sleep 60 & child=$!; echo $child; wait"),
                Path.of("").toAbsolutePath(),
                Duration.ofMillis(500),
                false,
                new PrintWriter(new StringWriter(), true)
        );

        assertTrue(result.timedOut());
        long childPid = Long.parseLong(result.output().lines().findFirst().orElseThrow().trim());
        assertProcessStopped(childPid, "Timed-out child process is still alive");
    }

    @Test
    void blockedStdinWriterStillHonorsTimeoutAndTerminatesDescendants() throws Exception {
        ProcessExecutor executor = new ProcessExecutor();

        ProcessExecutor.ExecutionResult result = executor.execute(
                List.of("sh", "-c", "sleep 5 & child=$!; echo $child; wait"),
                Path.of("").toAbsolutePath(),
                Duration.ofMillis(500),
                false,
                new PrintWriter(new StringWriter(), true),
                "prompt".repeat(500_000)
        );

        assertTrue(result.timedOut());
        long childPid = Long.parseLong(result.output().lines().findFirst().orElseThrow().trim());
        assertProcessStopped(childPid, "Blocked stdin writer left a child process alive");
    }

    @Test
    void listenerStartFailureTerminatesDescendantProcesses() {
        ProcessExecutor executor = new ProcessExecutor();
        Path pidFile = this.tempDir.resolve("listener-child.pid");
        AtomicLong childPid = new AtomicLong();
        ProcessExecutor.ProgressListener listener = new ProcessExecutor.ProgressListener() {
            @Override
            public void onStart(List<String> command) {
                childPid.set(waitForPid(pidFile));
                throw new IllegalStateException("listener failed");
            }
        };

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(
                        childCommand(pidFile, false),
                        Path.of("").toAbsolutePath(),
                        Duration.ofSeconds(10),
                        false,
                        new PrintWriter(new StringWriter(), true),
                        null,
                        listener,
                        ProcessExecutor.OutputListener.noOp()
                )
        );

        assertEquals("listener failed", failure.getMessage());
        assertProcessStopped(childPid.get(), "Listener failure left a child process alive");
    }

    @Test
    void stdinWriteFailureTerminatesDescendantProcesses() {
        ProcessExecutor executor = new ProcessExecutor();
        Path pidFile = this.tempDir.resolve("stdin-child.pid");
        AtomicLong childPid = new AtomicLong();
        ProcessExecutor.ProgressListener listener = new ProcessExecutor.ProgressListener() {
            @Override
            public void onStart(List<String> command) {
                childPid.set(waitForPid(pidFile));
            }
        };

        assertThrows(
                IOException.class,
                () -> executor.execute(
                        childCommand(pidFile, true),
                        Path.of("").toAbsolutePath(),
                        Duration.ofSeconds(10),
                        false,
                        new PrintWriter(new StringWriter(), true),
                        "prompt".repeat(200_000),
                        listener,
                        ProcessExecutor.OutputListener.noOp()
                )
        );

        assertProcessStopped(childPid.get(), "Stdin failure left a child process alive");
    }

    private List<String> childCommand(Path pidFile, boolean closeStdin) {
        String script = (closeStdin ? "exec 0<&-; " : "")
                + "sleep 60 & child=$!; printf '%s\\n' \"$child\" > \"$1\"; wait";
        return List.of("sh", "-c", script, "jaipilot-process-test", pidFile.toString());
    }

    private long waitForPid(Path pidFile) {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                if (Files.isRegularFile(pidFile) && Files.size(pidFile) > 0L) {
                    return Long.parseLong(Files.readString(pidFile).trim());
                }
                Thread.sleep(20L);
            } catch (NumberFormatException ignored) {
                // The shell may still be flushing the PID file.
            } catch (Exception exception) {
                throw new IllegalStateException("Failed while waiting for child PID", exception);
            }
        }
        throw new IllegalStateException("Child process did not publish its PID: " + pidFile);
    }

    private void assertProcessStopped(long pid, String message) {
        ProcessHandle child = ProcessHandle.of(pid).orElse(null);
        for (int attempt = 0; attempt < 40 && child != null && child.isAlive(); attempt++) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for child process cleanup", exception);
            }
        }
        assertFalse(child != null && child.isAlive(), message + ": " + pid);
    }
}
