package com.jaipilot.cli.commands;

import com.jaipilot.cli.JaipilotEndpointConfig;
import com.jaipilot.cli.backend.HttpJunitLlmBackendClient;
import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.JunitLlmOperation;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.process.GradleCommandBuilder;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import com.jaipilot.cli.service.JunitLlmConsoleLogger;
import com.jaipilot.cli.service.JunitLlmSessionRunner;
import com.jaipilot.cli.service.JunitLlmWorkflowRunner;
import com.jaipilot.cli.service.UsedContextClassPathCache;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

abstract class BaseJunitLlmCommand implements Callable<Integer> {

    private static final Duration GENERATE_TIMEOUT = Duration.ofSeconds(600);

    @Spec
    private CommandSpec spec;

    private final ProjectFileService fileService;

    BaseJunitLlmCommand() {
        this(new ProjectFileService());
    }

    BaseJunitLlmCommand(ProjectFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public final Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        JunitLlmConsoleLogger consoleLogger = new JunitLlmConsoleLogger(out);
        JunitLlmConsoleLogger errorLogger = new JunitLlmConsoleLogger(err);
        Instant startedAt = Instant.now();

        try {
            Path workingDirectory = Path.of("").toAbsolutePath().normalize();
            Path resolvedCutPath = resolveCutPath(workingDirectory);
            Path normalizedProjectRoot = inferProjectRoot(workingDirectory, resolvedCutPath);
            Path resolvedOutputPath = defaultOutputPath(workingDirectory, normalizedProjectRoot, resolvedCutPath);
            String initialOutputContent = Files.isRegularFile(resolvedOutputPath)
                    ? fileService.readFile(resolvedOutputPath)
                    : "";

            JunitLlmBackendClient backendClient = new HttpJunitLlmBackendClient(
                    JaipilotEndpointConfig.resolveBackendUrl(),
                    resolveLicenseKey()
            );
            JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                    backendClient,
                    fileService,
                    new UsedContextClassPathCache(),
                    consoleLogger
            );
            JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                    sessionRunner,
                    new MavenCommandBuilder(),
                    new GradleCommandBuilder(),
                    new ProcessExecutor(),
                    fileService,
                    true,
                    out
            );
            JunitLlmSessionResult result = workflowRunner.run(new JunitLlmSessionRequest(
                    normalizedProjectRoot,
                    resolvedCutPath,
                    resolvedOutputPath,
                    operation(),
                    null,
                    initialTestClassCode(resolvedOutputPath),
                    "",
                    null
            ),
                    null,
                    List.of(),
                    GENERATE_TIMEOUT
            );

            consoleLogger.announceTestFile(result.outputPath());
            consoleLogger.announceTestFileDiff(initialOutputContent, fileService.readFile(result.outputPath()));
            consoleLogger.announceTotalTime(Duration.between(startedAt, Instant.now()));
            return CommandLine.ExitCode.OK;
        } catch (CommandLine.ParameterException exception) {
            throw exception;
        } catch (Exception exception) {
            errorLogger.error(exception.getMessage());
            errorLogger.announceTotalTime(Duration.between(startedAt, Instant.now()));
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    protected abstract JunitLlmOperation operation();

    protected abstract Path resolveCutPath(Path workingDirectory);

    protected abstract Path defaultOutputPath(Path workingDirectory, Path projectRoot, Path resolvedCutPath);

    protected abstract String initialTestClassCode(Path resolvedOutputPath);

    protected final ProjectFileService fileService() {
        return fileService;
    }

    private String resolveLicenseKey() {
        String licenseKey = firstNonBlank(System.getenv("JAIPILOT_LICENSE_KEY"));
        if (licenseKey == null) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Set JAIPILOT_LICENSE_KEY."
            );
        }
        return licenseKey;
    }

    private Path inferProjectRoot(Path workingDirectory, Path resolvedCutPath) {
        Path inferredProjectRoot = fileService.findNearestBuildProjectRoot(resolvedCutPath);
        if (inferredProjectRoot == null) {
            inferredProjectRoot = fileService.findNearestBuildProjectRoot(workingDirectory);
        }
        return inferredProjectRoot != null ? inferredProjectRoot : workingDirectory;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
