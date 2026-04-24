package com.jaipilot.cli.commands;

import com.jaipilot.cli.util.JaipilotAuthTokenStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "login",
        mixinStandardHelpOptions = true,
        description = "Stores JAIPilot auth token for local CLI usage."
)
public final class LoginCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<token>",
            description = "JAIPilot auth token. If omitted, JAIPILOT_AUTH_TOKEN is used."
    )
    private String tokenArg;

    @Override
    public Integer call() {
        String authToken = firstNonBlank(tokenArg, System.getenv("JAIPILOT_AUTH_TOKEN"));
        if (authToken == null) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Provide <token> or set JAIPILOT_AUTH_TOKEN."
            );
        }

        try {
            Path tokenPath = JaipilotAuthTokenStore.saveAuthToken(authToken);
            PrintWriter out = spec.commandLine().getOut();
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
