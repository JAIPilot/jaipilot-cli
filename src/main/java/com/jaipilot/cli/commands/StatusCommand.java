package com.jaipilot.cli.commands;

import com.jaipilot.cli.auth.AuthService;
import com.jaipilot.cli.auth.CredentialsStore;
import com.jaipilot.cli.auth.TokenInfo;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Show the current JAIPilot login status."
)
public final class StatusCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    private final CredentialsStore credentialsStore;
    private final AuthService authService;

    public StatusCommand() {
        this(new CredentialsStore(), null);
    }

    StatusCommand(CredentialsStore credentialsStore, AuthService authService) {
        this.credentialsStore = credentialsStore;
        this.authService = authService == null ? new AuthService(credentialsStore) : authService;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        TokenInfo storedToken = credentialsStore.load();
        if (storedToken == null || storedToken.accessToken() == null || storedToken.accessToken().isBlank()) {
            out.println("Not signed in.");
            out.flush();
            return CommandLine.ExitCode.OK;
        }

        String accessToken;
        try {
            accessToken = authService.ensureFreshAccessToken();
        } catch (IllegalStateException exception) {
            out.println(exception.getMessage());
            out.flush();
            return CommandLine.ExitCode.SOFTWARE;
        }
        if (accessToken == null || accessToken.isBlank()) {
            out.println("Stored credentials are expired or invalid. Run `jaipilot login` again.");
            out.flush();
            return CommandLine.ExitCode.SOFTWARE;
        }

        TokenInfo refreshedToken = credentialsStore.load();
        String email = refreshedToken != null ? refreshedToken.email() : storedToken.email();
        out.println("Signed in as: " + (email == null || email.isBlank() ? "(unknown)" : email));
        out.println("Credentials file: " + credentialsStore.storePath());
        out.flush();
        return CommandLine.ExitCode.OK;
    }
}
