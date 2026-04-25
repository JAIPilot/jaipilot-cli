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
import java.util.List;

public final class JunitLlmSessionRunner {

    private static final int MAX_INTERACTIONS = 100;
    private static final int MAX_FETCH_ATTEMPTS = 120;
    private static final long FETCH_DELAY_MILLIS = 1_000L;
    private static final Duration BASH_COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());

    private final JunitLlmBackendClient backendClient;
    private final ProjectFileService fileService;
    private final ConsoleLogger consoleLogger;
    private final ProcessExecutor processExecutor;

    public JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            ConsoleLogger consoleLogger
    ) {
        this(
                backendClient,
                fileService,
                consoleLogger,
                new ProcessExecutor()
        );
    }

    JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            ConsoleLogger consoleLogger,
            ProcessExecutor processExecutor
    ) {
        this.backendClient = backendClient;
        this.fileService = fileService;
        this.consoleLogger = consoleLogger;
        this.processExecutor = processExecutor == null ? new ProcessExecutor() : processExecutor;
    }

    public JunitLlmSessionResult run(JunitLlmSessionRequest sessionRequest) throws Exception {
        String cutCode = fileService.readFile(sessionRequest.cutPath());
        String cutName = fileService.stripJavaExtension(sessionRequest.cutPath().getFileName().toString());
        String mockitoVersion = null;

        String currentSessionId = blankToNull(sessionRequest.sessionId());
        String currentTestFilePath = blankToNull(sessionRequest.testFilePath());
        String currentTestCode = normalizeNullableText(sessionRequest.newTestClassCode());
        String clientLogs = blankToNull(sessionRequest.clientLogs());

        for (int interaction = 1; interaction <= MAX_INTERACTIONS; interaction++) {
            consoleLogger.announceStatus();
            InvokeJunitLlmRequest invokeRequest = new InvokeJunitLlmRequest(
                    currentSessionId,
                    cutName,
                    currentTestFilePath,
                    mockitoVersion,
                    cutCode,
                    normalizeText(sessionRequest.initialTestClassCode()),
                    normalizeText(currentTestCode),
                    clientLogs
            );
            InvokeJunitLlmResponse invokeResponse = backendClient.invoke(invokeRequest);
            currentSessionId = firstNonBlank(invokeResponse.sessionId(), currentSessionId);

            FetchJobResponse fetchJobResponse = pollJob(invokeResponse.jobId());
            currentSessionId = mergeSessionId(currentSessionId, fetchJobResponse);

            FetchJobResponse.FetchJobOutput output = requireOutput(fetchJobResponse);
            String nextTestFilePath = blankToNull(output.finalTestFilePath());
            if (nextTestFilePath != null) {
                currentTestFilePath = nextTestFilePath;
            }

            boolean hasFinalTestFile = output.finalTestFile() != null && !output.finalTestFile().isBlank();
            if (hasFinalTestFile) {
                currentTestCode = output.finalTestFile();
            }

            List<String> pendingBashCommands = normalizeList(output.pendingBashCommands());
            if (!pendingBashCommands.isEmpty()) {
                clientLogs = executePendingBashCommands(sessionRequest.projectRoot(), pendingBashCommands);
                continue;
            }

            clientLogs = null;

            if (currentTestCode == null || currentTestCode.isBlank()) {
                throw new IllegalStateException("Backend did not return a test file.");
            }
            if (currentTestFilePath == null || currentTestFilePath.isBlank()) {
                throw new IllegalStateException("Backend did not return a test file path.");
            }

            Path outputPath = resolveOutputPath(sessionRequest.projectRoot(), currentTestFilePath);
            String previousOutputContent = Files.isRegularFile(outputPath)
                    ? fileService.readFile(outputPath)
                    : "";
            fileService.writeFile(outputPath, currentTestCode);
            return new JunitLlmSessionResult(
                    currentSessionId,
                    outputPath,
                    previousOutputContent,
                    currentTestCode
            );
        }

        throw new IllegalStateException("Exceeded the maximum number of backend interactions.");
    }

    private FetchJobResponse pollJob(String jobId) throws Exception {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalStateException("Backend did not return a job.");
        }
        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            FetchJobResponse response = backendClient.fetchJob(jobId);
            String status = normalizeStatus(response.status());

            if (isDone(status)) {
                return response;
            }
            if (isFailure(status)) {
                throw new IllegalStateException(firstNonBlank(
                        response.errorMessage(),
                        "Backend job failed."
                ));
            }
            Thread.sleep(FETCH_DELAY_MILLIS);
        }
        throw new IllegalStateException("Timed out while waiting for backend response.");
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
            return String.format("%.1fs", totalMillis / 1_000.0);
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
