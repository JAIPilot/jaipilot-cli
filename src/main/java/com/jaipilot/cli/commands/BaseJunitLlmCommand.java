package com.jaipilot.cli.commands;

import com.jaipilot.cli.JaipilotEndpointConfig;
import com.jaipilot.cli.backend.HttpJunitLlmBackendClient;
import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.service.CoverageService;
import com.jaipilot.cli.service.JunitLlmSessionRunner;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.util.JaipilotAuthTokenStore;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

abstract class BaseJunitLlmCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    private final ProjectFileService fileService;
    private final CoverageService coverageService;

    BaseJunitLlmCommand() {
        this(new ProjectFileService(), new CoverageService());
    }

    BaseJunitLlmCommand(ProjectFileService fileService) {
        this(fileService, new CoverageService());
    }

    BaseJunitLlmCommand(ProjectFileService fileService, CoverageService coverageService) {
        this.fileService = fileService;
        this.coverageService = coverageService;
    }

    @Override
    public final Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        JunitLlmSessionRunner.ConsoleLogger consoleLogger = new JunitLlmSessionRunner.ConsoleLogger(out);
        JunitLlmSessionRunner.ConsoleLogger errorLogger = new JunitLlmSessionRunner.ConsoleLogger(err);
        Instant startedAt = Instant.now();

        try {
            Path workingDirectory = Path.of("").toAbsolutePath().normalize();
            Path resolvedCutPath = resolveCutPath(workingDirectory);
            Path normalizedProjectRoot = inferProjectRoot(workingDirectory, resolvedCutPath);
            String cutPathForDisplay = buildCutPathForDisplay(normalizedProjectRoot, resolvedCutPath);

            CoverageService.CoverageMeasurement beforeCoverage = coverageService.measureLineCoverage(
                    normalizedProjectRoot,
                    resolvedCutPath,
                    out
            );
            consoleLogger.announceCoverage("before", cutPathForDisplay, beforeCoverage);

            String initialAuthToken = resolveAuthToken();
            JunitLlmBackendClient backendClient = new HttpJunitLlmBackendClient(
                    JaipilotEndpointConfig.resolveBackendUrl(),
                    initialAuthToken,
                    this::resolveAuthTokenCandidates
            );
            JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                    backendClient,
                    fileService,
                    consoleLogger
            );
            JunitLlmSessionResult result = sessionRunner.run(new JunitLlmSessionRequest(
                    normalizedProjectRoot,
                    resolvedCutPath,
                    null,
                    null,
                    "",
                    "",
                    null
            ));

            consoleLogger.announceTestFile(result.outputPath());
            consoleLogger.announceTestFileDiff(result.previousOutputContent(), result.currentOutputContent());
            CoverageService.CoverageMeasurement afterCoverage = coverageService.measureLineCoverage(
                    normalizedProjectRoot,
                    resolvedCutPath,
                    out
            );
            consoleLogger.announceCoverage("after", cutPathForDisplay, afterCoverage);
            consoleLogger.announceCoverageSummary(cutPathForDisplay, beforeCoverage, afterCoverage);
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

    protected abstract Path resolveCutPath(Path workingDirectory);

    protected final ProjectFileService fileService() {
        return fileService;
    }

    private String resolveAuthToken() {
        String authToken;
        try {
            authToken = firstNonBlank(
                    System.getenv("JAIPILOT_AUTH_TOKEN"),
                    System.getenv("JAIPILOT_LICENSE_KEY"),
                    JaipilotAuthTokenStore.readBrowserAccessToken(),
                    JaipilotAuthTokenStore.readAuthToken()
            );
        } catch (IOException exception) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Failed to read stored JAIPilot auth token: " + exception.getMessage(),
                    exception
            );
        }
        if (authToken == null) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Set JAIPILOT_AUTH_TOKEN or JAIPILOT_LICENSE_KEY, run 'jaipilot login <token>', or provide browser-login "
                            + "credentials at ~/.config/jaipilot/credentials.json."
            );
        }
        return authToken;
    }

    private List<String> resolveAuthTokenCandidates(String currentToken) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, currentToken);
        addCandidate(candidates, System.getenv("JAIPILOT_AUTH_TOKEN"));
        addCandidate(candidates, System.getenv("JAIPILOT_LICENSE_KEY"));
        try {
            addCandidate(candidates, JaipilotAuthTokenStore.readBrowserAccessToken());
            addCandidate(candidates, JaipilotAuthTokenStore.readAuthToken());
        } catch (IOException ignored) {
            // Ignore read failures and keep existing candidate set.
        }
        return new ArrayList<>(candidates);
    }

    private static void addCandidate(LinkedHashSet<String> candidates, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        candidates.add(trimmed);
    }

    private Path inferProjectRoot(Path workingDirectory, Path resolvedCutPath) {
        Path inferredProjectRoot = fileService.findNearestBuildProjectRoot(resolvedCutPath);
        if (inferredProjectRoot == null) {
            inferredProjectRoot = fileService.findNearestBuildProjectRoot(workingDirectory);
        }
        return inferredProjectRoot != null ? inferredProjectRoot : workingDirectory;
    }

    private String buildCutPathForDisplay(Path projectRoot, Path cutPath) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedCutPath = cutPath.toAbsolutePath().normalize();
        if (normalizedCutPath.startsWith(normalizedProjectRoot)) {
            return normalizedProjectRoot.relativize(normalizedCutPath).toString().replace('\\', '/');
        }
        return normalizedCutPath.toString().replace('\\', '/');
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
