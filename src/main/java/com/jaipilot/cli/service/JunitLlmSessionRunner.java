package com.jaipilot.cli.service;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class JunitLlmSessionRunner {

    private static final int MAX_INTERACTIONS = 500;
    private static final int MAX_FETCH_ATTEMPTS = 450;
    private static final long FETCH_DELAY_MILLIS = 1_000L;
    private static final int MAX_POLL_RECOVERY_RETRIES = 10;
    private static final long INITIAL_POLL_RECOVERY_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_POLL_RECOVERY_BACKOFF_MILLIS = 30_000L;
    private static final long POLL_RECOVERY_JITTER_MILLIS = 250L;
    private static final Duration BASH_COMMAND_TIMEOUT = Duration.ofMinutes(10);
    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());

    @FunctionalInterface
    interface SleepStrategy {
        void sleep(long millis) throws InterruptedException;
    }

    private final JunitLlmBackendClient backendClient;
    private final ProjectFileService fileService;
    private final ConsoleLogger consoleLogger;
    private final ProcessExecutor processExecutor;
    private final SleepStrategy sleepStrategy;
    private final int maxFetchAttempts;
    private final long fetchDelayMillis;
    private final int maxPollRecoveryRetries;
    private final long initialPollRecoveryBackoffMillis;
    private final long maxPollRecoveryBackoffMillis;
    private final long pollRecoveryJitterMillis;

    public JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            ConsoleLogger consoleLogger
    ) {
        this(
                backendClient,
                fileService,
                consoleLogger,
                new ProcessExecutor(),
                Thread::sleep,
                MAX_FETCH_ATTEMPTS,
                FETCH_DELAY_MILLIS,
                MAX_POLL_RECOVERY_RETRIES,
                INITIAL_POLL_RECOVERY_BACKOFF_MILLIS,
                MAX_POLL_RECOVERY_BACKOFF_MILLIS,
                POLL_RECOVERY_JITTER_MILLIS
        );
    }

    JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            ConsoleLogger consoleLogger,
            ProcessExecutor processExecutor
    ) {
        this(
                backendClient,
                fileService,
                consoleLogger,
                processExecutor,
                Thread::sleep,
                MAX_FETCH_ATTEMPTS,
                FETCH_DELAY_MILLIS,
                MAX_POLL_RECOVERY_RETRIES,
                INITIAL_POLL_RECOVERY_BACKOFF_MILLIS,
                MAX_POLL_RECOVERY_BACKOFF_MILLIS,
                POLL_RECOVERY_JITTER_MILLIS
        );
    }

    JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            ConsoleLogger consoleLogger,
            ProcessExecutor processExecutor,
            SleepStrategy sleepStrategy,
            int maxFetchAttempts,
            long fetchDelayMillis,
            int maxPollRecoveryRetries,
            long initialPollRecoveryBackoffMillis,
            long maxPollRecoveryBackoffMillis,
            long pollRecoveryJitterMillis
    ) {
        this.backendClient = backendClient;
        this.fileService = fileService;
        this.consoleLogger = consoleLogger;
        this.processExecutor = processExecutor == null ? new ProcessExecutor() : processExecutor;
        this.sleepStrategy = sleepStrategy == null ? Thread::sleep : sleepStrategy;
        if (maxFetchAttempts <= 0) {
            throw new IllegalArgumentException("maxFetchAttempts must be greater than zero.");
        }
        if (fetchDelayMillis < 0) {
            throw new IllegalArgumentException("fetchDelayMillis must be non-negative.");
        }
        if (maxPollRecoveryRetries < 0) {
            throw new IllegalArgumentException("maxPollRecoveryRetries must be non-negative.");
        }
        if (initialPollRecoveryBackoffMillis <= 0) {
            throw new IllegalArgumentException("initialPollRecoveryBackoffMillis must be greater than zero.");
        }
        if (maxPollRecoveryBackoffMillis < initialPollRecoveryBackoffMillis) {
            throw new IllegalArgumentException(
                    "maxPollRecoveryBackoffMillis must be greater than or equal to initialPollRecoveryBackoffMillis."
            );
        }
        if (pollRecoveryJitterMillis < 0) {
            throw new IllegalArgumentException("pollRecoveryJitterMillis must be non-negative.");
        }
        this.maxFetchAttempts = maxFetchAttempts;
        this.fetchDelayMillis = fetchDelayMillis;
        this.maxPollRecoveryRetries = maxPollRecoveryRetries;
        this.initialPollRecoveryBackoffMillis = initialPollRecoveryBackoffMillis;
        this.maxPollRecoveryBackoffMillis = maxPollRecoveryBackoffMillis;
        this.pollRecoveryJitterMillis = pollRecoveryJitterMillis;
    }

    public JunitLlmSessionResult run(JunitLlmSessionRequest sessionRequest) throws Exception {
        String cutCode = fileService.readFile(sessionRequest.cutPath());
        String cutName = buildCutPathForBackend(sessionRequest.projectRoot(), sessionRequest.cutPath());
        String localRepositoryPath = buildLocalRepositoryPathForBackend(sessionRequest.projectRoot());

        String currentSessionId = blankToNull(sessionRequest.sessionId());
        String currentTestFilePath = blankToNull(sessionRequest.testFilePath());
        String currentTestCode = normalizeNullableText(sessionRequest.newTestClassCode());
        String clientLogs = blankToNull(sessionRequest.clientLogs());
        String lastCoverageSummaryText = null;
        Map<Path, String> previousOutputByPath = new HashMap<>();
        Path latestWrittenOutputPath = null;

        for (int interaction = 1; interaction <= MAX_INTERACTIONS; interaction++) {
            consoleLogger.announceStatus();
            InvocationAndPollResult invocationAndPollResult = invokeAndPollWithRetries(
                    currentSessionId,
                    cutName,
                    cutCode,
                    normalizeText(sessionRequest.initialTestClassCode()),
                    currentTestCode,
                    clientLogs,
                    localRepositoryPath
            );
            currentSessionId = invocationAndPollResult.sessionId();
            FetchJobResponse fetchJobResponse = invocationAndPollResult.fetchJobResponse();

            FetchJobResponse.FetchJobOutput output = requireOutput(fetchJobResponse);
            String coverageSummaryText = normalizeCoverageSummaryText(output.coverageSummary());
            if (coverageSummaryText != null && !coverageSummaryText.equals(lastCoverageSummaryText)) {
                // Temporarily disabled coverage summary printing.
                // consoleLogger.announceCoverageSummary(coverageSummaryText);
                lastCoverageSummaryText = coverageSummaryText;
            }
            String nextTestFilePath = blankToNull(output.finalTestFilePath());
            boolean hasFinalTestFile = output.finalTestFile() != null && !output.finalTestFile().isBlank();
            if (
                    nextTestFilePath != null &&
                            (hasFinalTestFile || currentTestFilePath == null || currentTestFilePath.isBlank())
            ) {
                currentTestFilePath = nextTestFilePath;
            }

            Path outputPathForInteraction = null;
            if (hasFinalTestFile) {
                currentTestCode = output.finalTestFile();
                if (
                        currentTestFilePath != null && !currentTestFilePath.isBlank() &&
                                currentTestCode != null && !currentTestCode.isBlank()
                ) {
                    outputPathForInteraction = writeCurrentTestFile(
                            sessionRequest.projectRoot(),
                            currentTestFilePath,
                            currentTestCode,
                            previousOutputByPath
                    );
                    latestWrittenOutputPath = outputPathForInteraction;
                }
            }

            List<String> pendingBashCommands = normalizeList(output.pendingBashCommands());
            if (!pendingBashCommands.isEmpty()) {
                clientLogs = executePendingBashCommands(sessionRequest.projectRoot(), pendingBashCommands);
                continue;
            }

            clientLogs = null;

            if (currentTestCode == null || currentTestCode.isBlank()) {
                String statusMessage = blankToNull(output.statusMessage());
                if (statusMessage != null) {
                    throw new IllegalStateException(statusMessage);
                }
                throw new IllegalStateException("Backend did not return a test file.");
            }
            if (currentTestFilePath == null || currentTestFilePath.isBlank()) {
                throw new IllegalStateException("Backend did not return a test file path.");
            }

            Path outputPath = outputPathForInteraction;
            if (outputPath == null) {
                outputPath = latestWrittenOutputPath;
            }
            if (outputPath == null) {
                outputPath = writeCurrentTestFile(
                        sessionRequest.projectRoot(),
                        currentTestFilePath,
                        currentTestCode,
                        previousOutputByPath
                );
                latestWrittenOutputPath = outputPath;
            }
            return new JunitLlmSessionResult(
                    currentSessionId,
                    outputPath,
                    previousOutputByPath.getOrDefault(outputPath, ""),
                    currentTestCode
            );
        }

        throw new IllegalStateException("Exceeded the maximum number of backend interactions.");
    }

    private InvocationAndPollResult invokeAndPollWithRetries(
            String currentSessionId,
            String cutName,
            String cutCode,
            String initialTestClassCode,
            String currentTestCode,
            String clientLogs,
            String localRepositoryPath
    ) throws Exception {
        String sessionId = currentSessionId;
        long backoffMillis = initialPollRecoveryBackoffMillis;
        for (int retry = 0; retry <= maxPollRecoveryRetries; retry++) {
            try {
                InvokeJunitLlmRequest invokeRequest = new InvokeJunitLlmRequest(
                        sessionId,
                        cutName,
                        null,
                        cutCode,
                        normalizeText(initialTestClassCode),
                        normalizeText(currentTestCode),
                        clientLogs,
                        localRepositoryPath
                );
                InvokeJunitLlmResponse invokeResponse = backendClient.invoke(invokeRequest);
                sessionId = firstNonBlank(invokeResponse.sessionId(), sessionId);

                FetchJobResponse fetchJobResponse = pollJob(invokeResponse.jobId());
                sessionId = mergeSessionId(sessionId, fetchJobResponse);
                return new InvocationAndPollResult(sessionId, fetchJobResponse);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            } catch (Exception exception) {
                if (!isRetriableInvokeOrPollFailure(exception) || retry >= maxPollRecoveryRetries) {
                    throw exception;
                }
                long delayMillis = retryDelayWithJitter(backoffMillis);
                consoleLogger.info(
                        "Backend request failed. Retrying (" + (retry + 1) + "/" + maxPollRecoveryRetries
                                + ") in " + delayMillis + "ms: " + describeException(exception)
                );
                sleep(delayMillis);
                backoffMillis = Math.min(backoffMillis * 2L, maxPollRecoveryBackoffMillis);
            }
        }
        throw new IllegalStateException("Backend request failed after retries.");
    }

    private FetchJobResponse pollJob(String jobId) throws Exception {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalStateException("Backend did not return a job.");
        }
        String lastStatus = "";
        for (int attempt = 1; attempt <= maxFetchAttempts; attempt++) {
            FetchJobResponse response = backendClient.fetchJob(jobId);
            String status = normalizeStatus(response.status());
            lastStatus = status;

            if (isDone(status)) {
                return response;
            }
            if (isFailure(status)) {
                throw new PollJobStatusException(status, firstNonBlank(
                        response.errorMessage(),
                        "Backend job failed."
                ));
            }
            sleep(fetchDelayMillis);
        }
        long timeoutSeconds = (maxFetchAttempts * fetchDelayMillis) / 1_000L;
        String normalizedStatus = lastStatus == null || lastStatus.isBlank() ? "unknown" : lastStatus;
        throw new PollJobTimeoutException(
                "Timed out while waiting for backend response for job `" + jobId + "` after "
                        + timeoutSeconds + "s (last status: " + normalizedStatus + ")."
        );
    }

    private void sleep(long delayMillis) throws InterruptedException {
        if (delayMillis <= 0) {
            return;
        }
        sleepStrategy.sleep(delayMillis);
    }

    private boolean isRetriableInvokeOrPollFailure(Exception exception) {
        if (exception instanceof PollJobTimeoutException) {
            return true;
        }
        if (exception instanceof PollJobStatusException pollJobStatusException) {
            return "error".equals(pollJobStatusException.normalizedStatus());
        }
        return hasCause(exception, java.io.IOException.class);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long retryDelayWithJitter(long backoffMillis) {
        long cappedBackoffMillis = Math.max(1L, Math.min(backoffMillis, maxPollRecoveryBackoffMillis));
        long remainingHeadroom = Math.max(0L, maxPollRecoveryBackoffMillis - cappedBackoffMillis);
        long jitterBound = Math.min(pollRecoveryJitterMillis, remainingHeadroom);
        if (jitterBound <= 0L) {
            return cappedBackoffMillis;
        }
        long jitterMillis = ThreadLocalRandom.current().nextLong(jitterBound + 1L);
        return cappedBackoffMillis + jitterMillis;
    }

    private String describeException(Exception exception) {
        String message = blankToNull(exception.getMessage());
        if (message == null) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private FetchJobResponse.FetchJobOutput requireOutput(FetchJobResponse response) {
        if (response.output() == null) {
            throw new IllegalStateException(firstNonBlank(
                    response.errorMessage(),
                    "Backend response was empty."
            ));
        }
        return response.output();
    }

    private Path resolveOutputPath(Path projectRoot, String backendOutputPath) {
        String normalizedBackendPath = backendOutputPath == null
                ? ""
                : backendOutputPath.trim().replace('\\', '/');
        if (normalizedBackendPath.isBlank()) {
            throw new IllegalStateException("Backend returned an empty test file path.");
        }

        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path relativePath = Path.of(normalizedBackendPath).normalize();
        if (relativePath.isAbsolute()) {
            throw new IllegalStateException("Backend returned an absolute test file path: " + backendOutputPath);
        }

        Path resolvedPath = normalizedProjectRoot.resolve(relativePath).normalize();
        if (!resolvedPath.startsWith(normalizedProjectRoot)) {
            throw new IllegalStateException("Backend test file path escapes the project root: " + backendOutputPath);
        }
        return resolvedPath;
    }

    private Path writeCurrentTestFile(
            Path projectRoot,
            String backendOutputPath,
            String testCode,
            Map<Path, String> previousOutputByPath
    ) throws Exception {
        Path outputPath = resolveOutputPath(projectRoot, backendOutputPath);
        if (!previousOutputByPath.containsKey(outputPath)) {
            String previousContent = Files.isRegularFile(outputPath)
                    ? fileService.readFile(outputPath)
                    : "";
            previousOutputByPath.put(outputPath, previousContent);
        }
        fileService.writeFile(outputPath, testCode);
        return outputPath;
    }

    private String buildCutPathForBackend(Path projectRoot, Path cutPath) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedCutPath = cutPath.toAbsolutePath().normalize();
        if (normalizedCutPath.startsWith(normalizedProjectRoot)) {
            return normalizedProjectRoot
                    .relativize(normalizedCutPath)
                    .toString()
                    .replace('\\', '/');
        }
        return normalizedCutPath.getFileName().toString();
    }

    private String buildLocalRepositoryPathForBackend(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().toString();
    }

    private String mergeSessionId(String currentSessionId, FetchJobResponse response) {
        if (response.output() == null) {
            return currentSessionId;
        }
        return firstNonBlank(response.output().sessionId(), currentSessionId);
    }

    private boolean isDone(String status) {
        return "done".equals(status) || "completed".equals(status) || "success".equals(status);
    }

    private boolean isFailure(String status) {
        return "error".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value;
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private String normalizeCoverageSummaryText(FetchJobResponse.CoverageSummary coverageSummary) {
        if (coverageSummary == null) {
            return null;
        }

        List<String> structuredLines = buildStructuredCoverageSummaryLines(coverageSummary);
        if (!structuredLines.isEmpty()) {
            return String.join("\n", structuredLines);
        }

        return sanitizeCoverageSummaryText(coverageSummary.text());
    }

    private List<String> buildStructuredCoverageSummaryLines(FetchJobResponse.CoverageSummary coverageSummary) {
        List<String> lines = new ArrayList<>();
        appendCoverageSnapshotLine(lines, "Before", coverageSummary.before());
        appendCoverageSnapshotLine(lines, "After", coverageSummary.after());

        if (coverageSummary.deltaPercentagePoints() != null) {
            lines.add("Delta: " + formatSignedDelta(coverageSummary.deltaPercentagePoints()));
        }

        return lines;
    }

    private void appendCoverageSnapshotLine(
            List<String> lines,
            String label,
            FetchJobResponse.CoverageSnapshot coverageSnapshot
    ) {
        if (coverageSnapshot == null || coverageSnapshot.primaryPercent() == null) {
            return;
        }
        lines.add(label + ": " + formatPercent(coverageSnapshot.primaryPercent()));
    }

    private String sanitizeCoverageSummaryText(String coverageSummaryText) {
        String normalizedText = blankToNull(coverageSummaryText);
        if (normalizedText == null) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        for (String line : normalizedText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String trimmedLine = line == null ? "" : line.trim();
            if (trimmedLine.isBlank() || containsHtml(trimmedLine)) {
                continue;
            }

            String normalizedLower = trimmedLine.toLowerCase(Locale.ROOT);
            if (
                    (normalizedLower.startsWith("before:") || normalizedLower.startsWith("after:")) &&
                            trimmedLine.contains("%")
            ) {
                lines.add(trimmedLine);
            } else if (normalizedLower.startsWith("delta:")) {
                lines.add(trimmedLine);
            }
        }

        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private boolean containsHtml(String value) {
        return value.contains("<") && value.contains(">");
    }

    private String formatPercent(Double percent) {
        return String.format(Locale.ROOT, "%.2f%%", percent);
    }

    private String formatSignedDelta(Double deltaPercentagePoints) {
        return String.format(Locale.ROOT, "%+.2f pp", deltaPercentagePoints);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private String executePendingBashCommands(Path projectRoot, List<String> pendingBashCommands) {
        Path workingDirectory = projectRoot.toAbsolutePath().normalize();
        List<String> normalizedCommands = pendingBashCommands.stream()
                .map(command -> command == null ? "" : command.trim())
                .filter(command -> !command.isBlank())
                .toList();
        if (normalizedCommands.isEmpty()) {
            return null;
        }

        StringBuilder logs = new StringBuilder();
        for (String command : normalizedCommands) {
            consoleLogger.info("Running backend command: " + command);
            logs.append("$ ").append(command).append(System.lineSeparator());

            try {
                ProcessExecutor.ExecutionResult result = processExecutor.execute(
                        shellCommand(command),
                        workingDirectory,
                        BASH_COMMAND_TIMEOUT,
                        false,
                        QUIET_WRITER
                );
                appendCommandOutput(logs, result.output());
                logs.append("[exitCode=")
                        .append(result.exitCode())
                        .append(", timedOut=")
                        .append(result.timedOut())
                        .append("]")
                        .append(System.lineSeparator());
            } catch (Exception exception) {
                logs.append("Command execution failed: ")
                        .append(exception.getMessage())
                        .append(System.lineSeparator());
            }

            logs.append(System.lineSeparator());
        }
        return logs.toString().trim();
    }

    private List<String> shellCommand(String command) {
        String osName = System.getProperty("os.name", "");
        boolean windows = osName != null && osName.toLowerCase().contains("win");
        if (windows) {
            return List.of("cmd", "/c", command);
        }
        return List.of("sh", "-lc", command);
    }

    private record InvocationAndPollResult(
            String sessionId,
            FetchJobResponse fetchJobResponse
    ) {
    }

    private static final class PollJobStatusException extends IllegalStateException {

        private final String normalizedStatus;

        private PollJobStatusException(String normalizedStatus, String message) {
            super(message);
            this.normalizedStatus = normalizedStatus == null ? "" : normalizedStatus;
        }

        private String normalizedStatus() {
            return normalizedStatus;
        }
    }

    private static final class PollJobTimeoutException extends IllegalStateException {

        private PollJobTimeoutException(String message) {
            super(message);
        }
    }

    private void appendCommandOutput(StringBuilder logs, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        logs.append(output);
        if (!output.endsWith("\n") && !output.endsWith("\r")) {
            logs.append(System.lineSeparator());
        }
    }

    public static final class ConsoleLogger {

        private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
        private static final String ANSI_GREEN = "\u001B[32m";
        private static final String ANSI_RED = "\u001B[31m";
        private static final String ANSI_RESET = "\u001B[0m";

        private final PrintWriter writer;

        public ConsoleLogger(PrintWriter writer) {
            this.writer = writer;
        }

        public void announceStatus() {
            info("Generating...");
        }

        public void announceTestFileDiff(String previousContent, String currentContent) {
            info("Source diff:");
            List<DiffLine> diffLines = buildDiffLines(previousContent, currentContent);
            if (diffLines.isEmpty()) {
                info("No file changes.");
                return;
            }
            for (DiffLine diffLine : diffLines) {
                if (diffLine.type() == DiffType.ADD) {
                    raw(colorize(ANSI_GREEN, "+ " + diffLine.value()));
                } else if (diffLine.type() == DiffType.REMOVE) {
                    raw(colorize(ANSI_RED, "- " + diffLine.value()));
                }
            }
        }

        public void announceTestFile(Path outputPath) {
            info("Test file: " + outputPath);
        }

        public void announceTotalTime(Duration duration) {
            info("Total time: " + formatDuration(duration));
        }

        public void announceCoverageSummary(String coverageSummaryText) {
            if (coverageSummaryText == null || coverageSummaryText.isBlank()) {
                return;
            }

            info("Coverage summary:");
            String normalizedText = coverageSummaryText
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            for (String line : normalizedText.split("\n", -1)) {
                if (!line.isBlank()) {
                    info("  " + line);
                }
            }
        }

        public void error(String message) {
            info("ERROR: " + message);
        }

        public void info(String message) {
            raw(message);
        }

        private void raw(String message) {
            writer.println("[" + LocalTime.now().format(TIMESTAMP_FORMAT) + "] " + message);
            writer.flush();
        }

        private String formatDuration(Duration duration) {
            long totalMillis = duration.toMillis();
            if (totalMillis < 1_000) {
                return totalMillis + "ms";
            }
            if (totalMillis < 60_000) {
                return String.format("%.1fs", totalMillis / 1_000.0);
            }

            long totalSeconds = Math.round(totalMillis / 1_000.0);
            long days = totalSeconds / 86_400;
            long remainder = totalSeconds % 86_400;
            long hours = remainder / 3_600;
            remainder %= 3_600;
            long minutes = remainder / 60;
            long seconds = remainder % 60;

            List<String> parts = new ArrayList<>();
            if (days > 0) {
                parts.add(days + "d");
            }
            if (hours > 0 || !parts.isEmpty()) {
                parts.add(hours + "h");
            }
            parts.add(minutes + "m");
            parts.add(seconds + "s");
            return String.join(" ", parts);
        }

        private String colorize(String color, String message) {
            return color + message + ANSI_RESET;
        }

        private List<DiffLine> buildDiffLines(String previousContent, String currentContent) {
            List<String> previousLines = toLines(previousContent);
            List<String> currentLines = toLines(currentContent);
            int[][] lcsLengths = buildLcsLengths(previousLines, currentLines);
            List<DiffLine> diffLines = new ArrayList<>();

            int previousIndex = 0;
            int currentIndex = 0;
            while (previousIndex < previousLines.size() && currentIndex < currentLines.size()) {
                String previousLine = previousLines.get(previousIndex);
                String currentLine = currentLines.get(currentIndex);
                if (previousLine.equals(currentLine)) {
                    previousIndex++;
                    currentIndex++;
                    continue;
                }
                if (lcsLengths[previousIndex + 1][currentIndex] >= lcsLengths[previousIndex][currentIndex + 1]) {
                    diffLines.add(new DiffLine(DiffType.REMOVE, previousLine));
                    previousIndex++;
                } else {
                    diffLines.add(new DiffLine(DiffType.ADD, currentLine));
                    currentIndex++;
                }
            }

            while (previousIndex < previousLines.size()) {
                diffLines.add(new DiffLine(DiffType.REMOVE, previousLines.get(previousIndex++)));
            }
            while (currentIndex < currentLines.size()) {
                diffLines.add(new DiffLine(DiffType.ADD, currentLines.get(currentIndex++)));
            }
            return diffLines;
        }

        private int[][] buildLcsLengths(List<String> previousLines, List<String> currentLines) {
            int[][] lcsLengths = new int[previousLines.size() + 1][currentLines.size() + 1];
            for (int previousIndex = previousLines.size() - 1; previousIndex >= 0; previousIndex--) {
                for (int currentIndex = currentLines.size() - 1; currentIndex >= 0; currentIndex--) {
                    if (previousLines.get(previousIndex).equals(currentLines.get(currentIndex))) {
                        lcsLengths[previousIndex][currentIndex] = lcsLengths[previousIndex + 1][currentIndex + 1] + 1;
                    } else {
                        lcsLengths[previousIndex][currentIndex] = Math.max(
                                lcsLengths[previousIndex + 1][currentIndex],
                                lcsLengths[previousIndex][currentIndex + 1]
                        );
                    }
                }
            }
            return lcsLengths;
        }

        private List<String> toLines(String content) {
            if (content == null || content.isEmpty()) {
                return List.of();
            }
            String normalizedContent = content.replace("\r\n", "\n").replace('\r', '\n');
            List<String> lines = new ArrayList<>(List.of(normalizedContent.split("\n", -1)));
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return lines;
        }

        private enum DiffType {
            ADD,
            REMOVE
        }

        private record DiffLine(DiffType type, String value) {
        }
    }
}
