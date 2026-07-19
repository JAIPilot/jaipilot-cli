package com.jaipilot.cli.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void writeFilesTransactionallyWritesEveryStagedFile() throws Exception {
        Path existing = tempDir.resolve("src/test/java/com/example/ExistingTests.java");
        Path created = tempDir.resolve("src/test/java/com/example/CreatedTests.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "old");
        Map<Path, ProjectFileService.FileFingerprint> baseline = projectFileService.snapshotJavaTestFiles(tempDir);

        projectFileService.writeFilesTransactionally(
                Map.of(existing, "updated", created, "created"),
                baseline
        );

        assertEquals("updated", Files.readString(existing));
        assertEquals("created", Files.readString(created));
    }

    @Test
    void javaSourceSnapshotIncludesProductionAndTestsButNotBuildOutputs() throws Exception {
        Path production = tempDir.resolve("src/main/java/com/example/Example.java");
        Path test = tempDir.resolve("src/test/java/com/example/ExampleTests.java");
        Path generated = tempDir.resolve("target/generated-sources/Generated.java");
        Files.createDirectories(production.getParent());
        Files.createDirectories(test.getParent());
        Files.createDirectories(generated.getParent());
        Files.writeString(production, "class Example {}");
        Files.writeString(test, "class ExampleTests {}");
        Files.writeString(generated, "class Generated {}");

        Map<Path, ProjectFileService.FileFingerprint> snapshot = projectFileService.snapshotJavaSourceFiles(tempDir);

        assertEquals(java.util.Set.of(production, test), snapshot.keySet());
    }

    @Test
    void writeFilesTransactionallyRejectsDriftWithoutOverwritingUserChanges() throws Exception {
        Path existing = tempDir.resolve("src/test/java/com/example/ExistingTests.java");
        Path created = tempDir.resolve("src/test/java/com/example/CreatedTests.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "baseline");
        Map<Path, ProjectFileService.FileFingerprint> baseline = projectFileService.snapshotJavaTestFiles(tempDir);
        Files.writeString(existing, "user edit");

        assertThrows(
                IllegalStateException.class,
                () -> projectFileService.writeFilesTransactionally(
                        Map.of(existing, "generated", created, "created"),
                        baseline
                )
        );

        assertEquals("user edit", Files.readString(existing));
        assertFalse(Files.exists(created));
    }

    @Test
    void stagingFailureLeavesEarlierFilesUnchanged() throws Exception {
        Path existing = tempDir.resolve("src/test/java/a/ExistingTests.java");
        Path blockingParent = tempDir.resolve("src/test/java/z-blocker");
        Path blockedOutput = blockingParent.resolve("BlockedTests.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "original");
        Files.writeString(blockingParent, "not a directory");
        Map<Path, ProjectFileService.FileFingerprint> baseline = projectFileService.snapshotJavaTestFiles(tempDir);

        assertThrows(
                IllegalStateException.class,
                () -> projectFileService.writeFilesTransactionally(
                        Map.of(existing, "generated", blockedOutput, "blocked"),
                        baseline
                )
        );

        assertEquals("original", Files.readString(existing));
        assertEquals("not a directory", Files.readString(blockingParent));
    }

    @Test
    void moveFailureRollsBackFilesAlreadyReplaced() throws Exception {
        Path existing = tempDir.resolve("src/test/java/a/ExistingTests.java");
        Path invalidDestination = tempDir.resolve("src/test/java/z/DirectoryTests.java");
        Files.createDirectories(existing.getParent());
        Files.createDirectories(invalidDestination);
        Files.writeString(existing, "original");
        Files.writeString(invalidDestination.resolve("keep.txt"), "keep");
        Map<Path, ProjectFileService.FileFingerprint> baseline = projectFileService.snapshotJavaTestFiles(tempDir);

        assertThrows(
                IllegalStateException.class,
                () -> projectFileService.writeFilesTransactionally(
                        Map.of(existing, "generated", invalidDestination, "cannot replace a directory"),
                        baseline
                )
        );

        assertEquals("original", Files.readString(existing));
        assertEquals("keep", Files.readString(invalidDestination.resolve("keep.txt")));
    }
}
