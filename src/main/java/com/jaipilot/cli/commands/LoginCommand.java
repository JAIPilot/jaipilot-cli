package com.jaipilot.cli.commands;

import com.jaipilot.cli.auth.AuthService;
import com.jaipilot.cli.auth.CredentialsStore;
import com.jaipilot.cli.auth.TokenInfo;
import com.jaipilot.cli.util.JaipilotAuthTokenStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "login",
        mixinStandardHelpOptions = true,
        description = "Signs in to JAIPilot (browser flow) or stores a provided auth token."
)
public final class LoginCommand implements Callable<Integer> {

    @Option(
            names = "--timeout-seconds",
            defaultValue = "180",
            paramLabel = "<seconds>",
            description = "Maximum time to wait for browser login. Default: ${DEFAULT-VALUE}."
    )
    private long timeoutSeconds;

    @Spec
    private CommandSpec spec;

    private final AuthService authService;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<token>",
            description = "JAIPilot auth token. If omitted, browser login flow is started."
    )
    private String tokenArg;

    public LoginCommand() {
        this(new AuthService(new CredentialsStore()));
    }

    LoginCommand(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        String authToken = firstNonBlank(tokenArg, System.getenv("JAIPILOT_AUTH_TOKEN"));
        if (authToken == null) {
            if (timeoutSeconds < 1) {
                throw new CommandLine.ParameterException(
                        spec.commandLine(),
                        "--timeout-seconds must be greater than zero."
                );
            }
            try {
                TokenInfo tokenInfo = authService.startLogin(Duration.ofSeconds(timeoutSeconds), out, err);
                out.println("Signed in as: "
                        + (tokenInfo.email() == null || tokenInfo.email().isBlank() ? "(unknown)" : tokenInfo.email()));
                out.flush();
                return CommandLine.ExitCode.OK;
            } catch (IllegalStateException exception) {
                throw new CommandLine.ParameterException(
                        spec.commandLine(),
                        "JAIPilot login failed: " + exception.getMessage(),
                        exception
                );
            }
        }

        try {
            Path tokenPath = JaipilotAuthTokenStore.saveAuthToken(authToken);
            out.printf("Saved JAIPilot auth token to %s%n", tokenPath);
            return CommandLine.ExitCode.OK;
        } catch (IllegalArgumentException | IOException exception) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Failed to save JAIPilot auth token: " + exception.getMessage(),
                    exception
            );
        }
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
