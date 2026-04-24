package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.ProjectFileService;
import com.jaipilot.cli.model.JunitLlmOperation;
import java.nio.file.Files;
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
    protected JunitLlmOperation operation() {
        return JunitLlmOperation.GENERATE;
    }

    @Override
    protected Path resolveCutPath(Path workingDirectory) {
        return fileService().resolvePath(workingDirectory, cutPath);
    }

    @Override
    protected Path defaultOutputPath(Path workingDirectory, Path projectRoot, Path resolvedCutPath) {
        return fileService().deriveGeneratedTestPath(projectRoot, resolvedCutPath);
    }

    @Override
    protected String initialTestClassCode(Path resolvedOutputPath) {
        if (Files.isRegularFile(resolvedOutputPath)) {
            return fileService().readFile(resolvedOutputPath);
        }
        return "";
    }
}
