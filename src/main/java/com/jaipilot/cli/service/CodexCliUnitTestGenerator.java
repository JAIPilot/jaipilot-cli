package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CodexCliUnitTestGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration CODEX_TIMEOUT = Duration.ofMinutes(20);

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
                descriptor,
                model,
                promptTemplateService.buildInitialPrompt(descriptor),
                ui,
                showLogs,
                showProgress,
                logWriter
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

    private void ensureCodexAvailable(Path workingDirectory) {
        if (codexVersion(workingDirectory).isEmpty()) {
            throw new IllegalStateException("Codex CLI is not installed or not available on PATH.");
        }
    }

    private AgentUsage runCodex(
            JavaProjectService.JavaClassDescriptor descriptor,
            String model,
            String prompt,
            TerminalUi ui,
            boolean showLogs,
            boolean showProgress,
            PrintWriter logWriter
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
                descriptor.projectRoot(),
                CODEX_TIMEOUT,
                false,
                logWriter,
                prompt,
                progressListener(ui, "codex generating tests for " + descriptor.className(), showLogs, showProgress),
                logRenderer
        );
        if (result.timedOut()) {
            throw new IllegalStateException("Codex timed out while generating tests for " + descriptor.className() + ".");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "Codex failed while generating tests for " + descriptor.className() + ":\n" + tail(result.output())
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
