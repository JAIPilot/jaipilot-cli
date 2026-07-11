package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CodexCliUnitTestGenerator;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Checks local Codex, Java build, git, and JaCoCo prerequisites."
)
public final class DoctorCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final CodexCliUnitTestGenerator generator;

    public DoctorCommand() {
        this(new ProjectFileService(), new CoverageReportService());
    }

    DoctorCommand(ProjectFileService fileService, CoverageReportService coverageReportService) {
        this.projectService = new JavaProjectService(fileService, coverageReportService);
        this.coverageReportService = coverageReportService;
        this.generator = new CodexCliUnitTestGenerator(fileService, projectService);
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path projectRoot = projectService.resolveProjectRoot(workingDirectory);

        out.printf("Project root: %s%n", projectRoot);
        out.printf("Build tool: %s%n", projectService.detectBuildTool(projectRoot).displayName());
        out.printf("Codex CLI: %s%n", generator.codexVersion(projectRoot).orElse("not found"));
        out.printf("JaCoCo configured: %s%n", projectService.supportsCoverage(projectRoot) ? "yes" : "no");
        out.printf("Default threshold: %.1f%%%n", StatusCommand.DEFAULT_COVERAGE_THRESHOLD);
        out.printf("Changed production classes: %d%n", projectService.findChangedProductionClasses(projectRoot).size());
        out.printf(
                "JaCoCo report: %s%n",
                coverageReportService.findCoverageReport(projectRoot)
                        .map(Path::toString)
                        .orElse("not found")
        );
        return CommandLine.ExitCode.OK;
    }
}
