package com.jaipilot.cli.commands;

import com.jaipilot.cli.auth.AuthService;
import com.jaipilot.cli.auth.CredentialsStore;
import com.jaipilot.cli.auth.TokenInfo;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "login",
        mixinStandardHelpOptions = true,
        description = "Sign in to JAIPilot using the browser login flow."
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

    public LoginCommand() {
        this(new AuthService(new CredentialsStore()));
    }

    LoginCommand(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        TokenInfo tokenInfo = authService.startLogin(Duration.ofSeconds(timeoutSeconds), out, spec.commandLine().getErr());
        out.println("Signed in as: " + (tokenInfo.email() == null || tokenInfo.email().isBlank() ? "(unknown)" : tokenInfo.email()));
        out.flush();
        return CommandLine.ExitCode.OK;
    }
}
