package com.jaipilot.cli.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void readResourceReadsBundledPromptTemplate() {
        String template = projectFileService.readResource("prompts/generate-java-tests.md");

        assertTrue(template.contains("Generate or update JUnit tests for one Java production class."));
    }

    @Test
    void copyProjectWorkspaceSkipsBuildOutputsAndGitMetadata() throws Exception {
        Path sourceRoot = tempDir.resolve("source");
        Files.createDirectories(sourceRoot.resolve(".git"));
        Files.createDirectories(sourceRoot.resolve("target/classes"));
        Files.createDirectories(sourceRoot.resolve("build/tmp"));
        Files.createDirectories(sourceRoot.resolve("src/main/java/com/example"));
        Path wrapperScript = sourceRoot.resolve("mvnw");
        Files.writeString(sourceRoot.resolve(".git/config"), "[core]");
        Files.writeString(sourceRoot.resolve("target/classes/Example.class"), "compiled");
        Files.writeString(sourceRoot.resolve("build/tmp/output.txt"), "generated");
        Files.writeString(sourceRoot.resolve(".DS_Store"), "ignored");
        Files.writeString(sourceRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(wrapperScript, "#!/bin/sh\nexit 0\n");
        wrapperScript.toFile().setExecutable(true, false);
        Files.writeString(sourceRoot.resolve("src/main/java/com/example/Example.java"), "class Example {}");

        Path destinationRoot = tempDir.resolve("destination");
        projectFileService.copyProjectWorkspace(sourceRoot, destinationRoot);

        assertTrue(Files.isRegularFile(destinationRoot.resolve("pom.xml")));
        assertTrue(Files.isExecutable(destinationRoot.resolve("mvnw")));
        assertTrue(Files.isRegularFile(destinationRoot.resolve("src/main/java/com/example/Example.java")));
        assertFalse(Files.exists(destinationRoot.resolve(".git/config")));
        assertFalse(Files.exists(destinationRoot.resolve("target/classes/Example.class")));
        assertFalse(Files.exists(destinationRoot.resolve("build/tmp/output.txt")));
        assertFalse(Files.exists(destinationRoot.resolve(".DS_Store")));
    }
}
