package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CodexCliUnitTestGenerator;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
            description = "Stream live Codex logs."
    )
    private boolean showLogs;

    @Spec
    private CommandSpec spec;

    private final ProjectFileService fileService;
    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final CodexCliUnitTestGenerator generator;

    public GenerateCommand() {
        this(new ProjectFileService(), new CoverageReportService());
    }

    GenerateCommand(ProjectFileService fileService, CoverageReportService coverageReportService) {
        this.fileService = fileService;
        this.projectService = new JavaProjectService(fileService, coverageReportService);
        this.coverageReportService = coverageReportService;
        this.generator = new CodexCliUnitTestGenerator(fileService, projectService);
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
        validateTargetMode();

        ui.printBanner("Generating Java tests locally with Codex");
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("project", projectRoot.toString());
        metadata.put("agent", agent.toLowerCase());
        metadata.put("target mode", targetModeDescription());
        metadata.put("logs", showLogs ? "live" : "summary");
        ui.printKeyValues(metadata);

        CodexCliUnitTestGenerator.AgentUsage preparationUsage = prepareProjectIfRequired(projectRoot, ui, out);

        List<JavaProjectService.JavaClassDescriptor> targets = resolveTargets(projectRoot);
        if (targets.isEmpty()) {
            ui.warn("No matching Java production classes found.");
            return CommandLine.ExitCode.OK;
        }

        CoverageReportService.CoverageSnapshot baselineSnapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElse(null);
        ui.section("Targets");
        ui.info("Resolved %d target class%s.".formatted(targets.size(), targets.size() == 1 ? "" : "es"));
        ui.info(isParallelBatch(targets)
                ? "Execution mode: parallel isolated x%d".formatted(resolveParallelism(targets.size()))
                : "Execution mode: sequential");
        ui.blankLine();
        if (baselineSnapshot == null) {
            ui.warn("Coverage baseline unavailable. JAIPilot will read any JaCoCo report that Codex refreshes during generation.");
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

        ValidationResult validationResult = validateBatchIfRequired(projectRoot, runSummary, ui, errUi, out);
        CodexCliUnitTestGenerator.AgentUsage totalUsage = preparationUsage
                .plus(runSummary.totalUsage())
                .plus(validationResult.usage());
        ui.section("Run Summary");
        LinkedHashMap<String, String> summary = new LinkedHashMap<>();
        summary.put("successful classes", String.valueOf(runSummary.successCount()));
        summary.put("failed classes", String.valueOf(runSummary.failureCount()));
        if (requiresPreparation()) {
            summary.put("final validation", validationResult.succeeded() ? "passed" : "failed");
        }
        summary.put(
                "total tokens",
                "input=%d cached=%d output=%d reasoning=%d total=%d".formatted(
                        totalUsage.inputTokens(),
                        totalUsage.cachedInputTokens(),
                        totalUsage.outputTokens(),
                        totalUsage.reasoningOutputTokens(),
                        totalUsage.totalTokens()
                )
        );
        ui.printKeyValues(summary);
        if (!runSummary.failedClasses().isEmpty()) {
            ui.printTable(
                    List.of("Failed class"),
                    runSummary.failedClasses().stream().map(value -> List.of(value)).toList()
            );
        }
        if (requiresPreparation()) {
            CoverageReportService.CoverageSnapshot finalSnapshot = coverageReportService.readProjectSnapshot(projectRoot)
                    .orElse(null);
            printCoverageSummary(ui, baselineSnapshot, finalSnapshot);
            printRemainingThresholdFailures(ui, projectRoot, finalSnapshot);
        }
        return runSummary.failureCount() == 0 && validationResult.succeeded()
                ? CommandLine.ExitCode.OK
                : CommandLine.ExitCode.SOFTWARE;
    }

    private void validateTargetMode() {
        int modes = (isExplicitClassTarget() ? 1 : 0) + (changed ? 1 : 0) + (coverageBelow != null ? 1 : 0);
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
    }

    private CodexCliUnitTestGenerator.AgentUsage prepareProjectIfRequired(
            Path projectRoot,
            TerminalUi ui,
            PrintWriter out
    ) {
        ui.section("Preparation");
        if (!requiresPreparation()) {
            ui.info("Skipped for explicit class target. JAIPilot will validate the generated test during generation.");
            ui.blankLine();
            return CodexCliUnitTestGenerator.AgentUsage.zero();
        }

        CodexCliUnitTestGenerator.AgentUsage preparationUsage;
        try {
            preparationUsage = generator.prepareProject(
                    projectRoot,
                    model,
                    ui,
                    showLogs,
                    out
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Codex project preparation failed.", exception);
        }
        ui.success("Codex completed project preparation.");
        ui.info(
                "Preparation tokens: input=%d cached=%d output=%d reasoning=%d total=%d".formatted(
                        preparationUsage.inputTokens(),
                        preparationUsage.cachedInputTokens(),
                        preparationUsage.outputTokens(),
                        preparationUsage.reasoningOutputTokens(),
                        preparationUsage.totalTokens()
                )
        );
        ui.blankLine();
        return preparationUsage;
    }

    private ValidationResult validateBatchIfRequired(
            Path projectRoot,
            RunSummary runSummary,
            TerminalUi ui,
            TerminalUi errUi,
            PrintWriter out
    ) {
        if (!requiresPreparation() || runSummary.successCount() == 0) {
            return new ValidationResult(CodexCliUnitTestGenerator.AgentUsage.zero(), !requiresPreparation());
        }

        ui.section("Final Validation");
        try {
            CodexCliUnitTestGenerator.AgentUsage usage = generator.validateBatch(
                    projectRoot,
                    model,
                    ui,
                    showLogs,
                    out,
                    runSummary.generatedTests()
            );
            ui.success("Clean full-suite validation passed and refreshed JaCoCo coverage.");
            ui.info(
                    "Validation tokens: input=%d cached=%d output=%d reasoning=%d total=%d".formatted(
                            usage.inputTokens(),
                            usage.cachedInputTokens(),
                            usage.outputTokens(),
                            usage.reasoningOutputTokens(),
                            usage.totalTokens()
                    )
            );
            ui.blankLine();
            return new ValidationResult(usage, true);
        } catch (Exception exception) {
            errUi.error("Final merged-test validation failed: " + exception.getMessage());
            ui.blankLine();
            return new ValidationResult(CodexCliUnitTestGenerator.AgentUsage.zero(), false);
        }
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
        List<Path> generatedTests = new ArrayList<>();

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
                generatedTests.addAll(result.touchedTestPaths());
                successCount++;
            } catch (Exception exception) {
                errUi.error("Failed for " + descriptor.fullyQualifiedName() + ": " + exception.getMessage());
                failedClasses.add(descriptor.fullyQualifiedName());
                failureCount++;
            }
            ui.blankLine();
        }
        return new RunSummary(
                successCount,
                failureCount,
                totalUsage,
                List.copyOf(failedClasses),
                List.copyOf(generatedTests)
        );
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
        Map<Path, ProjectFileService.FileFingerprint> batchTestBaseline = fileService.snapshotJavaTestFiles(projectRoot);
        try {
            for (JavaProjectService.JavaClassDescriptor descriptor : targets) {
                completionService.submit(() -> generateInSandbox(batchRoot, projectRoot, descriptor, model, ui, out));
            }

            List<GenerationOutcome> outcomes = new ArrayList<>();
            for (int index = 0; index < targets.size(); index++) {
                outcomes.add(takeCompletedOutcome(completionService));
            }
            outcomes.sort(Comparator
                    .comparing((GenerationOutcome outcome) -> outcome.descriptor().fullyQualifiedName())
                    .thenComparing(outcome -> targetIdentity(projectRoot, outcome.descriptor())));

            Map<String, Map<Path, String>> outputsByClass = new TreeMap<>();
            for (GenerationOutcome outcome : outcomes) {
                if (outcome.success()) {
                    outputsByClass.put(targetIdentity(projectRoot, outcome.descriptor()), outcome.generatedTestContents());
                }
            }
            Map<String, List<Path>> conflictsByClass = findOutputConflicts(outputsByClass);
            Map<Path, String> mergeContents = collectMergeContents(outputsByClass, conflictsByClass.keySet());
            fileService.writeFilesTransactionally(mergeContents, batchTestBaseline);

            int successCount = 0;
            int failureCount = 0;
            CodexCliUnitTestGenerator.AgentUsage totalUsage = CodexCliUnitTestGenerator.AgentUsage.zero();
            List<String> failedClasses = new ArrayList<>();
            List<Path> generatedTests = new ArrayList<>();
            for (int index = 0; index < outcomes.size(); index++) {
                GenerationOutcome outcome = outcomes.get(index);
                CoverageReportService.ClassCoverage baselineClassCoverage = baselineSnapshot == null
                        ? null
                        : baselineSnapshot.classCoverage(outcome.descriptor().fullyQualifiedName()).orElse(null);
                printClassContext(out, ui, outcome.descriptor(), baselineClassCoverage, index + 1, targets.size());
                String targetIdentity = targetIdentity(projectRoot, outcome.descriptor());
                if (outcome.success() && !conflictsByClass.containsKey(targetIdentity)) {
                    CodexCliUnitTestGenerator.GenerationResult mergedResult = normalizeResult(
                            outcome.result().outputPath(),
                            outcome.result(),
                            baselineClassCoverage
                    );
                    printClassResult(out, ui, mergedResult);
                    totalUsage = totalUsage.plus(mergedResult.usage());
                    generatedTests.addAll(mergedResult.touchedTestPaths());
                    successCount++;
                } else {
                    String failureMessage = outcome.failureMessage();
                    if (conflictsByClass.containsKey(targetIdentity)) {
                        failureMessage = "Conflicting parallel edits for " + conflictsByClass
                                .get(targetIdentity).stream()
                                .map(Path::toString)
                                .toList();
                        totalUsage = totalUsage.plus(outcome.result().usage());
                    }
                    errUi.error("Failed for " + outcome.descriptor().fullyQualifiedName() + ": " + failureMessage);
                    failedClasses.add(outcome.descriptor().fullyQualifiedName());
                    failureCount++;
                }
                ui.blankLine();
            }

            return new RunSummary(
                    successCount,
                    failureCount,
                    totalUsage,
                    List.copyOf(failedClasses),
                    List.copyOf(generatedTests)
            );
        } finally {
            executor.shutdownNow();
            boolean terminated;
            try {
                terminated = executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping parallel generation workers.", exception);
            }
            if (!terminated) {
                throw new IllegalStateException("Parallel generation workers did not stop; preserved workspace at "
                        + batchRoot);
            }
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
        String targetIdentity = targetIdentity(projectRoot, descriptor);
        Path sandboxRoot = batchRoot.resolve(sanitizeFileName(descriptor.className()) + "-"
                + UUID.nameUUIDFromBytes(targetIdentity.getBytes(StandardCharsets.UTF_8)));
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
            Map<Path, String> generatedTestContents = new LinkedHashMap<>();
            for (Path touchedTestPath : result.touchedTestPaths()) {
                Path projectTestPath = rebaseSandboxPath(projectRoot, sandboxRoot, touchedTestPath);
                generatedTestContents.put(projectTestPath, fileService.readFile(touchedTestPath));
            }
            Path projectOutputPath = rebaseSandboxPath(projectRoot, sandboxRoot, result.outputPath());
            return GenerationOutcome.success(
                    descriptor,
                    rebaseOutputPaths(projectRoot, sandboxRoot, projectOutputPath, result),
                    Map.copyOf(generatedTestContents)
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

    private Path rebaseSandboxPath(Path projectRoot, Path sandboxRoot, Path sandboxPath) {
        Path normalizedSandboxRoot = sandboxRoot.toAbsolutePath().normalize();
        Path normalizedSandboxPath = sandboxPath.toAbsolutePath().normalize();
        if (!normalizedSandboxPath.startsWith(normalizedSandboxRoot)) {
            throw new IllegalStateException("Generated test escaped its isolated workspace: " + sandboxPath);
        }
        return projectRoot.resolve(normalizedSandboxRoot.relativize(normalizedSandboxPath)).normalize();
    }

    static Map<Path, String> collectMergeContents(
            Map<String, Map<Path, String>> outputsByClass,
            Set<String> conflictedClasses
    ) {
        Map<Path, String> mergedContents = new TreeMap<>();
        outputsByClass.forEach((className, outputs) -> {
            if (!conflictedClasses.contains(className)) {
                mergedContents.putAll(outputs);
            }
        });
        return new LinkedHashMap<>(mergedContents);
    }

    static Map<String, List<Path>> findOutputConflicts(Map<String, Map<Path, String>> outputsByClass) {
        Map<Path, Map<String, Set<String>>> classesByPathAndContent = new TreeMap<>();
        outputsByClass.forEach((className, outputs) -> outputs.forEach((path, content) -> classesByPathAndContent
                .computeIfAbsent(path.normalize(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(content, ignored -> new LinkedHashSet<>())
                .add(className)));

        Map<String, List<Path>> conflictsByClass = new TreeMap<>();
        classesByPathAndContent.forEach((path, classesByContent) -> {
            if (classesByContent.size() <= 1) {
                return;
            }
            classesByContent.values().stream()
                    .flatMap(Set::stream)
                    .distinct()
                    .sorted()
                    .forEach(className -> conflictsByClass
                            .computeIfAbsent(className, ignored -> new ArrayList<>())
                            .add(path));
        });
        return conflictsByClass.entrySet().stream().collect(
                LinkedHashMap::new,
                (result, entry) -> result.put(entry.getKey(), List.copyOf(entry.getValue())),
                Map::putAll
        );
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

    private List<JavaProjectService.JavaClassDescriptor> resolveTargets(Path projectRoot) {
        if (isExplicitClassTarget()) {
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
        Map<JavaProjectService.JavaClassDescriptor, Boolean> testPresence = projectService.likelyTestPresence(targets);
        for (JavaProjectService.JavaClassDescriptor descriptor : targets) {
            CoverageReportService.ClassCoverage coverage = snapshot == null
                    ? null
                    : snapshot.classCoverage(descriptor.fullyQualifiedName()).orElse(null);
            rows.add(List.of(
                    descriptor.fullyQualifiedName(),
                    ui.formatCoverage(coverage == null ? null : coverage.lineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(coverage == null ? null : coverage.branchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatTestState(testPresence.getOrDefault(descriptor, false))
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
                ui.formatTestState(projectService.hasLikelyTests(descriptor))
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
                    result.touchedTestPaths(),
                    result.usage(),
                    result.coverageDelta(),
                    result.testExistedBefore(),
                    result.note()
            );
        }
        return new CodexCliUnitTestGenerator.GenerationResult(
                outputPath,
                result.touchedTestPaths(),
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

    private CodexCliUnitTestGenerator.GenerationResult rebaseOutputPaths(
            Path projectRoot,
            Path sandboxRoot,
            Path outputPath,
            CodexCliUnitTestGenerator.GenerationResult result
    ) {
        return new CodexCliUnitTestGenerator.GenerationResult(
                outputPath,
                result.touchedTestPaths().stream()
                        .map(path -> rebaseSandboxPath(projectRoot, sandboxRoot, path))
                        .toList(),
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
        Map<JavaProjectService.JavaClassDescriptor, Boolean> testPresence = projectService.likelyTestPresence(belowThreshold);
        for (JavaProjectService.JavaClassDescriptor descriptor : belowThreshold) {
            CoverageReportService.ClassCoverage coverage = finalSnapshot.classCoverage(descriptor.fullyQualifiedName())
                    .orElse(new CoverageReportService.ClassCoverage(descriptor.fullyQualifiedName(), 0.0d, 0.0d));
            rows.add(List.of(
                    descriptor.fullyQualifiedName(),
                    ui.formatCoverage(coverage.lineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(coverage.branchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatTestState(testPresence.getOrDefault(descriptor, false))
            ));
        }
        ui.printTable(List.of("Class", "Line", "Branch", "Tests"), rows);
    }

    private String targetModeDescription() {
        if (isExplicitClassTarget()) {
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

    private boolean requiresPreparation() {
        return changed || coverageBelow != null;
    }

    private boolean isExplicitClassTarget() {
        return selector != null && !selector.isBlank() && !"all".equalsIgnoreCase(selector);
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

    static String targetIdentity(
            Path projectRoot,
            JavaProjectService.JavaClassDescriptor descriptor
    ) {
        return projectRoot.toAbsolutePath().normalize()
                .relativize(descriptor.cutPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record RunSummary(
            int successCount,
            int failureCount,
            CodexCliUnitTestGenerator.AgentUsage totalUsage,
            List<String> failedClasses,
            List<Path> generatedTests
    ) {
    }

    private record ValidationResult(
            CodexCliUnitTestGenerator.AgentUsage usage,
            boolean succeeded
    ) {
    }

    private record GenerationOutcome(
            JavaProjectService.JavaClassDescriptor descriptor,
            CodexCliUnitTestGenerator.GenerationResult result,
            Map<Path, String> generatedTestContents,
            String failureMessage
    ) {
        static GenerationOutcome success(
                JavaProjectService.JavaClassDescriptor descriptor,
                CodexCliUnitTestGenerator.GenerationResult result,
                Map<Path, String> generatedTestContents
        ) {
            return new GenerationOutcome(descriptor, result, generatedTestContents, null);
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
