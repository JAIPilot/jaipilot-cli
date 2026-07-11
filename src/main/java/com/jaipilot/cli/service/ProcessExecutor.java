package com.jaipilot.cli.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessExecutor {

    private static final long HEARTBEAT_MILLIS = 200L;

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter
    ) throws IOException, InterruptedException {
        return execute(command, workingDirectory, timeout, verbose, verboseWriter, null, ProgressListener.noOp());
    }

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter,
            String stdinText
    ) throws IOException, InterruptedException {
        return execute(command, workingDirectory, timeout, verbose, verboseWriter, stdinText, ProgressListener.noOp());
    }

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter,
            String stdinText,
            ProgressListener progressListener
    ) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);

        Process process = processBuilder.start();
        ProgressListener listener = progressListener == null ? ProgressListener.noOp() : progressListener;
        listener.onStart(command);
        writeInput(process, stdinText);
        StringBuilder output = new StringBuilder();
        Thread readerThread = new Thread(() -> readOutput(process, output, verbose, verboseWriter), "jaipilot-process-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        WaitResult waitResult = waitForProcess(process, timeout, listener);
        Duration elapsed = waitResult.elapsed();
        boolean finished = waitResult.finished();
        if (!finished) {
            process.destroyForcibly();
        }

        readerThread.join(TimeUnit.SECONDS.toMillis(5));
        int exitCode = finished ? process.exitValue() : -1;
        ExecutionResult result = new ExecutionResult(command, exitCode, !finished, output.toString(), elapsed);
        listener.onFinish(result, elapsed);
        return result;
    }

    private void writeInput(Process process, String stdinText) throws IOException {
        try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
            if (stdinText != null && !stdinText.isBlank()) {
                writer.write(stdinText);
            }
        }
    }

    private WaitResult waitForProcess(
            Process process,
            Duration timeout,
            ProgressListener progressListener
    ) throws InterruptedException {
        long timeoutMillis = timeout.toMillis();
        long elapsedMillis = 0L;
        long startedAt = System.nanoTime();
        while (elapsedMillis < timeoutMillis) {
            long waitMillis = Math.min(HEARTBEAT_MILLIS, timeoutMillis - elapsedMillis);
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return new WaitResult(true, Duration.ofNanos(System.nanoTime() - startedAt));
            }
            elapsedMillis += waitMillis;
            progressListener.onHeartbeat(Duration.ofMillis(elapsedMillis));
        }
        return new WaitResult(false, Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static void readOutput(Process process, StringBuilder output, boolean verbose, PrintWriter verboseWriter) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                if (verbose) {
                    verboseWriter.println(line);
                }
            }
            if (verbose) {
                verboseWriter.flush();
            }
        } catch (IOException exception) {
            output.append("Failed to read process output: ")
                    .append(exception.getMessage())
                    .append(System.lineSeparator());
        }
    }

    public record ExecutionResult(
            List<String> command,
            int exitCode,
            boolean timedOut,
            String output,
            Duration elapsed
    ) {
    }

    public interface ProgressListener {

        default void onStart(List<String> command) {
        }

        default void onHeartbeat(Duration elapsed) {
        }

        default void onFinish(ExecutionResult result, Duration elapsed) {
        }

        static ProgressListener noOp() {
            return NoOpProgressListener.INSTANCE;
        }
    }

    private enum NoOpProgressListener implements ProgressListener {
        INSTANCE
    }

    private record WaitResult(
            boolean finished,
            Duration elapsed
    ) {
    }
}
