package com.jaipilot.cli;

import com.jaipilot.cli.commands.DoctorCommand;
import com.jaipilot.cli.commands.GenerateCommand;
import com.jaipilot.cli.commands.StatusCommand;
import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.InteractiveShell;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.service.StartupUpdateService;
import java.io.Console;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "jaipilot",
        mixinStandardHelpOptions = true,
        versionProvider = JaiPilotVersionProvider.class,
        description = "Generate Java unit tests locally with coding agents.",
        subcommands = {
            GenerateCommand.class,
            StatusCommand.class,
            DoctorCommand.class
        }
)
public final class JaiPilotCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = createCommandLine().execute(args);
        System.exit(exitCode);
    }

    public static CommandLine createCommandLine() {
        return JaiPilotCommandLineFactory.create();
    }

    @Override
    public Integer call() {
        Console console = System.console();
        if (console != null) {
            StartupUpdateService.Result updateResult = StartupUpdateService.createDefault().checkForUpdate(
                    console::readLine,
                    spec.commandLine().getOut(),
                    spec.commandLine().getErr()
            );
            if (updateResult == StartupUpdateService.Result.UPDATED) {
                return CommandLine.ExitCode.OK;
            }
            InteractiveShell shell = new InteractiveShell(
                    new JavaProjectService(new ProjectFileService(), new CoverageReportService())
            );
            return shell.run(spec.commandLine().getOut(), spec.commandLine().getErr());
        }
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.OK;
    }
}
