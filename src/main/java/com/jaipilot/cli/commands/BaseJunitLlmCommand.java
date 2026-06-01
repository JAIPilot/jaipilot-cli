package com.jaipilot.cli.commands;

import com.jaipilot.cli.JaipilotEndpointConfig;
import com.jaipilot.cli.auth.AuthService;
import com.jaipilot.cli.auth.CredentialsStore;
import com.jaipilot.cli.backend.HttpJunitLlmBackendClient;
import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.service.JunitLlmSessionRunner;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.util.JaipilotAuthTokenStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

abstract class BaseJunitLlmCommand implements Callable<Integer> {

    private static final Duration AUTO_LOGIN_TIMEOUT = Duration.ofSeconds(180);

    @Spec
    private CommandSpec spec;

    private final ProjectFileService fileService;
    private final AuthService authService;

    BaseJunitLlmCommand() {
        this(new ProjectFileService(), new AuthService(new CredentialsStore()));
    }

    BaseJunitLlmCommand(ProjectFileService fileService) {
        this(fileService, new AuthService(new CredentialsStore()));
    }

    BaseJunitLlmCommand(ProjectFileService fileService, AuthService authService) {
        this.fileService = fileService;
        this.authService = authService;
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

            String initialAuthToken = resolveAuthToken(out, err);
            HttpJunitLlmBackendClient.AuthTokenResolver authTokenResolver = createAuthTokenResolver(out, err);
            JunitLlmBackendClient backendClient = new HttpJunitLlmBackendClient(
                    JaipilotEndpointConfig.resolveBackendUrl(),
                    initialAuthToken,
                    authTokenResolver
            );
            JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                    backendClient,
                    fileService,
                    consoleLogger
            );
            sessionRunner.run(new JunitLlmSessionRequest(
                    normalizedProjectRoot,
                    resolvedCutPath,
                    null,
                    null,
                    "",
                    "",
                    null
            ));

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

    private String resolveAuthToken(PrintWriter out, PrintWriter err) {
        String authToken = resolveAuthTokenWithoutLogin();
        if (authToken != null) {
            return authToken;
        }

        if (shouldAttemptAutoLogin()) {
            runAutoLoginFlow(
                    out,
                    err,
                    "No JAIPilot auth token found. Starting `jaipilot login` browser auth flow..."
            );
            authToken = resolveAuthTokenWithoutLogin();
            if (authToken != null) {
                return authToken;
            }
        }

        if (authToken == null) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Set JAIPILOT_AUTH_TOKEN or run `jaipilot login` to sign in."
            );
        }
        return authToken;
    }

    private String resolveAuthTokenWithoutLogin() {
        try {
            return firstNonBlank(
                    System.getenv("JAIPILOT_AUTH_TOKEN"),
                    authService.ensureFreshAccessToken(),
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
    }

    private HttpJunitLlmBackendClient.AuthTokenResolver createAuthTokenResolver(PrintWriter out, PrintWriter err) {
        AtomicBoolean unauthorizedAutoLoginAttempted = new AtomicBoolean(false);
        return (currentToken, attemptedTokens) -> resolveAuthTokenCandidates(
                currentToken,
                attemptedTokens,
                out,
                err,
                unauthorizedAutoLoginAttempted
        );
    }

    private List<String> resolveAuthTokenCandidates(
            String currentToken,
            Set<String> attemptedTokens,
            PrintWriter out,
            PrintWriter err,
            AtomicBoolean unauthorizedAutoLoginAttempted
    ) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, currentToken);
        addConfiguredTokenCandidates(candidates);
        if (shouldAttemptAutoLogin()
                && !hasUnattemptedCandidate(candidates, attemptedTokens)
                && unauthorizedAutoLoginAttempted.compareAndSet(false, true)) {
            runAutoLoginFlow(
                    out,
                    err,
                    "Received Unauthorized from JAIPilot backend. Starting `jaipilot login` browser auth flow..."
            );
            addConfiguredTokenCandidates(candidates);
        }
        return new ArrayList<>(candidates);
    }

    private void addConfiguredTokenCandidates(LinkedHashSet<String> candidates) {
        addCandidate(candidates, System.getenv("JAIPILOT_AUTH_TOKEN"));
        addCandidate(candidates, authService.ensureFreshAccessToken());
        try {
            addCandidate(candidates, JaipilotAuthTokenStore.readBrowserAccessToken());
            addCandidate(candidates, JaipilotAuthTokenStore.readAuthToken());
        } catch (IOException ignored) {
            // Ignore read failures and keep existing candidate set.
        }
    }

    private boolean hasUnattemptedCandidate(LinkedHashSet<String> candidates, Set<String> attemptedTokens) {
        for (String candidate : candidates) {
            if (!attemptedTokens.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void runAutoLoginFlow(PrintWriter out, PrintWriter err, String message) {
        out.println(message);
        out.flush();
        try {
            authService.startLogin(AUTO_LOGIN_TIMEOUT, out, err);
        } catch (IllegalStateException exception) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "JAIPilot login failed: " + exception.getMessage(),
                    exception
            );
        }
    }

    private boolean shouldAttemptAutoLogin() {
        String ci = System.getenv("CI");
        if (ci != null && !ci.isBlank() && !"false".equalsIgnoreCase(ci)) {
            return false;
        }
        String githubActions = System.getenv("GITHUB_ACTIONS");
        return githubActions == null || githubActions.isBlank() || "false".equalsIgnoreCase(githubActions);
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
