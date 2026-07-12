package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CodexCliUnitTestGenerator;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProcessExecutor;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "generate",
        mixinStandardHelpOptions = true,
        description = "Generates Java unit tests locally using Codex."
)
public final class GenerateCommand implements Callable<Integer> {

    private static final Duration PROJECT_COVERAGE_TIMEOUT = Duration.ofMinutes(20);

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<class>",
            description = "Class path, fully qualified class name, or unique Java class name."
    )
    private String selector;

    @Option(
            names = "--changed",
            description = "Generate tests for uncommitted Java production classes."
    )
    private boolean changed;

    @Option(
            names = "--coverage-below",
            arity = "0..1",
            fallbackValue = "80",
            paramLabel = "<percent>",
            description = "Generate tests for Java production classes below the coverage threshold. Defaults to 80 when no value is provided."
    )
    private Double coverageBelow;

    @Option(
            names = "--agent",
            defaultValue = "codex",
            description = "Agent provider to use. Default: ${DEFAULT-VALUE}."
    )
    private String agent;

    @Option(
            names = "--model",
            paramLabel = "<model>",
            description = "Optional Codex model override."
    )
    private String model;

    @Option(
            names = "--show-logs",
            description = "Stream Codex, validation, and JaCoCo logs during generation."
    )
    private boolean showLogs;

    @Spec
    private CommandSpec spec;

    private final ProjectFileService fileService;
    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final CodexCliUnitTestGenerator generator;
    private final ProcessExecutor processExecutor;

    public GenerateCommand() {
        this(new ProjectFileService(), new CoverageReportService());
    }

    GenerateCommand(ProjectFileService fileService, CoverageReportService coverageReportService) {
        this.fileService = fileService;
        this.projectService = new JavaProjectService(fileService, coverageReportService);
        this.coverageReportService = coverageReportService;
        this.generator = new CodexCliUnitTestGenerator(fileService, projectService);
        this.processExecutor = new ProcessExecutor();
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        TerminalUi ui = new TerminalUi(out);
        TerminalUi errUi = new TerminalUi(err);
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path projectRoot = projectService.resolveProjectRoot(workingDirectory);

        if (!"codex".equalsIgnoreCase(agent)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Only `codex` is supported in the current open-source backend-free flow."
            );
        }

        List<JavaProjectService.JavaClassDescriptor> targets = resolveTargets(projectRoot);
        if (targets.isEmpty()) {
            ui.warn("No matching Java production classes found.");
            return CommandLine.ExitCode.OK;
        }

        CoverageReportService.CoverageSnapshot baselineSnapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElse(null);

        ui.printBanner("Generating Java tests locally with Codex and JaCoCo");
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("project", projectRoot.toString());
        metadata.put("agent", agent.toLowerCase());
        metadata.put("target mode", targetModeDescription());
        metadata.put("targets", String.valueOf(targets.size()));
        metadata.put("execution", isParallelBatch(targets) ? "parallel isolated x" + resolveParallelism(targets.size()) : "sequential");
        metadata.put("logs", showLogs ? "live" : "summary");
        ui.printKeyValues(metadata);
        if (baselineSnapshot == null) {
            ui.warn("Coverage baseline unavailable. Run JaCoCo once to compare before and after totals.");
        } else {
            ui.section("Baseline");
            ui.printCoverageMeter("line", baselineSnapshot.totalLineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD);
            ui.printCoverageMeter("branch", baselineSnapshot.totalBranchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD);
        }
        ui.section("Queue");
        ui.printTable(
                List.of("Class", "Line", "Branch", "Tests"),
                queueRows(targets, baselineSnapshot, ui)
        );

        RunSummary runSummary = isParallelBatch(targets)
                ? runParallelGeneration(projectRoot, targets, baselineSnapshot, model, ui, errUi, out)
                : runSequentialGeneration(targets, baselineSnapshot, model, ui, errUi, out);

        CoverageReportService.CoverageSnapshot finalSnapshot = runSummary.successCount() > 0
                ? refreshProjectCoverage(projectRoot, ui, out)
                : coverageReportService.readProjectSnapshot(projectRoot).orElse(null);
        ui.section("Run Summary");
        LinkedHashMap<String, String> summary = new LinkedHashMap<>();
        summary.put("successful classes", String.valueOf(runSummary.successCount()));
        summary.put("failed classes", String.valueOf(runSummary.failureCount()));
        summary.put(
                "total tokens",
                "input=%d cached=%d output=%d reasoning=%d total=%d".formatted(
                        runSummary.totalUsage().inputTokens(),
                        runSummary.totalUsage().cachedInputTokens(),
                        runSummary.totalUsage().outputTokens(),
                        runSummary.totalUsage().reasoningOutputTokens(),
                        runSummary.totalUsage().totalTokens()
                )
        );
        ui.printKeyValues(summary);
        if (!runSummary.failedClasses().isEmpty()) {
            ui.printTable(
                    List.of("Failed class"),
                    runSummary.failedClasses().stream().map(value -> List.of(value)).toList()
            );
        }
        printCoverageSummary(ui, baselineSnapshot, finalSnapshot);
        printRemainingThresholdFailures(ui, projectRoot, finalSnapshot);
        return runSummary.failureCount() == 0 ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }

    private RunSummary runSequentialGeneration(
            List<JavaProjectService.JavaClassDescriptor> targets,
            CoverageReportService.CoverageSnapshot baselineSnapshot,
            String model,
            TerminalUi ui,
            TerminalUi errUi,
            PrintWriter out
    ) {
        int successCount = 0;
        int failureCount = 0;
        CodexCliUnitTestGenerator.AgentUsage totalUsage = CodexCliUnitTestGenerator.AgentUsage.zero();
        List<String> failedClasses = new ArrayList<>();

        for (int index = 0; index < targets.size(); index++) {
            JavaProjectService.JavaClassDescriptor descriptor = targets.get(index);
            CoverageReportService.ClassCoverage baselineClassCoverage = baselineSnapshot == null
                    ? null
                    : baselineSnapshot.classCoverage(descriptor.fullyQualifiedName()).orElse(null);
            printClassContext(out, ui, descriptor, baselineClassCoverage, index + 1, targets.size());
            try {
                CodexCliUnitTestGenerator.GenerationResult result = generator.generate(
                        descriptor,
                        model,
                        ui,
                        showLogs,
                        true,
                        out
                );
                printClassResult(out, ui, normalizeResult(result.outputPath(), result, baselineClassCoverage));
                totalUsage = totalUsage.plus(result.usage());
                successCount++;
            } catch (Exception exception) {
                errUi.error("Failed for " + descriptor.fullyQualifiedName() + ": " + exception.getMessage());
                failedClasses.add(descriptor.fullyQualifiedName());
                failureCount++;
            }
            ui.blankLine();
        }
        return new RunSummary(successCount, failureCount, totalUsage, List.copyOf(failedClasses));
    }

    private RunSummary runParallelGeneration(
            Path projectRoot,
            List<JavaProjectService.JavaClassDescriptor> targets,
            CoverageReportService.CoverageSnapshot baselineSnapshot,
            String model,
            TerminalUi ui,
            TerminalUi errUi,
            PrintWriter out
    ) {
        int parallelism = resolveParallelism(targets.size());
        ui.section("Execution");
        ui.info("Running %d isolated sandboxes with parallelism %d.".formatted(targets.size(), parallelism));
        Path batchRoot;
        try {
            batchRoot = Files.createTempDirectory("jaipilot-batch-");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create temporary workspace for parallel generation.", exception);
        }

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        CompletionService<GenerationOutcome> completionService = new ExecutorCompletionService<>(executor);
        try {
            for (JavaProjectService.JavaClassDescriptor descriptor : targets) {
                completionService.submit(() -> generateInSandbox(batchRoot, projectRoot, descriptor, model, ui, out));
            }

            int successCount = 0;
            int failureCount = 0;
            int completedCount = 0;
            CodexCliUnitTestGenerator.AgentUsage totalUsage = CodexCliUnitTestGenerator.AgentUsage.zero();
            List<String> failedClasses = new ArrayList<>();

            for (int index = 0; index < targets.size(); index++) {
                GenerationOutcome outcome = takeCompletedOutcome(completionService);
                completedCount++;
                CoverageReportService.ClassCoverage baselineClassCoverage = baselineSnapshot == null
                        ? null
                        : baselineSnapshot.classCoverage(outcome.descriptor().fullyQualifiedName()).orElse(null);
                printClassContext(out, ui, outcome.descriptor(), baselineClassCoverage, completedCount, targets.size());
                if (outcome.success()) {
                    fileService.writeFile(outcome.result().outputPath(), outcome.generatedTestContent());
                    CodexCliUnitTestGenerator.GenerationResult mergedResult = normalizeResult(
                            outcome.result().outputPath(),
                            outcome.result(),
                            baselineClassCoverage
                    );
                    printClassResult(out, ui, mergedResult);
                    totalUsage = totalUsage.plus(mergedResult.usage());
                    successCount++;
                } else {
                    errUi.error("Failed for " + outcome.descriptor().fullyQualifiedName() + ": " + outcome.failureMessage());
                    failedClasses.add(outcome.descriptor().fullyQualifiedName());
                    failureCount++;
                }
                ui.blankLine();
            }

            return new RunSummary(successCount, failureCount, totalUsage, List.copyOf(failedClasses));
        } finally {
            executor.shutdownNow();
            fileService.deleteRecursively(batchRoot);
        }
    }

    private GenerationOutcome generateInSandbox(
            Path batchRoot,
            Path projectRoot,
            JavaProjectService.JavaClassDescriptor descriptor,
            String model,
            TerminalUi ui,
            PrintWriter sharedWriter
    ) {
        Path sandboxRoot = batchRoot.resolve(sanitizeFileName(descriptor.className()) + "-" + Integer.toUnsignedString(descriptor.fullyQualifiedName().hashCode(), 36));
        PrintWriter workerWriter = sharedWriter;
        try {
            fileService.copyProjectWorkspace(projectRoot, sandboxRoot);
            JavaProjectService sandboxProjectService = new JavaProjectService(fileService, coverageReportService);
            JavaProjectService.JavaClassDescriptor sandboxDescriptor = sandboxProjectService.resolveClass(
                    sandboxRoot,
                    normalizeRelativePath(projectRoot.relativize(descriptor.cutPath()))
            );
            if (showLogs) {
                workerWriter = new PrintWriter(
                        new PrefixedWriter(sharedWriter, badgePrefix(ui, descriptor.className())),
                        true
                );
            }
            CodexCliUnitTestGenerator sandboxGenerator = new CodexCliUnitTestGenerator(fileService, sandboxProjectService);
            CodexCliUnitTestGenerator.GenerationResult result = sandboxGenerator.generate(
                    sandboxDescriptor,
                    model,
                    ui,
                    showLogs,
                    false,
                    workerWriter
            );
            Path projectOutputPath = projectRoot.resolve(sandboxRoot.relativize(result.outputPath())).normalize();
            return GenerationOutcome.success(
                    descriptor,
                    rebaseOutputPath(projectOutputPath, result),
                    fileService.readFile(result.outputPath())
            );
        } catch (Exception exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.toString()
                    : exception.getMessage();
            return GenerationOutcome.failure(descriptor, message);
        } finally {
            if (showLogs && workerWriter != sharedWriter) {
                workerWriter.flush();
            }
            fileService.deleteRecursively(sandboxRoot);
        }
    }

    private GenerationOutcome takeCompletedOutcome(CompletionService<GenerationOutcome> completionService) {
        try {
            return completionService.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Parallel generation was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("Parallel generation failed unexpectedly.", cause);
        }
    }

    private CoverageReportService.CoverageSnapshot refreshProjectCoverage(
            Path projectRoot,
            TerminalUi ui,
            PrintWriter out
    ) {
        ui.section("Coverage Refresh");
        var coverageCommand = projectService.buildProjectCoverageCommand(projectRoot);
        if (coverageCommand.isEmpty()) {
            ui.warn("Skipped final coverage refresh because no project-level JaCoCo task was detected.");
            return coverageReportService.readProjectSnapshot(projectRoot).orElse(null);
        }
        try {
            if (showLogs) {
                out.printf("%s %s%n", ui.badge(TerminalUi.Tone.PRIMARY, "summary"), formatCommand(coverageCommand.get()));
                out.flush();
            }
            ProcessExecutor.ExecutionResult result = processExecutor.execute(
                    coverageCommand.get(),
                    projectRoot,
                    PROJECT_COVERAGE_TIMEOUT,
                    showLogs,
                    out,
                    null,
                    showLogs ? ProcessExecutor.ProgressListener.noOp() : ui.spinner("refreshing full-project JaCoCo coverage")
            );
            if (result.timedOut()) {
                ui.warn("Final project coverage refresh timed out.");
            } else if (result.exitCode() != 0) {
                ui.warn("Final project coverage refresh failed.");
                if (!showLogs) {
                    ui.info(tail(result.output()));
                }
            }
        } catch (Exception exception) {
            ui.warn("Final project coverage refresh failed: " + exception.getMessage());
        }
        return coverageReportService.readProjectSnapshot(projectRoot).orElse(null);
    }

    private List<JavaProjectService.JavaClassDescriptor> resolveTargets(Path projectRoot) {
        boolean hasExplicitSelector = selector != null && !selector.isBlank() && !"all".equalsIgnoreCase(selector);
        int modes = (hasExplicitSelector ? 1 : 0) + (changed ? 1 : 0) + (coverageBelow != null ? 1 : 0);
        if (modes != 1) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Choose exactly one target mode: `<class>`, `--changed`, or `--coverage-below <percent>`."
            );
        }
        if (coverageBelow != null && (coverageBelow <= 0 || coverageBelow > 100)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "--coverage-below must be between 0 and 100."
            );
        }
        if (hasExplicitSelector) {
            return List.of(projectService.resolveClass(projectRoot, selector));
        }
        if (changed) {
            return projectService.findChangedProductionClasses(projectRoot);
        }
        return projectService.findClassesBelowCoverage(projectRoot, coverageBelow);
    }

    private List<List<String>> queueRows(
            List<JavaProjectService.JavaClassDescriptor> targets,
            CoverageReportService.CoverageSnapshot snapshot,
            TerminalUi ui
    ) {
        List<List<String>> rows = new ArrayList<>();
        for (JavaProjectService.JavaClassDescriptor descriptor : targets) {
            CoverageReportService.ClassCoverage coverage = snapshot == null
                    ? null
                    : snapshot.classCoverage(descriptor.fullyQualifiedName()).orElse(null);
            rows.add(List.of(
                    descriptor.fullyQualifiedName(),
                    ui.formatCoverage(coverage == null ? null : coverage.lineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(coverage == null ? null : coverage.branchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    formatTestState(descriptor, ui)
            ));
        }
        return rows;
    }

    private void printClassResult(
            PrintWriter out,
            TerminalUi ui,
            CodexCliUnitTestGenerator.GenerationResult result
    ) {
        out.printf(
                "  %s %s %s%n",
                ui.highlight("result"),
                result.outputPath(),
                result.testExistedBefore() ? ui.muted("(updated)") : ui.muted("(created)")
        );
        if (result.coverageDelta().available()) {
            out.printf(
                    "  %s line %s -> %s  branch %s -> %s%n",
                    ui.highlight("coverage"),
                    ui.formatCoverage(result.coverageDelta().beforeLineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(result.coverageDelta().afterLineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(result.coverageDelta().beforeBranchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(result.coverageDelta().afterBranchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD)
            );
        } else {
            out.printf("  %s %s%n", ui.highlight("coverage"), ui.muted(result.coverageDelta().unavailableReason()));
        }
        out.printf(
                "  %s input=%d cached=%d output=%d reasoning=%d total=%d%n",
                ui.highlight("usage"),
                result.usage().inputTokens(),
                result.usage().cachedInputTokens(),
                result.usage().outputTokens(),
                result.usage().reasoningOutputTokens(),
                result.usage().totalTokens()
        );
        if (result.note() != null && !result.note().isBlank()) {
            out.printf("  %s %s%n", ui.highlight("note"), result.note());
        }
        out.flush();
    }

    private void printClassContext(
            PrintWriter out,
            TerminalUi ui,
            JavaProjectService.JavaClassDescriptor descriptor,
            CoverageReportService.ClassCoverage baselineClassCoverage,
            int completed,
            int total
    ) {
        out.printf(
                "%s %s%n",
                ui.badge(TerminalUi.Tone.PRIMARY, "%d/%d".formatted(completed, total)),
                ui.accent(descriptor.fullyQualifiedName())
        );
        out.printf("  %s %s%n", ui.highlight("source"), descriptor.cutPath());
        out.printf(
                "  %s line %s  branch %s  %s%n",
                ui.highlight("baseline"),
                ui.formatCoverage(baselineClassCoverage == null ? null : baselineClassCoverage.lineCoverage(),
                        StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                ui.formatCoverage(baselineClassCoverage == null ? null : baselineClassCoverage.branchCoverage(),
                        StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                formatTestState(descriptor, ui)
        );
    }

    private CodexCliUnitTestGenerator.GenerationResult normalizeResult(
            Path outputPath,
            CodexCliUnitTestGenerator.GenerationResult result,
            CoverageReportService.ClassCoverage baselineClassCoverage
    ) {
        if (!result.coverageDelta().available()) {
            return new CodexCliUnitTestGenerator.GenerationResult(
                    outputPath,
                    result.usage(),
                    result.coverageDelta(),
                    result.testExistedBefore(),
                    result.note()
            );
        }
        return new CodexCliUnitTestGenerator.GenerationResult(
                outputPath,
                result.usage(),
                new CodexCliUnitTestGenerator.CoverageDelta(
                        baselineClassCoverage,
                        result.coverageDelta().afterClassCoverage(),
                        null
                ),
                result.testExistedBefore(),
                result.note()
        );
    }

    private CodexCliUnitTestGenerator.GenerationResult rebaseOutputPath(
            Path outputPath,
            CodexCliUnitTestGenerator.GenerationResult result
    ) {
        return new CodexCliUnitTestGenerator.GenerationResult(
                outputPath,
                result.usage(),
                result.coverageDelta(),
                result.testExistedBefore(),
                result.note()
        );
    }

    private void printCoverageSummary(
            TerminalUi ui,
            CoverageReportService.CoverageSnapshot baselineSnapshot,
            CoverageReportService.CoverageSnapshot finalSnapshot
    ) {
        ui.section("Coverage Summary");
        if (finalSnapshot == null) {
            ui.warn("Coverage after run: unavailable");
            return;
        }
        if (baselineSnapshot == null) {
            ui.printCoverageMeter("line", finalSnapshot.totalLineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD);
            ui.printCoverageMeter("branch", finalSnapshot.totalBranchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD);
            return;
        }
        ui.printCoverageMeter("line", finalSnapshot.totalLineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD);
        ui.printCoverageMeter("branch", finalSnapshot.totalBranchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD);
        ui.info(
                "Line: %.1f%% -> %.1f%% (%+.1f)".formatted(
                        baselineSnapshot.totalLineCoverage(),
                        finalSnapshot.totalLineCoverage(),
                        finalSnapshot.totalLineCoverage() - baselineSnapshot.totalLineCoverage()
                )
        );
        ui.info(
                "Branch: %.1f%% -> %.1f%% (%+.1f)".formatted(
                        baselineSnapshot.totalBranchCoverage(),
                        finalSnapshot.totalBranchCoverage(),
                        finalSnapshot.totalBranchCoverage() - baselineSnapshot.totalBranchCoverage()
                )
        );
    }

    private void printRemainingThresholdFailures(
            TerminalUi ui,
            Path projectRoot,
            CoverageReportService.CoverageSnapshot finalSnapshot
    ) {
        if (finalSnapshot == null) {
            return;
        }
        List<JavaProjectService.JavaClassDescriptor> belowThreshold = projectService.findClassesBelowCoverage(
                projectRoot,
                StatusCommand.DEFAULT_COVERAGE_THRESHOLD
        );
        ui.section("Remaining Below Threshold");
        if (belowThreshold.isEmpty()) {
            ui.success("All Java production classes now meet the %.1f%% line threshold.".formatted(
                    StatusCommand.DEFAULT_COVERAGE_THRESHOLD
            ));
            return;
        }
        ui.info("Classes still below %.1f%% line coverage: %d".formatted(
                StatusCommand.DEFAULT_COVERAGE_THRESHOLD,
                belowThreshold.size()
        ));
        List<List<String>> rows = new ArrayList<>();
        for (JavaProjectService.JavaClassDescriptor descriptor : belowThreshold) {
            CoverageReportService.ClassCoverage coverage = finalSnapshot.classCoverage(descriptor.fullyQualifiedName())
                    .orElse(new CoverageReportService.ClassCoverage(descriptor.fullyQualifiedName(), 0.0d, 0.0d));
            rows.add(List.of(
                    descriptor.fullyQualifiedName(),
                    ui.formatCoverage(coverage.lineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(coverage.branchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    formatTestState(descriptor, ui)
            ));
        }
        ui.printTable(List.of("Class", "Line", "Branch", "Tests"), rows);
    }

    private String formatTestState(JavaProjectService.JavaClassDescriptor descriptor, TerminalUi ui) {
        return ui.formatTestState(projectService.hasLikelyTests(descriptor));
    }

    private String targetModeDescription() {
        if (selector != null && !selector.isBlank() && !"all".equalsIgnoreCase(selector)) {
            return selector;
        }
        if (changed) {
            return "changed/uncommitted production classes";
        }
        if (coverageBelow != null) {
            return "classes below %.1f%% line coverage".formatted(coverageBelow);
        }
        return "unknown";
    }

    private boolean isParallelBatch(List<JavaProjectService.JavaClassDescriptor> targets) {
        return targets.size() > 1 && (changed || coverageBelow != null);
    }

    private int resolveParallelism(int targetCount) {
        int processors = Math.max(2, Runtime.getRuntime().availableProcessors());
        return Math.min(targetCount, Math.max(2, Math.min(processors, 4)));
    }

    private String badgePrefix(TerminalUi ui, String label) {
        return ui.badge(TerminalUi.Tone.PRIMARY, label) + " ";
    }

    private String normalizeRelativePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
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
        int start = Math.max(0, lines.size() - 40);
        return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
    }

    private record RunSummary(
            int successCount,
            int failureCount,
            CodexCliUnitTestGenerator.AgentUsage totalUsage,
            List<String> failedClasses
    ) {
    }

    private record GenerationOutcome(
            JavaProjectService.JavaClassDescriptor descriptor,
            CodexCliUnitTestGenerator.GenerationResult result,
            String generatedTestContent,
            String failureMessage
    ) {
        static GenerationOutcome success(
                JavaProjectService.JavaClassDescriptor descriptor,
                CodexCliUnitTestGenerator.GenerationResult result,
                String generatedTestContent
        ) {
            return new GenerationOutcome(descriptor, result, generatedTestContent, null);
        }

        static GenerationOutcome failure(
                JavaProjectService.JavaClassDescriptor descriptor,
                String failureMessage
        ) {
            return new GenerationOutcome(descriptor, null, null, failureMessage);
        }

        boolean success() {
            return failureMessage == null;
        }
    }

    private static final class PrefixedWriter extends Writer {

        private final PrintWriter delegate;
        private final String prefix;
        private final StringBuilder buffer = new StringBuilder();

        private PrefixedWriter(PrintWriter delegate, String prefix) {
            this.delegate = delegate;
            this.prefix = prefix;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                char character = characters[index];
                if (character == '\r') {
                    continue;
                }
                if (character == '\n') {
                    emit(true);
                    continue;
                }
                buffer.append(character);
            }
        }

        @Override
        public void flush() {
            emit(false);
            synchronized (delegate) {
                delegate.flush();
            }
        }

        @Override
        public void close() {
            flush();
        }

        private void emit(boolean appendNewLine) {
            synchronized (delegate) {
                if (buffer.length() == 0) {
                    if (appendNewLine) {
                        delegate.println();
                    }
                    return;
                }
                delegate.print(prefix);
                delegate.print(buffer);
                if (appendNewLine) {
                    delegate.println();
                }
                delegate.flush();
                buffer.setLength(0);
            }
        }
    }
}
