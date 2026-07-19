package com.jaipilot.cli.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ProcessExecutor {

    private static final long HEARTBEAT_MILLIS = 200L;

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter
    ) throws IOException, InterruptedException {
        return execute(command, workingDirectory, timeout, verbose, verboseWriter, null, ProgressListener.noOp(), OutputListener.noOp());
    }

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter,
            String stdinText
    ) throws IOException, InterruptedException {
        return execute(command, workingDirectory, timeout, verbose, verboseWriter, stdinText, ProgressListener.noOp(), OutputListener.noOp());
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
        return execute(command, workingDirectory, timeout, verbose, verboseWriter, stdinText, progressListener, OutputListener.noOp());
    }

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter,
            String stdinText,
            ProgressListener progressListener,
            OutputListener outputListener
    ) throws IOException, InterruptedException {
        return execute(
                command,
                workingDirectory,
                timeout,
                verbose,
                verboseWriter,
                stdinText,
                progressListener,
                outputListener,
                Map.of()
        );
    }

    public ExecutionResult execute(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            boolean verbose,
            PrintWriter verboseWriter,
            String stdinText,
            ProgressListener progressListener,
            OutputListener outputListener,
            Map<String, String> environmentOverrides
    ) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        if (environmentOverrides != null && !environmentOverrides.isEmpty()) {
            processBuilder.environment().putAll(environmentOverrides);
        }

        Process process = processBuilder.start();
        try {
            ProgressListener listener = progressListener == null ? ProgressListener.noOp() : progressListener;
            OutputListener lineListener = outputListener == null ? OutputListener.noOp() : outputListener;
            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(
                    () -> readOutput(process, output, verbose, verboseWriter, lineListener),
                    "jaipilot-process-reader"
            );
            readerThread.setDaemon(true);
            readerThread.start();

            listener.onStart(command);
            AtomicReference<Throwable> inputFailure = new AtomicReference<>();
            Thread inputWriterThread = new Thread(
                    () -> writeInput(process, stdinText, inputFailure),
                    "jaipilot-process-input-writer"
            );
            inputWriterThread.setDaemon(true);
            inputWriterThread.start();

            WaitResult waitResult = waitForProcess(process, timeout, listener, inputFailure);
            Duration elapsed = waitResult.elapsed();
            boolean finished = waitResult.finished();
            if (!finished && !terminateProcessTree(process)) {
                throw new IllegalStateException("Failed to terminate the timed-out process tree: " + command);
            }

            inputWriterThread.join(TimeUnit.SECONDS.toMillis(5));
            if (inputWriterThread.isAlive()) {
                throw new IllegalStateException("Process input writer did not stop: " + command);
            }
            if (finished) {
                throwInputFailure(inputFailure.get());
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(5));
            int exitCode = finished ? process.exitValue() : -1;
            ExecutionResult result = new ExecutionResult(command, exitCode, !finished, output.toString(), elapsed);
            listener.onFinish(result, elapsed);
            return result;
        } catch (InterruptedException exception) {
            terminateAfterFailure(process, exception);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (IOException | RuntimeException | Error exception) {
            terminateAfterFailure(process, exception);
            throw exception;
        }
    }

    private void writeInput(Process process, String stdinText, AtomicReference<Throwable> inputFailure) {
        try {
            writeInput(process, stdinText);
        } catch (Throwable failure) {
            inputFailure.compareAndSet(null, failure);
        }
    }

    private void throwInputFailure(Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("Failed to write process input.", failure);
    }

    private void terminateAfterFailure(Process process, Throwable failure) {
        try {
            if (!terminateProcessTree(process)) {
                failure.addSuppressed(new IllegalStateException("Failed to terminate the process tree after an exception."));
            }
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private boolean terminateProcessTree(Process process) {
        Set<ProcessHandle> handles = new LinkedHashSet<>();
        handles.add(process.toHandle());
        long startedAt = System.nanoTime();
        long gracefulDeadline = startedAt + TimeUnit.MILLISECONDS.toNanos(500);
        long finalDeadline = startedAt + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = false;

        while (System.nanoTime() < finalDeadline) {
            List<ProcessHandle> alive = handles.stream().filter(ProcessHandle::isAlive).toList();
            if (alive.isEmpty()) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
            for (ProcessHandle handle : alive) {
                handle.descendants().forEach(handles::add);
            }
            boolean force = System.nanoTime() >= gracefulDeadline;
            handles.stream().filter(ProcessHandle::isAlive).forEach(handle -> {
                if (force) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            });
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return handles.stream().noneMatch(ProcessHandle::isAlive);
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
            ProgressListener progressListener,
            AtomicReference<Throwable> inputFailure
    ) throws InterruptedException, IOException {
        long timeoutMillis = timeout.toMillis();
        long elapsedMillis = 0L;
        long startedAt = System.nanoTime();
        while (elapsedMillis < timeoutMillis) {
            throwInputFailure(inputFailure.get());
            long waitMillis = Math.min(HEARTBEAT_MILLIS, timeoutMillis - elapsedMillis);
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return new WaitResult(true, Duration.ofNanos(System.nanoTime() - startedAt));
            }
            throwInputFailure(inputFailure.get());
            elapsedMillis += waitMillis;
            progressListener.onHeartbeat(Duration.ofMillis(elapsedMillis));
        }
        return new WaitResult(false, Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static void readOutput(
            Process process,
            StringBuilder output,
            boolean verbose,
            PrintWriter verboseWriter,
            OutputListener outputListener
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                if (verbose) {
                    verboseWriter.println(line);
                }
                outputListener.onLine(line);
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

    public interface OutputListener {

        default void onLine(String line) {
        }

        static OutputListener noOp() {
            return NoOpOutputListener.INSTANCE;
        }
    }

    private enum NoOpProgressListener implements ProgressListener {
        INSTANCE
    }

    private enum NoOpOutputListener implements OutputListener {
        INSTANCE
    }

    private record WaitResult(
            boolean finished,
            Duration elapsed
    ) {
    }
}
