package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CodexCliUnitTestGenerator;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.ui.TerminalUi;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        TerminalUi ui = new TerminalUi(spec.commandLine().getOut());
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path projectRoot = projectService.resolveProjectRoot(workingDirectory);
        String codexVersion = generator.codexVersion(projectRoot).orElse("not found");
        var buildTool = projectService.detectBuildToolIfPresent(projectRoot);
        boolean jacocoConfigured = buildTool.map(tool -> projectService.supportsCoverage(projectRoot)).orElse(false);
        String buildWrapper = projectService.resolveBuildWrapper(projectRoot).orElse("unavailable");
        String reportPath = coverageReportService.findCoverageReport(projectRoot)
                .map(Path::toString)
                .orElse("not found");

        ui.printBanner("Local environment checks");
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("project", projectRoot.toString());
        metadata.put("build tool", buildTool.map(JavaProjectService.BuildTool::displayName).orElse("none"));
        metadata.put("default threshold", "%.1f%%".formatted(StatusCommand.DEFAULT_COVERAGE_THRESHOLD));
        metadata.put("changed classes", String.valueOf(projectService.findChangedProductionClasses(projectRoot).size()));
        ui.printKeyValues(metadata);

        ui.section("Checks");
        ui.printTable(
                java.util.List.of("Check", "Status", "Details"),
                java.util.List.of(
                        java.util.List.of("Codex CLI", "not found".equals(codexVersion) ? ui.badge(TerminalUi.Tone.ERROR, "missing")
                                : ui.badge(TerminalUi.Tone.SUCCESS, "ready"), codexVersion),
                        java.util.List.of("Build wrapper", "unavailable".equals(buildWrapper) ? ui.badge(TerminalUi.Tone.WARN, "optional")
                                : ui.badge(TerminalUi.Tone.SUCCESS, "ready"), "unavailable".equals(buildWrapper)
                                ? "generation still works, but Codex may be limited in local test and coverage checks"
                                : buildWrapper),
                        java.util.List.of("JaCoCo config", jacocoConfigured ? ui.badge(TerminalUi.Tone.SUCCESS, "ready")
                                : ui.badge(TerminalUi.Tone.WARN, "missing"), jacocoConfigured ? "jacoco detected in build files"
                                : "add jacoco to pom.xml or build.gradle"),
                        java.util.List.of("JaCoCo report", "not found".equals(reportPath) ? ui.badge(TerminalUi.Tone.WARN, "missing")
                                : ui.badge(TerminalUi.Tone.SUCCESS, "ready"), reportPath)
                )
        );
        return CommandLine.ExitCode.OK;
    }
}
