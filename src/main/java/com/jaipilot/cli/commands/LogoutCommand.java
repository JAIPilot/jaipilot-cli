package com.jaipilot.cli.commands;

import com.jaipilot.cli.auth.CredentialsStore;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "logout",
        mixinStandardHelpOptions = true,
        description = "Clear the stored JAIPilot login session."
)
public final class LogoutCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    private final CredentialsStore credentialsStore;

    public LogoutCommand() {
        this(new CredentialsStore());
    }

    LogoutCommand(CredentialsStore credentialsStore) {
        this.credentialsStore = credentialsStore;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        credentialsStore.clear();
        out.println("Signed out.");
        out.flush();
        return CommandLine.ExitCode.OK;
    }
}
