package com.jaipilot.cli.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectFileServiceTest {

    private final ProjectFileService projectFileService = new ProjectFileService();

    @TempDir
    Path tempDir;

    @Test
    void resolvePathResolvesRelativePathsAgainstProjectRoot() {
        Path projectRoot = tempDir.resolve("workspace");
        Path resolved = projectFileService.resolvePath(projectRoot, Path.of("src/main/java/com/example/Cut.java"));

        assertEquals(
                projectRoot.resolve("src/main/java/com/example/Cut.java").normalize(),
                resolved
        );
    }

    @Test
    void resolvePathReturnsNormalizedAbsolutePath() {
        Path absolute = tempDir.resolve("workspace").resolve("src/main/java/../java/com/example/Cut.java");

        Path resolved = projectFileService.resolvePath(tempDir, absolute);

        assertEquals(absolute.normalize(), resolved);
    }

    @Test
    void findNearestBuildProjectRootWalksUpFromGradleSourceFile() throws Exception {
        Path projectRoot = tempDir.resolve("gradle-sample");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("build.gradle.kts"), "plugins { java }");
        Path cutPath = projectRoot.resolve("src/main/java/com/example/CrashController.java");
        Files.createDirectories(cutPath.getParent());
        Files.writeString(cutPath, "package com.example;");

        assertEquals(projectRoot, projectFileService.findNearestBuildProjectRoot(cutPath));
    }

    @Test
    void stripJavaExtensionRemovesSuffixWhenPresent() {
        assertEquals("CrashController", projectFileService.stripJavaExtension("CrashController.java"));
        assertEquals("CrashController", projectFileService.stripJavaExtension("CrashController"));
    }

    @Test
    void writeFileDoesNotFormatJavaSource() throws Exception {
        Path javaFile = tempDir.resolve("Example.java");
        String source = "package com.example;class Example{void run(){}}";

        projectFileService.writeFile(javaFile, source);

        assertEquals(source, Files.readString(javaFile));
    }
}
