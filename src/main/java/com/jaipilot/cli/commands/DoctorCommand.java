package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.CodexCliUnitTestGenerator;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.CoverageRefreshService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProcessExecutor;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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
    private final CoverageRefreshService coverageRefreshService;
    private final CodexCliUnitTestGenerator generator;
    private final ProcessExecutor processExecutor;

    public DoctorCommand() {
        this(new ProjectFileService(), new CoverageReportService());
    }

    DoctorCommand(ProjectFileService fileService, CoverageReportService coverageReportService) {
        this(fileService, coverageReportService, new ProcessExecutor());
    }

    DoctorCommand(
            ProjectFileService fileService,
            CoverageReportService coverageReportService,
            ProcessExecutor processExecutor
    ) {
        this.projectService = new JavaProjectService(fileService, coverageReportService);
        this.coverageRefreshService = new CoverageRefreshService(projectService, coverageReportService);
        this.generator = new CodexCliUnitTestGenerator(fileService, projectService);
        this.processExecutor = processExecutor;
    }

    @Override
    public Integer call() {
        TerminalUi ui = new TerminalUi(spec.commandLine().getOut());
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path projectRoot = projectService.resolveProjectRoot(workingDirectory);
        String codexVersion = generator.codexVersion(projectRoot).orElse("not found");
        var buildTool = projectService.detectBuildToolIfPresent(projectRoot);
        boolean jacocoConfigured = buildTool.map(tool -> projectService.supportsCoverage(projectRoot)).orElse(false);
        BuildCommandStatus buildCommand = checkBuildCommand(projectRoot, buildTool);
        CoverageReportStatus coverageReport = inspectCoverageReport(projectRoot);

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
                        java.util.List.of("Build command", buildCommand.available()
                                ? ui.badge(TerminalUi.Tone.SUCCESS, "ready")
                                : ui.badge(TerminalUi.Tone.ERROR, "missing"), buildCommand.details()),
                        java.util.List.of("JaCoCo config", jacocoConfigured ? ui.badge(TerminalUi.Tone.SUCCESS, "ready")
                                : ui.badge(TerminalUi.Tone.WARN, "missing"), jacocoConfigured ? "jacoco detected in build files"
                                : "add jacoco to pom.xml or build.gradle"),
                        java.util.List.of("JaCoCo report", switch (coverageReport.state()) {
                            case READY -> ui.badge(TerminalUi.Tone.SUCCESS, "ready");
                            case MISSING -> ui.badge(TerminalUi.Tone.WARN, "missing");
                            case INVALID -> ui.badge(TerminalUi.Tone.ERROR, "invalid");
                        }, coverageReport.details())
                )
        );
        return CommandLine.ExitCode.OK;
    }

    BuildCommandStatus checkBuildCommand(
            Path projectRoot,
            Optional<JavaProjectService.BuildTool> buildTool
    ) {
        if (buildTool.isEmpty()) {
            return new BuildCommandStatus(false, "no Maven or Gradle build file found");
        }
        String executable = projectService.resolveBuildExecutable(projectRoot);
        try {
            ProcessExecutor.ExecutionResult result = processExecutor.execute(
                    List.of(executable, "--version"),
                    projectRoot,
                    Duration.ofSeconds(20),
                    false,
                    new PrintWriter(Writer.nullWriter())
            );
            if (result.timedOut()) {
                return new BuildCommandStatus(false, "`" + executable + " --version` timed out");
            }
            if (result.exitCode() != 0) {
                return new BuildCommandStatus(false, "`" + executable + " --version` exited " + result.exitCode());
            }
            List<String> outputLines = result.output().lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
            String versionPrefix = buildTool.orElseThrow() == JavaProjectService.BuildTool.MAVEN
                    ? "Apache Maven"
                    : "Gradle ";
            String version = outputLines.stream()
                    .filter(line -> line.startsWith(versionPrefix))
                    .findFirst()
                    .or(() -> outputLines.stream().filter(line -> !line.startsWith("WARNING")).findFirst())
                    .orElse("version check passed");
            return new BuildCommandStatus(true, executable + " (" + version + ")");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new BuildCommandStatus(false, "`" + executable + " --version` was interrupted");
        } catch (Exception exception) {
            return new BuildCommandStatus(false, "cannot run `" + executable + " --version`: " + exception.getMessage());
        }
    }

    CoverageReportStatus inspectCoverageReport(Path projectRoot) {
        try {
            return coverageRefreshService.readCachedSnapshot(projectRoot)
                    .map(snapshot -> new CoverageReportStatus(CoverageReportState.READY, snapshot.reportPath().toString()))
                    .orElseGet(() -> new CoverageReportStatus(CoverageReportState.MISSING, "not found"));
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            String details = message == null || message.isBlank()
                    ? exception.toString()
                    : message.lines().findFirst().orElse(message);
            return new CoverageReportStatus(CoverageReportState.INVALID, details);
        }
    }

    record BuildCommandStatus(boolean available, String details) {
    }

    record CoverageReportStatus(CoverageReportState state, String details) {
    }

    enum CoverageReportState {
        READY,
        MISSING,
        INVALID
    }
}
