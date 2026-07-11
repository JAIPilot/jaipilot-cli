package com.jaipilot.cli;

import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;

public final class JaiPilotCommandLineFactory {

    private JaiPilotCommandLineFactory() {
    }

    public static CommandLine create() {
        return configure(new CommandLine(new JaiPilotCli()));
    }

    public static CommandLine configure(CommandLine commandLine) {
        Objects.requireNonNull(commandLine, "commandLine");
        commandLine.setUsageHelpAutoWidth(true);
        commandLine.setExecutionExceptionHandler(new FriendlyExecutionExceptionHandler());
        commandLine.setParameterExceptionHandler(new FriendlyParameterExceptionHandler());
        return commandLine;
    }

    private static final class FriendlyExecutionExceptionHandler implements IExecutionExceptionHandler {

        @Override
        public int handleExecutionException(Exception exception, CommandLine commandLine, CommandLine.ParseResult parseResult) {
            PrintWriter err = commandLine.getErr();
            TerminalUi ui = new TerminalUi(err);
            ui.error(rootCauseMessage(exception));
            err.printf("Run `%s --help` for usage.%n", commandName(commandLine));
            err.flush();
            return commandLine.getCommandSpec().exitCodeOnExecutionException();
        }
    }

    private static final class FriendlyParameterExceptionHandler implements IParameterExceptionHandler {

        @Override
        public int handleParseException(ParameterException exception, String[] args) {
            CommandLine commandLine = exception.getCommandLine();
            PrintWriter err = commandLine.getErr();
            TerminalUi ui = new TerminalUi(err);
            ui.error(exception.getMessage());
            err.printf("Run `%s --help` for usage.%n", commandName(commandLine));
            err.flush();
            return commandLine.getCommandSpec().exitCodeOnInvalidInput();
        }
    }

    private static String commandName(CommandLine commandLine) {
        String name = commandLine.getCommandName();
        return name == null || name.isBlank() ? "jaipilot" : name;
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.toString();
        }
        return message;
    }
}
