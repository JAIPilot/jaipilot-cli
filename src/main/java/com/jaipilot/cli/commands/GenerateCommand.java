package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CodexCliUnitTestGenerator;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
            out.println("No matching Java production classes found.");
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

        out.printf("Project: %s%n", projectRoot);
        out.printf("Agent: %s%n", agent.toLowerCase());
        out.printf("Targets: %d%n", targets.size());
        if (baselineSnapshot == null) {
            out.println("Coverage baseline: unavailable (run JaCoCo once to compare before/after totals)");
        } else {
            out.printf(
                    "Coverage baseline: line %.1f%%, branch %.1f%%%n",
                    baselineSnapshot.totalLineCoverage(),
                    baselineSnapshot.totalBranchCoverage()
            );
        }

        for (int index = 0; index < targets.size(); index++) {
            JavaProjectService.JavaClassDescriptor descriptor = targets.get(index);
            CoverageReportService.ClassCoverage baselineClassCoverage = baselineSnapshot == null
                    ? null
                    : baselineSnapshot.classCoverage(descriptor.fullyQualifiedName()).orElse(null);
            String testState = Files.isRegularFile(descriptor.testPath()) ? "test-present" : "test-missing";
            out.printf(
                    "[%d/%d] %s line=%s branch=%s %s%n",
                    index + 1,
                    targets.size(),
                    descriptor.fullyQualifiedName(),
                    formatCoverage(baselineClassCoverage == null ? null : baselineClassCoverage.lineCoverage()),
                    formatCoverage(baselineClassCoverage == null ? null : baselineClassCoverage.branchCoverage()),
                    testState
            );
            try {
                CodexCliUnitTestGenerator.GenerationResult result = generator.generate(descriptor, model, out);
                out.printf("Generated %s%n", result.outputPath());
                totalUsage = totalUsage.plus(result.usage());
                if (result.estimatedCost().available()) {
                    totalEstimatedCostUsd += result.estimatedCost().usd();
                } else {
                    estimatedCostAvailableForAll = false;
                }
                successCount++;
            } catch (Exception exception) {
                err.printf("Failed for %s: %s%n", descriptor.fullyQualifiedName(), exception.getMessage());
                failedClasses.add(descriptor.fullyQualifiedName());
                failureCount++;
            }
        }

        CoverageReportService.CoverageSnapshot finalSnapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElse(null);
        out.println();
        out.println("Run summary");
        out.printf("Successful classes: %d%n", successCount);
        out.printf("Failed classes: %d%n", failureCount);
        if (!failedClasses.isEmpty()) {
            out.println("Failures:");
            for (String failedClass : failedClasses) {
                out.printf("  - %s%n", failedClass);
            }
        }
        out.printf(
                "Total usage: input=%d cached=%d output=%d reasoning=%d total=%d%n",
                totalUsage.inputTokens(),
                totalUsage.cachedInputTokens(),
                totalUsage.outputTokens(),
                totalUsage.reasoningOutputTokens(),
                totalUsage.totalTokens()
        );
        if (successCount == 0 || !estimatedCostAvailableForAll) {
            out.println("Estimated cost: unavailable (configure JAIPILOT_CODEX_*_COST_PER_MILLION_USD to enable)");
        } else {
            out.printf("Estimated cost: $%.4f%n", totalEstimatedCostUsd);
        }
        printCoverageSummary(out, baselineSnapshot, finalSnapshot);
        printRemainingThresholdFailures(out, projectRoot, finalSnapshot);
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

    private void printCoverageSummary(
            PrintWriter out,
            CoverageReportService.CoverageSnapshot baselineSnapshot,
            CoverageReportService.CoverageSnapshot finalSnapshot
    ) {
        if (finalSnapshot == null) {
            out.println("Coverage after run: unavailable");
            return;
        }
        if (baselineSnapshot == null) {
            out.printf(
                    "Coverage after run: line %.1f%%, branch %.1f%%%n",
                    finalSnapshot.totalLineCoverage(),
                    finalSnapshot.totalBranchCoverage()
            );
            return;
        }
        out.printf(
                "Coverage improvement: line %.1f%% -> %.1f%%, branch %.1f%% -> %.1f%%%n",
                baselineSnapshot.totalLineCoverage(),
                finalSnapshot.totalLineCoverage(),
                baselineSnapshot.totalBranchCoverage(),
                finalSnapshot.totalBranchCoverage()
        );
    }

    private void printRemainingThresholdFailures(
            PrintWriter out,
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
        out.printf(
                "Classes still below %.1f%% line coverage: %d%n",
                StatusCommand.DEFAULT_COVERAGE_THRESHOLD,
                belowThreshold.size()
        );
        for (JavaProjectService.JavaClassDescriptor descriptor : belowThreshold) {
            CoverageReportService.ClassCoverage coverage = finalSnapshot.classCoverage(descriptor.fullyQualifiedName())
                    .orElse(new CoverageReportService.ClassCoverage(descriptor.fullyQualifiedName(), 0.0d, 0.0d));
            String testState = Files.isRegularFile(descriptor.testPath()) ? "test-present" : "test-missing";
            out.printf(
                    "  - %s line=%.1f%% branch=%.1f%% %s%n",
                    descriptor.fullyQualifiedName(),
                    coverage.lineCoverage(),
                    coverage.branchCoverage(),
                    testState
            );
        }
    }

    private String formatCoverage(Double coverage) {
        if (coverage == null) {
            return "n/a";
        }
        return "%.1f%%".formatted(coverage);
    }
}
