package com.jaipilot.cli.commands;

import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.JunitLlmOperation;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "fix",
        mixinStandardHelpOptions = true,
        description = "Fixes an existing JUnit test by calling the backend JUnit LLM API."
)
public final class FixCommand extends BaseJunitLlmCommand {

    @Parameters(
            index = "0",
            paramLabel = "<test>",
            description = "Path to the failing test class."
    )
    private Path testPath;

    public FixCommand() {
        super();
    }

    FixCommand(ProjectFileService fileService) {
        super(fileService);
    }

    @Override
    protected JunitLlmOperation operation() {
        return JunitLlmOperation.FIX;
    }

    @Override
    protected Path resolveCutPath(Path workingDirectory) {
        return fileService().resolvePath(workingDirectory, testPath);
    }

    @Override
    protected Path defaultOutputPath(Path workingDirectory, Path projectRoot, Path resolvedCutPath) {
        return fileService().resolvePath(workingDirectory, testPath);
    }

    @Override
    protected String initialTestClassCode(Path resolvedOutputPath) {
        return fileService().readFile(resolvedOutputPath);
    }
}
