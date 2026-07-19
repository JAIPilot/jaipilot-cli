package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.CoverageRefreshService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.ui.TerminalUi;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Refreshes full-suite coverage, then shows JaCoCo totals and classes below the threshold."
)
public final class StatusCommand implements Callable<Integer> {

    public static final double DEFAULT_COVERAGE_THRESHOLD = 80.0d;

    @Option(
            names = "--threshold",
            defaultValue = "80",
            paramLabel = "<percent>",
            description = "Coverage threshold to evaluate. Default: ${DEFAULT-VALUE}."
    )
    private double threshold;

    @Option(
            names = "--cached",
            description = "Read the existing JaCoCo XML without running the full test suite."
    )
    private boolean cached;

    @Option(
            names = "--show-logs",
            description = "Stream build output while refreshing coverage."
    )
    private boolean showLogs;

    @Spec
    private CommandSpec spec;

    private final JavaProjectService projectService;
    private final CoverageRefreshService coverageRefreshService;

    public StatusCommand() {
        this(new ProjectFileService(), new CoverageReportService());
    }

    StatusCommand(ProjectFileService fileService, CoverageReportService coverageReportService) {
        this.projectService = new JavaProjectService(fileService, coverageReportService);
        this.coverageRefreshService = new CoverageRefreshService(projectService, coverageReportService);
    }

    @Override
    public Integer call() {
        if (threshold <= 0 || threshold > 100) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--threshold must be between 0 and 100.");
        }
        if (cached && showLogs) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--show-logs cannot be used with --cached.");
        }

        TerminalUi ui = new TerminalUi(spec.commandLine().getOut());
        Path projectRoot = projectService.resolveProjectRoot(Path.of("").toAbsolutePath().normalize());
        ui.printBanner("JaCoCo status and threshold tracking");
        LinkedHashMap<String, String> projectMetadata = new LinkedHashMap<>();
        projectMetadata.put("project", projectRoot.toString());
        projectMetadata.put("threshold", "%.1f%%".formatted(threshold));
        ui.printKeyValues(projectMetadata);

        ui.section("Coverage Source");
        CoverageReportService.CoverageSnapshot snapshot;
        String coverageSource;
        if (cached) {
            snapshot = coverageRefreshService.readCachedSnapshot(projectRoot)
                    .orElseThrow(() -> new CommandLine.ParameterException(
                            spec.commandLine(),
                            "No cached JaCoCo XML report found under the current project."
                    ));
            ui.warn("Using cached JaCoCo XML without running tests; it may reflect a focused or older run.");
            coverageSource = "cached report";
        } else {
            snapshot = coverageRefreshService.refresh(
                    projectRoot,
                    ui,
                    showLogs,
                    spec.commandLine().getOut()
            );
            ui.success("Clean full-suite coverage refreshed.");
            coverageSource = "fresh full suite";
        }
        ui.blankLine();
        List<JavaProjectService.JavaClassDescriptor> belowThreshold = projectService.findClassesBelowCoverage(
                projectRoot,
                threshold,
                snapshot
        );

        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("coverage source", coverageSource);
        metadata.put("report", snapshot.reportPath().toString());
        metadata.put("below threshold", String.valueOf(belowThreshold.size()));
        ui.printKeyValues(metadata);
        ui.section("Totals");
        ui.printCoverageMeter("line", snapshot.totalLineCoverage(), threshold);
        ui.printCoverageMeter("branch", snapshot.totalBranchCoverage(), threshold);

        if (belowThreshold.isEmpty()) {
            ui.section("Threshold Result");
            ui.success("All Java production classes currently meet the threshold.");
            return CommandLine.ExitCode.OK;
        }

        ui.section("Classes Below Threshold");
        List<List<String>> rows = new ArrayList<>();
        Map<JavaProjectService.JavaClassDescriptor, Boolean> testPresence = projectService.likelyTestPresence(belowThreshold);
        for (JavaProjectService.JavaClassDescriptor descriptor : belowThreshold) {
            CoverageReportService.ClassCoverage coverage = snapshot.classCoverageByName()
                    .get(descriptor.fullyQualifiedName());
            double lineCoverage = coverage == null ? 0.0d : coverage.lineCoverage();
            double branchCoverage = coverage == null ? 0.0d : coverage.branchCoverage();
            rows.add(List.of(
                    descriptor.fullyQualifiedName(),
                    ui.formatCoverage(lineCoverage, threshold),
                    ui.formatCoverage(branchCoverage, threshold),
                    ui.formatTestState(testPresence.getOrDefault(descriptor, false))
            ));
        }
        ui.printTable(List.of("Class", "Line", "Branch", "Test file"), rows);
        ui.info("Use `jaipilot generate --coverage-below %.0f` to target this set.".formatted(threshold));
        return CommandLine.ExitCode.OK;
    }
}
