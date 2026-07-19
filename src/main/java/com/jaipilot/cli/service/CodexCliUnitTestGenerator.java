package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodexCliUnitTestGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration PREPARATION_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration CODEX_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration BUILD_VERIFICATION_TIMEOUT = Duration.ofMinutes(30);
    private static final String MAVEN_OPTS = "MAVEN_OPTS";
    private static final String MAVEN_TRACKING_PROPERTY = "aether.enhancedLocalRepository.trackingFilename";
    private static final String MAVEN_TRACKING_OPTION = "-D" + MAVEN_TRACKING_PROPERTY + "=ignore";
    private static final String GRADLE_USER_HOME = "GRADLE_USER_HOME";
    private static final Pattern TEST_COUNT_PATTERN = Pattern.compile("<testsuite\\b[^>]*\\btests=\"(\\d+)\"");

    private final ProjectFileService fileService;
    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final ProcessExecutor processExecutor;
    private final PromptTemplateService promptTemplateService;

    public CodexCliUnitTestGenerator(ProjectFileService fileService, JavaProjectService projectService) {
        this(
                fileService,
                projectService,
                new CoverageReportService(),
                new ProcessExecutor(),
                new PromptTemplateService(fileService)
        );
    }

    CodexCliUnitTestGenerator(
            ProjectFileService fileService,
            JavaProjectService projectService,
            CoverageReportService coverageReportService,
            ProcessExecutor processExecutor,
            PromptTemplateService promptTemplateService
    ) {
        this.fileService = fileService;
        this.projectService = projectService;
        this.coverageReportService = coverageReportService;
        this.processExecutor = processExecutor;
        this.promptTemplateService = promptTemplateService;
    }

    public Optional<String> codexVersion(Path workingDirectory) {
        try {
            ProcessExecutor.ExecutionResult result = processExecutor.execute(
                    List.of("codex", "--version"),
                    workingDirectory,
                    Duration.ofSeconds(15),
                    false,
                    new PrintWriter(System.err, true)
            );
            if (result.exitCode() == 0) {
                return Optional.of(result.output().trim());
            }
        } catch (Exception ignored) {
            // Ignore and report absence.
        }
        return Optional.empty();
    }

    public GenerationResult generate(
            JavaProjectService.JavaClassDescriptor descriptor,
            String model,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter logWriter
    ) throws Exception {
        return generate(descriptor, model, ui, showLogs, true, logWriter);
    }

    public GenerationResult generate(
            JavaProjectService.JavaClassDescriptor descriptor,
            String model,
            TerminalUi ui,
            boolean showLogs,
            boolean showProgress,
            PrintWriter logWriter
    ) throws Exception {
        ensureCodexAvailable(descriptor.projectRoot());
        var beforeTestSnapshot = fileService.snapshotJavaTestFiles(descriptor.moduleRoot());
        CoverageReportService.CoverageSnapshot beforeCoverage = coverageReportService.readProjectSnapshot(descriptor.moduleRoot())
                .orElse(null);

        AgentUsage initialUsage = runCodex(
                descriptor.projectRoot(),
                model,
                promptTemplateService.buildInitialPrompt(descriptor),
                ui,
                showLogs,
                showProgress,
                logWriter,
                CODEX_TIMEOUT,
                "codex generating tests for " + descriptor.className(),
                "Codex failed while generating tests for " + descriptor.className() + ":"
        );
        List<JavaProjectService.JavaTestDescriptor> touchedTests = projectService.findTouchedTests(descriptor, beforeTestSnapshot);
        if (touchedTests.isEmpty()) {
            throw new IllegalStateException("Expected Codex to create or update a Java test file for " + descriptor.fullyQualifiedName() + ".");
        }
        JavaProjectService.JavaTestDescriptor generatedTest = touchedTests.get(0);
        boolean testExistedBefore = beforeTestSnapshot.containsKey(generatedTest.testPath());
        String note = buildNote(touchedTests, generatedTest);

        CoverageDelta coverageDelta = captureCoverageDelta(
                descriptor,
                beforeCoverage
        );
        return new GenerationResult(
                generatedTest.testPath(),
                touchedTests.stream().map(JavaProjectService.JavaTestDescriptor::testPath).toList(),
                initialUsage,
                coverageDelta,
                testExistedBefore,
                note
        );
    }

    public AgentUsage prepareProject(
            Path projectRoot,
            String model,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter logWriter
    ) throws Exception {
        return prepareProject(projectRoot, model, ui, showLogs, true, logWriter);
    }

    public AgentUsage prepareProject(
            Path projectRoot,
            String model,
            TerminalUi ui,
            boolean showLogs,
            boolean showProgress,
            PrintWriter logWriter
    ) throws Exception {
        ensureCodexAvailable(projectRoot);
        invalidateCoverageReport(projectRoot);
        String basePrompt = promptTemplateService.buildPreparationPrompt(projectRoot);
        AgentUsage usage = runReadinessWorkflow(
                projectRoot,
                model,
                ui,
                showLogs,
                showProgress,
                logWriter,
                basePrompt,
                "codex preparing project",
                "codex repairing project",
                "Codex failed while preparing the project:",
                "Codex failed while repairing the project:",
                "preparation"
        );
        runCleanBuildVerification(projectRoot, ui, showLogs, logWriter, List.of());
        return usage;
    }

    public AgentUsage validateBatch(
            Path projectRoot,
            String model,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter logWriter,
            List<Path> expectedTestPaths
    ) throws Exception {
        List<Path> normalizedExpectedTests = normalizeExpectedTestPaths(projectRoot, expectedTestPaths);
        Map<Path, ProjectFileService.FileFingerprint> testBaseline = fileService.snapshotJavaTestFiles(projectRoot);
        AgentUsage usage = AgentUsage.zero();
        Exception repairFailure = null;
        try {
            ensureCodexAvailable(projectRoot);
            usage = repairBatchInIsolatedWorkspace(
                    projectRoot,
                    model,
                    ui,
                    showLogs,
                    logWriter,
                    normalizedExpectedTests,
                    testBaseline
            );
        } catch (Exception exception) {
            repairFailure = exception;
            ui.warn("Codex repair was unavailable; continuing with direct clean verification: "
                    + firstLine(exception.getMessage()));
        }
        try {
            runCleanBuildVerification(projectRoot, ui, showLogs, logWriter, normalizedExpectedTests);
            return usage;
        } catch (Exception verificationFailure) {
            if (repairFailure != null) {
                verificationFailure.addSuppressed(repairFailure);
            }
            throw verificationFailure;
        }
    }

    private AgentUsage repairBatchInIsolatedWorkspace(
            Path projectRoot,
            String model,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter logWriter,
            List<Path> expectedTestPaths,
            Map<Path, ProjectFileService.FileFingerprint> testBaseline
    ) throws Exception {
        Path sandboxRoot = Files.createTempDirectory("jaipilot-validation-");
        try {
            fileService.copyProjectWorkspace(projectRoot, sandboxRoot);
            List<Path> sandboxExpectedTests = expectedTestPaths.stream()
                    .map(path -> rebaseIntoSandbox(projectRoot, sandboxRoot, path))
                    .toList();
            Map<Path, ProjectFileService.FileFingerprint> sourceBaseline = fileService.snapshotJavaSourceFiles(sandboxRoot);
            invalidateCoverageReport(sandboxRoot);
            AgentUsage usage = runReadinessWorkflow(
                    sandboxRoot,
                    model,
                    ui,
                    showLogs,
                    true,
                    logWriter,
                    promptTemplateService.buildBatchValidationPrompt(sandboxRoot, sandboxExpectedTests),
                    "codex validating merged tests",
                    "codex repairing merged tests",
                    "Codex failed while validating the merged tests:",
                    "Codex failed while repairing the merged tests:",
                    "batch validation"
            );

            Map<Path, ProjectFileService.FileFingerprint> sourceAfterRepair = fileService.snapshotJavaSourceFiles(sandboxRoot);
            List<Path> unexpectedChanges = findUnexpectedJavaChanges(
                    sourceBaseline,
                    sourceAfterRepair,
                    new LinkedHashSet<>(sandboxExpectedTests)
            );
            if (!unexpectedChanges.isEmpty()) {
                throw new IllegalStateException("Codex validation edited files outside the generated-test allowlist: "
                        + unexpectedChanges);
            }

            Map<Path, String> repairedTests = new LinkedHashMap<>();
            for (int index = 0; index < expectedTestPaths.size(); index++) {
                Path sandboxTest = sandboxExpectedTests.get(index);
                if (!Files.isRegularFile(sandboxTest)) {
                    throw new IllegalStateException("Codex validation removed an expected generated test: " + sandboxTest);
                }
                repairedTests.put(expectedTestPaths.get(index), fileService.readFile(sandboxTest));
            }
            fileService.writeFilesTransactionally(repairedTests, testBaseline);
            return usage;
        } finally {
            fileService.deleteRecursively(sandboxRoot);
        }
    }

    private List<Path> normalizeExpectedTestPaths(Path projectRoot, List<Path> expectedTestPaths) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        return expectedTestPaths.stream()
                .map(path -> path.isAbsolute() ? path : normalizedRoot.resolve(path))
                .map(path -> path.toAbsolutePath().normalize())
                .peek(path -> {
                    if (!path.startsWith(normalizedRoot)) {
                        throw new IllegalStateException("Generated test is outside the project root: " + path);
                    }
                })
                .distinct()
                .sorted()
                .toList();
    }

    private Path rebaseIntoSandbox(Path projectRoot, Path sandboxRoot, Path projectPath) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedPath = projectPath.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IllegalStateException("Generated test is outside the project root: " + projectPath);
        }
        return sandboxRoot.resolve(normalizedRoot.relativize(normalizedPath)).normalize();
    }

    static List<Path> findUnexpectedJavaChanges(
            Map<Path, ProjectFileService.FileFingerprint> before,
            Map<Path, ProjectFileService.FileFingerprint> after,
            Set<Path> allowedPaths
    ) {
        Set<Path> normalizedAllowedPaths = allowedPaths.stream()
                .map(Path::normalize)
                .collect(java.util.stream.Collectors.toSet());
        Set<Path> allPaths = new TreeSet<>();
        allPaths.addAll(before.keySet());
        allPaths.addAll(after.keySet());
        return allPaths.stream()
                .filter(path -> !normalizedAllowedPaths.contains(path.normalize()))
                .filter(path -> !Objects.equals(before.get(path), after.get(path)))
                .toList();
    }

    private AgentUsage runReadinessWorkflow(
            Path projectRoot,
            String model,
            TerminalUi ui,
            boolean showLogs,
            boolean showProgress,
            PrintWriter logWriter,
            String basePrompt,
            String initialProgressLabel,
            String retryProgressLabel,
            String initialFailurePrefix,
            String retryFailurePrefix,
            String workflowName
    ) throws Exception {
        String prompt = basePrompt;
        AgentUsage totalUsage = AgentUsage.zero();

        for (int attempt = 1; attempt <= 3; attempt++) {
            String progressLabel = attempt == 1 ? initialProgressLabel : retryProgressLabel;
            String failurePrefix = attempt == 1 ? initialFailurePrefix : retryFailurePrefix;
            try {
                totalUsage = totalUsage.plus(runCodex(
                        projectRoot,
                        model,
                        prompt,
                        ui,
                        showLogs,
                        showProgress,
                        logWriter,
                        PREPARATION_TIMEOUT,
                        progressLabel,
                        failurePrefix
                ));
            } catch (Exception failure) {
                if (attempt == 3 || isNonRetryableCodexFailure(failure)) {
                    throw failure;
                }
                prompt = buildPreparationRetryPrompt(basePrompt, List.of(failure.getMessage()));
                continue;
            }

            List<String> readinessIssues = findPreparationIssues(projectRoot);
            if (readinessIssues.isEmpty()) {
                return totalUsage;
            }
            if (attempt == 3) {
                throw new IllegalStateException(
                        "Codex completed " + workflowName + " but the repository is still not ready:"
                                + System.lineSeparator()
                                + String.join(System.lineSeparator(), readinessIssues)
                );
            }
            prompt = buildPreparationRetryPrompt(basePrompt, readinessIssues);
        }

        throw new IllegalStateException("Project " + workflowName + " exited without a final readiness result.");
    }

    boolean isNonRetryableCodexFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("usage limit")) {
                return true;
            }
        }
        return false;
    }

    void invalidateCoverageReport(Path projectRoot) {
        coverageReportService.findCoverageReports(projectRoot).forEach(reportPath -> {
            try {
                Files.deleteIfExists(reportPath);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to remove stale JaCoCo report " + reportPath, exception);
            }
        });
    }

    private void runCleanBuildVerification(
            Path projectRoot,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter logWriter,
            List<Path> expectedTestPaths
    ) throws Exception {
        invalidateCoverageReport(projectRoot);
        JavaProjectService.BuildTool buildTool = projectService.detectBuildTool(projectRoot);
        String executable = projectService.resolveBuildWrapper(projectRoot).orElseGet(() -> switch (buildTool) {
            case MAVEN -> "mvn";
            case GRADLE -> "gradle";
        });
        List<String> command = switch (buildTool) {
            case MAVEN -> List.of(executable, "-B", "clean", "verify");
            case GRADLE -> List.of(executable, "--no-daemon", "clean", "test", "jacocoTestReport");
        };
        if (showLogs) {
            logWriter.printf("%s %s%n", ui.badge(TerminalUi.Tone.PRIMARY, "verify"), formatCommand(command));
            logWriter.flush();
        }

        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                command,
                projectRoot,
                BUILD_VERIFICATION_TIMEOUT,
                showLogs,
                logWriter,
                null,
                showLogs
                        ? ProcessExecutor.ProgressListener.noOp()
                        : ui.spinner("running clean full test suite"),
                ProcessExecutor.OutputListener.noOp(),
                buildToolCacheEnvironment(projectRoot, System.getenv())
        );
        if (result.timedOut()) {
            throw new IllegalStateException("Clean full-suite verification timed out after "
                    + BUILD_VERIFICATION_TIMEOUT.toMinutes() + " minutes.");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Clean full-suite verification failed:" + System.lineSeparator()
                    + tailBuildOutput(result.output()));
        }
        if (coverageReportService.readProjectSnapshot(projectRoot).isEmpty()) {
            throw new IllegalStateException("Clean full-suite verification did not generate a readable JaCoCo XML report.");
        }
        List<String> missingReports = findMissingTestReports(projectRoot, expectedTestPaths);
        if (!missingReports.isEmpty()) {
            throw new IllegalStateException("Clean full-suite verification did not execute generated tests: "
                    + String.join(", ", missingReports));
        }
    }

    List<String> findMissingTestReports(Path projectRoot, List<Path> expectedTestPaths) {
        Map<Path, List<String>> reportNamesByModule = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Path testPath : expectedTestPaths.stream().distinct().sorted().toList()) {
            if (!Files.isRegularFile(testPath)) {
                missing.add(testPath.toString());
                continue;
            }
            JavaProjectService.JavaTestDescriptor descriptor = projectService.describeTestClass(testPath, projectRoot);
            String expectedReport = "TEST-" + descriptor.fullyQualifiedName() + ".xml";
            List<String> moduleReports = reportNamesByModule.computeIfAbsent(
                    descriptor.moduleRoot(),
                    this::findTestReportNames
            );
            if (!moduleReports.contains(expectedReport)) {
                missing.add(descriptor.fullyQualifiedName());
            }
        }
        return List.copyOf(missing);
    }

    private List<String> findTestReportNames(Path moduleRoot) {
        List<Path> reportRoots = List.of(
                moduleRoot.resolve("target/surefire-reports"),
                moduleRoot.resolve("target/failsafe-reports"),
                moduleRoot.resolve("build/test-results")
        );
        List<String> reportNames = new ArrayList<>();
        for (Path reportRoot : reportRoots) {
            if (!Files.isDirectory(reportRoot)) {
                continue;
            }
            try (var paths = Files.walk(reportRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.startsWith("TEST-") && fileName.endsWith(".xml");
                        })
                        .filter(this::containsExecutedTests)
                        .map(path -> path.getFileName().toString())
                        .forEach(reportNames::add);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to inspect clean test results under " + reportRoot, exception);
            }
        }
        return reportNames.stream().distinct().toList();
    }

    private boolean containsExecutedTests(Path reportPath) {
        try {
            Matcher matcher = TEST_COUNT_PATTERN.matcher(Files.readString(reportPath));
            return matcher.find() && Long.parseLong(matcher.group(1)) > 0L;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to inspect test count in " + reportPath, exception);
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

    private String firstLine(String message) {
        return message == null || message.isBlank()
                ? "unknown Codex failure"
                : message.lines().findFirst().orElse(message);
    }

    private void ensureCodexAvailable(Path workingDirectory) {
        if (codexVersion(workingDirectory).isEmpty()) {
            throw new IllegalStateException("Codex CLI is not installed or not available on PATH.");
        }
    }

    private AgentUsage runCodex(
            Path workingDirectory,
            String model,
            String prompt,
            TerminalUi ui,
            boolean showLogs,
            boolean showProgress,
            PrintWriter logWriter,
            Duration timeout,
            String progressLabel,
            String failurePrefix
    ) throws Exception {
        List<String> command = buildCodexCommand(model);
        command.add("exec");
        command.add("--json");
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");
        command.add("-");

        printLiveLogHeader(ui, logWriter, showLogs, "agent", command);
        CodexJsonLogRenderer logRenderer = showLogs ? new CodexJsonLogRenderer(ui, logWriter) : null;
        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                command,
                workingDirectory,
                timeout,
                false,
                logWriter,
                prompt,
                progressListener(ui, progressLabel, showLogs, showProgress),
                logRenderer,
                buildToolCacheEnvironment(workingDirectory, System.getenv())
        );
        if (result.timedOut()) {
            throw new IllegalStateException(failurePrefix + System.lineSeparator() + "Timed out.");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    failurePrefix + System.lineSeparator() + summarizeFailure(result.output(), ui)
            );
        }
        return parseUsage(result.output());
    }

    List<String> buildCodexCommand(String model) {
        List<String> command = new ArrayList<>();
        command.add("codex");
        command.add("-a");
        command.add("never");
        command.add("-c");
        command.add("hide_agent_reasoning=true");
        command.add("-c");
        command.add("features.multi_agent=false");
        command.add("-s");
        command.add("workspace-write");
        if (model != null && !model.isBlank()) {
            command.add("-m");
            command.add(model.trim());
        }
        return command;
    }

    private CoverageDelta captureCoverageDelta(
            JavaProjectService.JavaClassDescriptor descriptor,
            CoverageReportService.CoverageSnapshot beforeCoverage
    ) {
        CoverageReportService.CoverageSnapshot afterCoverage = coverageReportService.readProjectSnapshot(descriptor.moduleRoot())
                .orElse(null);
        if (afterCoverage == null) {
            return CoverageDelta.unavailable("Coverage snapshot unavailable after Codex run.");
        }
        CoverageReportService.ClassCoverage beforeClass = classCoverageOrZero(beforeCoverage, descriptor);
        CoverageReportService.ClassCoverage afterClass = classCoverageOrZero(afterCoverage, descriptor);
        return new CoverageDelta(beforeClass, afterClass, null);
    }

    private String buildNote(
            List<JavaProjectService.JavaTestDescriptor> touchedTests,
            JavaProjectService.JavaTestDescriptor generatedTest
    ) {
        List<String> notes = new ArrayList<>();
        if (touchedTests.size() > 1) {
            notes.add("Multiple test files changed; using " + generatedTest.testPath().getFileName() + " as the primary result.");
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private List<String> findPreparationIssues(Path projectRoot) {
        List<String> issues = new ArrayList<>();
        if (projectService.detectBuildToolIfPresent(projectRoot).isEmpty()) {
            issues.add("- No Maven or Gradle build file was detected under " + projectRoot + ".");
        }
        try {
            if (coverageReportService.readProjectSnapshot(projectRoot).isEmpty()) {
                issues.add("- No JaCoCo XML report was found after preparation.");
            }
        } catch (Exception exception) {
            issues.add("- JAIPilot could not read the JaCoCo XML report after preparation: " + exception.getMessage());
        }
        return issues;
    }

    private String buildPreparationRetryPrompt(String basePrompt, List<String> readinessIssues) {
        String issues = readinessIssues.isEmpty()
                ? "- Unknown preparation failure."
                : String.join(System.lineSeparator(), readinessIssues);
        return basePrompt
                + System.lineSeparator()
                + System.lineSeparator()
                + "The repository is still not ready for target-class test generation."
                + System.lineSeparator()
                + "Fix these issues now:"
                + System.lineSeparator()
                + issues
                + System.lineSeparator()
                + System.lineSeparator()
                + "Resume from the current workspace state. Rerun the necessary build, test, and JaCoCo coverage commands yourself. "
                + "Only stop once the repository is buildable, tests pass, and a readable JaCoCo XML report exists.";
    }

    private AgentUsage parseUsage(String output) {
        AgentUsage usage = AgentUsage.zero();
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = OBJECT_MAPPER.readTree(trimmed);
                if (!"turn.completed".equals(node.path("type").asText())) {
                    continue;
                }
                JsonNode usageNode = node.path("usage");
                usage = new AgentUsage(
                        usageNode.path("input_tokens").asLong(0L),
                        usageNode.path("cached_input_tokens").asLong(0L),
                        usageNode.path("output_tokens").asLong(0L),
                        usageNode.path("reasoning_output_tokens").asLong(0L)
                );
            } catch (Exception ignored) {
                // Ignore non-JSON or unexpected lines in mixed output.
            }
        }
        return usage;
    }

    private ProcessExecutor.ProgressListener progressListener(
            TerminalUi ui,
            String label,
            boolean showLogs,
            boolean showProgress
    ) {
        if (showLogs || !showProgress) {
            return ProcessExecutor.ProgressListener.noOp();
        }
        return ui.spinner(label);
    }

    private void printLiveLogHeader(
            TerminalUi ui,
            PrintWriter logWriter,
            boolean showLogs,
            String stage,
            List<String> command
    ) {
        if (!showLogs) {
            return;
        }
        logWriter.printf("%s %s%n", ui.badge(TerminalUi.Tone.PRIMARY, stage), formatCommand(command));
        logWriter.flush();
    }

    private String formatCommand(List<String> command) {
        return command.stream()
                .map(this::quoteIfNeeded)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    static Map<String, String> buildToolCacheEnvironment(Path workingDirectory, Map<String, String> currentEnvironment) {
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

    private static boolean containsMavenTrackingOverride(String value) {
        return value != null && value.contains(MAVEN_TRACKING_PROPERTY);
    }

    private static String appendJvmOption(String existingValue, String option) {
        if (existingValue == null || existingValue.isBlank()) {
            return option;
        }
        return existingValue.stripTrailing() + " " + option;
    }

    private String quoteIfNeeded(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
    String summarizeFailure(String output, TerminalUi ui) {
        CodexJsonLogRenderer renderer = new CodexJsonLogRenderer(
                ui,
                new PrintWriter(Writer.nullWriter())
        );
        List<String> rendered = (output == null ? List.<String>of() : output.lines().toList()).stream()
                .map(renderer::render)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<String> important = rendered.stream()
                .filter(value -> {
                    String lowerCase = value.toLowerCase(java.util.Locale.ROOT);
                    return lowerCase.contains("error")
                            || lowerCase.contains("failed")
                            || lowerCase.contains("warning");
                })
                .toList();
        List<String> selected = important.isEmpty() ? rendered : important;
        if (selected.isEmpty()) {
            return "Codex exited without a readable error. Re-run with --show-logs for details.";
        }
        int start = Math.max(0, selected.size() - 8);
        return String.join(System.lineSeparator(), selected.subList(start, selected.size()));
    }

    public record GenerationResult(
            Path outputPath,
            List<Path> touchedTestPaths,
            AgentUsage usage,
            CoverageDelta coverageDelta,
            boolean testExistedBefore,
            String note
    ) {
    }

    public record AgentUsage(
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long reasoningOutputTokens
    ) {
        public static AgentUsage zero() {
            return new AgentUsage(0L, 0L, 0L, 0L);
        }

        public AgentUsage plus(AgentUsage other) {
            return new AgentUsage(
                    inputTokens + other.inputTokens,
                    cachedInputTokens + other.cachedInputTokens,
                    outputTokens + other.outputTokens,
                    reasoningOutputTokens + other.reasoningOutputTokens
            );
        }

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    public record CoverageDelta(
            CoverageReportService.ClassCoverage beforeClassCoverage,
            CoverageReportService.ClassCoverage afterClassCoverage,
            String unavailableReason
    ) {
        static CoverageDelta unavailable(String reason) {
            return new CoverageDelta(null, null, reason);
        }

        public boolean available() {
            return afterClassCoverage != null;
        }

        public Double beforeLineCoverage() {
            return beforeClassCoverage == null ? null : beforeClassCoverage.lineCoverage();
        }

        public Double afterLineCoverage() {
            return afterClassCoverage == null ? null : afterClassCoverage.lineCoverage();
        }

        public Double beforeBranchCoverage() {
            return beforeClassCoverage == null ? null : beforeClassCoverage.branchCoverage();
        }

        public Double afterBranchCoverage() {
            return afterClassCoverage == null ? null : afterClassCoverage.branchCoverage();
        }
    }

    private CoverageReportService.ClassCoverage classCoverageOrZero(
            CoverageReportService.CoverageSnapshot snapshot,
            JavaProjectService.JavaClassDescriptor descriptor
    ) {
        if (snapshot == null) {
            return null;
        }
        return snapshot.classCoverageByName().getOrDefault(
                descriptor.fullyQualifiedName(),
                new CoverageReportService.ClassCoverage(descriptor.fullyQualifiedName(), 0.0d, 0.0d)
        );
    }
}
