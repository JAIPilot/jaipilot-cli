package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Shows Java coverage status, JaCoCo totals, and classes below the threshold."
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

    @Spec
    private CommandSpec spec;

    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;

    public StatusCommand() {
        this(new ProjectFileService(), new CoverageReportService());
    }

    StatusCommand(ProjectFileService fileService, CoverageReportService coverageReportService) {
        this.projectService = new JavaProjectService(fileService, coverageReportService);
        this.coverageReportService = coverageReportService;
    }

    @Override
    public Integer call() {
        if (threshold <= 0 || threshold > 100) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--threshold must be between 0 and 100.");
        }

        PrintWriter out = spec.commandLine().getOut();
        Path projectRoot = projectService.resolveProjectRoot(Path.of("").toAbsolutePath().normalize());
        CoverageReportService.CoverageSnapshot snapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow(() -> new CommandLine.ParameterException(
                        spec.commandLine(),
                        "No JaCoCo XML report found under the current project."
                ));

        out.printf("Project root: %s%n", projectRoot);
        out.printf("Coverage report: %s%n", snapshot.reportPath());
        out.printf("Threshold: %.1f%%%n", threshold);
        out.printf(
                "Current totals: line %.1f%%, branch %.1f%%%n",
                snapshot.totalLineCoverage(),
                snapshot.totalBranchCoverage()
        );

        List<JavaProjectService.JavaClassDescriptor> belowThreshold = projectService.findClassesBelowCoverage(
                projectRoot,
                threshold
        );
        out.printf("Classes below threshold: %d%n", belowThreshold.size());
        if (belowThreshold.isEmpty()) {
            out.println("All Java production classes currently meet the threshold.");
            return CommandLine.ExitCode.OK;
        }

        out.println("Classes below threshold:");
        for (JavaProjectService.JavaClassDescriptor descriptor : belowThreshold) {
            CoverageReportService.ClassCoverage coverage = snapshot.classCoverageByName()
                    .get(descriptor.fullyQualifiedName());
            String testStatus = Files.isRegularFile(descriptor.testPath()) ? "test-present" : "test-missing";
            double lineCoverage = coverage == null ? 0.0d : coverage.lineCoverage();
            double branchCoverage = coverage == null ? 0.0d : coverage.branchCoverage();
            out.printf(
                    "  - %s  line=%.1f%% branch=%.1f%% %s%n",
                    descriptor.fullyQualifiedName(),
                    lineCoverage,
                    branchCoverage,
                    testStatus
            );
        }
        return CommandLine.ExitCode.OK;
    }
}
