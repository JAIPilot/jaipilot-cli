package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.ProjectFileService;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "generate",
        mixinStandardHelpOptions = true,
        description = "Generates tests for a class."
)
public final class GenerateCommand extends BaseJunitLlmCommand {

    @Parameters(
            index = "0",
            paramLabel = "<cut>",
            description = "Path to the class under test."
    )
    private Path cutPath;

    public GenerateCommand() {
        super();
    }

    GenerateCommand(ProjectFileService fileService) {
        super(fileService);
    }

    @Override
    protected Path resolveCutPath(Path workingDirectory) {
        return fileService().resolvePath(workingDirectory, cutPath);
    }
}
