package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CodexCliUnitTestGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration PREPARATION_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration CODEX_TIMEOUT = Duration.ofMinutes(20);
    private static final String MAVEN_OPTS = "MAVEN_OPTS";
    private static final String MAVEN_TRACKING_PROPERTY = "aether.enhancedLocalRepository.trackingFilename";
    private static final String MAVEN_TRACKING_OPTION = "-D" + MAVEN_TRACKING_PROPERTY + "=ignore";
    private static final String GRADLE_USER_HOME = "GRADLE_USER_HOME";

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
        String basePrompt = promptTemplateService.buildPreparationPrompt(projectRoot);
        String prompt = basePrompt;
        AgentUsage totalUsage = AgentUsage.zero();

        for (int attempt = 1; attempt <= 3; attempt++) {
            String progressLabel = attempt == 1 ? "codex preparing project" : "codex repairing project";
            String failurePrefix = attempt == 1
                    ? "Codex failed while preparing the project:"
                    : "Codex failed while repairing the project:";
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
                if (attempt == 3) {
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
                        "Codex completed preparation but the repository is still not ready:"
                                + System.lineSeparator()
                                + String.join(System.lineSeparator(), readinessIssues)
                );
            }
            prompt = buildPreparationRetryPrompt(basePrompt, readinessIssues);
        }

        throw new IllegalStateException("Project preparation exited without a final readiness result.");
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
        List<String> command = new ArrayList<>();
        command.add("codex");
        command.add("-a");
        command.add("never");
        command.add("-c");
        command.add("hide_agent_reasoning=true");
        command.add("-s");
        command.add("workspace-write");
        if (model != null && !model.isBlank()) {
            command.add("-m");
            command.add(model.trim());
        }
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
                    failurePrefix + System.lineSeparator() + tail(result.output())
            );
        }
        return parseUsage(result.output());
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
            environment.put(GRADLE_USER_HOME, projectRoot.resolve("build/jaipilot-gradle").normalize().toString());
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
    private String tail(String output) {
        List<String> lines = output == null ? List.of() : output.lines().toList();
        int start = Math.max(0, lines.size() - 60);
        return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
    }

    public record GenerationResult(
            Path outputPath,
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
            return inputTokens + cachedInputTokens + outputTokens + reasoningOutputTokens;
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
