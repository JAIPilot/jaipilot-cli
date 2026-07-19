package com.jaipilot.cli.service;

import com.jaipilot.cli.ui.TerminalUi;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CoverageRefreshService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final String MAVEN_OPTS = "MAVEN_OPTS";
    private static final String MAVEN_TRACKING_PROPERTY = "aether.enhancedLocalRepository.trackingFilename";
    private static final String MAVEN_TRACKING_OPTION = "-D" + MAVEN_TRACKING_PROPERTY + "=ignore";
    private static final String GRADLE_USER_HOME = "GRADLE_USER_HOME";

    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final ProcessExecutor processExecutor;
    private final Duration timeout;

    public CoverageRefreshService(
            JavaProjectService projectService,
            CoverageReportService coverageReportService
    ) {
        this(projectService, coverageReportService, new ProcessExecutor(), DEFAULT_TIMEOUT);
    }

    CoverageRefreshService(
            JavaProjectService projectService,
            CoverageReportService coverageReportService,
            ProcessExecutor processExecutor,
            Duration timeout
    ) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.coverageReportService = Objects.requireNonNull(coverageReportService, "coverageReportService");
        this.processExecutor = Objects.requireNonNull(processExecutor, "processExecutor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public CoverageReportService.CoverageSnapshot refresh(
            Path projectRoot,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter logWriter
    ) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(logWriter, "logWriter");

        try (ProjectRefreshLock ignored = acquireProjectLock(projectRoot)) {
            return refreshWithProjectLock(projectRoot, ui, showLogs, logWriter);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to manage the project coverage-refresh lock.", exception);
        }
    }

    public Optional<CoverageReportService.CoverageSnapshot> readCachedSnapshot(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        try (ProjectRefreshLock ignored = acquireProjectLock(projectRoot)) {
            return coverageReportService.readProjectSnapshot(projectRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to manage the project coverage-refresh lock.", exception);
        }
    }

    CoverageReportService.CoverageSnapshot refreshWithProjectLock(
            Path projectRoot,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter logWriter
    ) {
        try {
            invalidateCoverageReports(projectRoot);
            JavaProjectService.BuildTool buildTool = projectService.detectBuildTool(projectRoot);
            String executable = projectService.resolveBuildExecutable(projectRoot);
            List<String> command = switch (buildTool) {
                case MAVEN -> List.of(executable, "-B", "clean", "verify");
                case GRADLE -> projectService.usesGradleCoverageAggregation(projectRoot)
                        ? List.of(executable, "--no-daemon", "clean", "testCodeCoverageReport")
                        : List.of(executable, "--no-daemon", "clean", "test", "jacocoTestReport");
            };
            if (showLogs) {
                logWriter.printf("%s %s%n", ui.badge(TerminalUi.Tone.PRIMARY, "coverage"), formatCommand(command));
                logWriter.flush();
            }

            ProcessExecutor.ExecutionResult result = processExecutor.execute(
                    command,
                    projectRoot,
                    timeout,
                    showLogs,
                    logWriter,
                    null,
                    showLogs
                            ? ProcessExecutor.ProgressListener.noOp()
                            : ui.spinner("refreshing clean full-suite coverage"),
                    ProcessExecutor.OutputListener.noOp(),
                    buildToolCacheEnvironment(projectRoot, System.getenv())
            );
            if (result.timedOut()) {
                throw new IllegalStateException("Clean full-suite coverage refresh timed out after "
                        + timeout.toMinutes() + " minutes.");
            }
            if (result.exitCode() != 0) {
                String failure = showLogs
                        ? "Clean full-suite coverage refresh failed with exit code " + result.exitCode()
                                + "; build output was streamed above."
                        : "Clean full-suite coverage refresh failed:"
                                + System.lineSeparator() + tailBuildOutput(result.output());
                throw new IllegalStateException(failure);
            }
            return coverageReportService.readProjectSnapshot(projectRoot)
                    .orElseThrow(() -> new IllegalStateException(
                            "The clean full suite passed but did not generate a readable JaCoCo XML report."
                    ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            discardFailedRefresh(projectRoot, exception);
            throw new IllegalStateException("Clean full-suite coverage refresh was interrupted.", exception);
        } catch (IOException | RuntimeException exception) {
            discardFailedRefresh(projectRoot, exception);
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to run the clean full-suite coverage refresh.", exception);
        }
    }

    ProjectRefreshLock acquireProjectLock(Path projectRoot) throws IOException {
        Path canonicalRoot = projectRoot.toRealPath();
        String lockName = UUID.nameUUIDFromBytes(
                canonicalRoot.toString().getBytes(StandardCharsets.UTF_8)
        ) + ".lock";
        Path lockDirectory = Path.of(System.getProperty("java.io.tmpdir"), "jaipilot", "coverage-locks");
        Files.createDirectories(lockDirectory);
        FileChannel channel = FileChannel.open(
                lockDirectory.resolve(lockName),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                lock = null;
            }
            if (lock == null) {
                throw new RefreshInProgressException(
                        "Another JAIPilot coverage refresh is already running for this project. Wait for it to finish and retry."
                );
            }
            return new ProjectRefreshLock(channel, lock);
        } catch (IOException | RuntimeException exception) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    void invalidateCoverageReports(Path projectRoot) {
        coverageReportService.findCoverageReports(projectRoot).forEach(reportPath -> {
            try {
                Files.deleteIfExists(reportPath);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to remove stale JaCoCo report " + reportPath, exception);
            }
        });
    }

    static Map<String, String> buildToolCacheEnvironment(
            Path workingDirectory,
            Map<String, String> currentEnvironment
    ) {
        Path projectRoot = workingDirectory.toAbsolutePath().normalize();
        Map<String, String> environment = new LinkedHashMap<>();
        String mavenOpts = currentEnvironment.getOrDefault(MAVEN_OPTS, "");
        if (!containsMavenTrackingOverride(mavenOpts)) {
            environment.put(MAVEN_OPTS, appendJvmOption(mavenOpts, MAVEN_TRACKING_OPTION));
        }
        if (!currentEnvironment.containsKey(GRADLE_USER_HOME)) {
            environment.put(GRADLE_USER_HOME, projectRoot.resolve(".gradle/jaipilot").normalize().toString());
        }
        return environment;
    }

    private void discardFailedRefresh(Path projectRoot, Throwable failure) {
        try {
            invalidateCoverageReports(projectRoot);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private String tailBuildOutput(String output) {
        List<String> lines = output == null ? List.of() : output.lines()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .toList();
        int start = Math.max(0, lines.size() - 40);
        return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
    }

    private String formatCommand(List<String> command) {
        return command.stream()
                .map(this::quoteIfNeeded)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String quoteIfNeeded(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static boolean containsMavenTrackingOverride(String value) {
        return value != null && value.contains(MAVEN_TRACKING_PROPERTY);
    }

    private static String appendJvmOption(String existingValue, String option) {
        if (existingValue == null || existingValue.isBlank()) {
            return option;
        }
        return existingValue.stripTrailing() + " " + option;
    }

    static final class ProjectRefreshLock implements AutoCloseable {

        private final FileChannel channel;
        private final FileLock lock;

        private ProjectRefreshLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    public static final class RefreshInProgressException extends IllegalStateException {

        RefreshInProgressException(String message) {
            super(message);
        }
    }
}
