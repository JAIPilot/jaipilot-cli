package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CodexCliUnitTestGenerator;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
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
            description = "Stream Codex, validation, and JaCoCo logs during generation."
    )
    private boolean showLogs;

    @Spec
    private CommandSpec spec;

    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final CodexCliUnitTestGenerator generator;

    public GenerateCommand() {
        this(new ProjectFileService(), new CoverageReportService());
    }

    GenerateCommand(ProjectFileService fileService, CoverageReportService coverageReportService) {
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

        List<JavaProjectService.JavaClassDescriptor> targets = resolveTargets(projectRoot);
        if (targets.isEmpty()) {
            ui.warn("No matching Java production classes found.");
            return CommandLine.ExitCode.OK;
        }

        CoverageReportService.CoverageSnapshot baselineSnapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElse(null);
        int successCount = 0;
        int failureCount = 0;
        CodexCliUnitTestGenerator.AgentUsage totalUsage = CodexCliUnitTestGenerator.AgentUsage.zero();
        double totalEstimatedCostUsd = 0.0d;
        boolean estimatedCostAvailableForAll = true;
        List<String> failedClasses = new ArrayList<>();

        ui.printBanner("Generating Java tests locally with Codex and JaCoCo");
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("project", projectRoot.toString());
        metadata.put("agent", agent.toLowerCase());
        metadata.put("target mode", targetModeDescription());
        metadata.put("targets", String.valueOf(targets.size()));
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

        for (int index = 0; index < targets.size(); index++) {
            JavaProjectService.JavaClassDescriptor descriptor = targets.get(index);
            CoverageReportService.ClassCoverage baselineClassCoverage = baselineSnapshot == null
                    ? null
                    : baselineSnapshot.classCoverage(descriptor.fullyQualifiedName()).orElse(null);
            out.printf(
                    "%s %s%n",
                    ui.badge(TerminalUi.Tone.PRIMARY, "%d/%d".formatted(index + 1, targets.size())),
                    ui.accent(descriptor.fullyQualifiedName())
            );
            out.printf("  %s %s%n", ui.highlight("source"), descriptor.cutPath());
            out.printf("  %s %s%n", ui.highlight("target"), descriptor.testPath());
            out.printf(
                    "  %s line %s  branch %s  %s%n",
                    ui.highlight("baseline"),
                    ui.formatCoverage(baselineClassCoverage == null ? null : baselineClassCoverage.lineCoverage(),
                            StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(baselineClassCoverage == null ? null : baselineClassCoverage.branchCoverage(),
                            StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatTestState(Files.isRegularFile(descriptor.testPath()))
            );
            try {
                CodexCliUnitTestGenerator.GenerationResult result = generator.generate(descriptor, model, ui, showLogs, out);
                printClassResult(out, ui, result);
                totalUsage = totalUsage.plus(result.usage());
                if (result.estimatedCost().available()) {
                    totalEstimatedCostUsd += result.estimatedCost().usd();
                } else {
                    estimatedCostAvailableForAll = false;
                }
                successCount++;
            } catch (Exception exception) {
                errUi.error("Failed for " + descriptor.fullyQualifiedName() + ": " + exception.getMessage());
                failedClasses.add(descriptor.fullyQualifiedName());
                failureCount++;
            }
            ui.blankLine();
        }

        CoverageReportService.CoverageSnapshot finalSnapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElse(null);
        ui.section("Run Summary");
        LinkedHashMap<String, String> summary = new LinkedHashMap<>();
        summary.put("successful classes", String.valueOf(successCount));
        summary.put("failed classes", String.valueOf(failureCount));
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
        summary.put(
                "estimated cost",
                successCount == 0 || !estimatedCostAvailableForAll
                        ? "unavailable (configure JAIPILOT_CODEX_*_COST_PER_MILLION_USD to enable)"
                        : "$%.4f".formatted(totalEstimatedCostUsd)
        );
        ui.printKeyValues(summary);
        if (!failedClasses.isEmpty()) {
            ui.printTable(
                    List.of("Failed class"),
                    failedClasses.stream().map(value -> List.of(value)).toList()
            );
        }
        printCoverageSummary(ui, baselineSnapshot, finalSnapshot);
        printRemainingThresholdFailures(ui, projectRoot, finalSnapshot);
        return failureCount == 0 ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
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
                    ui.truncate(descriptor.fullyQualifiedName(), 56),
                    ui.formatCoverage(coverage == null ? null : coverage.lineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(coverage == null ? null : coverage.branchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatTestState(Files.isRegularFile(descriptor.testPath()))
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
                "  %s input=%d cached=%d output=%d reasoning=%d total=%d  cost=%s%n",
                ui.highlight("usage"),
                result.usage().inputTokens(),
                result.usage().cachedInputTokens(),
                result.usage().outputTokens(),
                result.usage().reasoningOutputTokens(),
                result.usage().totalTokens(),
                result.estimatedCost().display()
        );
        out.flush();
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
                    ui.truncate(descriptor.fullyQualifiedName(), 56),
                    ui.formatCoverage(coverage.lineCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatCoverage(coverage.branchCoverage(), StatusCommand.DEFAULT_COVERAGE_THRESHOLD),
                    ui.formatTestState(Files.isRegularFile(descriptor.testPath()))
            ));
        }
        ui.printTable(List.of("Class", "Line", "Branch", "Tests"), rows);
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
}
