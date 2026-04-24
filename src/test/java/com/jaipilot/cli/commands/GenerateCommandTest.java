package com.jaipilot.cli.commands;

import com.jaipilot.cli.service.ProjectFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void initialTestClassCodeUsesExistingOutputFileAsBaseline() throws Exception {
        Path outputPath = tempDir.resolve("src/test/java/com/example/CrashControllerTest.java");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, "class CrashControllerTest {}");

        GenerateCommand command = new GenerateCommand(new ProjectFileService());

        assertEquals("class CrashControllerTest {}", command.initialTestClassCode(outputPath));
    }
}
