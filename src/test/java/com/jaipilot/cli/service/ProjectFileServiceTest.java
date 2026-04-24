package com.jaipilot.cli.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFileServiceTest {

    private final ProjectFileService projectFileService = new ProjectFileService();

    @TempDir
    Path tempDir;

    @Test
    void deriveGeneratedTestPathRewritesStandardMavenSourceRoots() {
        Path projectRoot = Path.of("/tmp/project");
        Path cutPath = projectRoot.resolve("src/main/java/com/example/CrashController.java");

        Path outputPath = projectFileService.deriveGeneratedTestPath(projectRoot, cutPath);

        assertEquals(
                projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java"),
                outputPath
        );
    }

    @Test
    void deriveGeneratedTestPathPreservesModulePrefix() {
        Path projectRoot = Path.of("/tmp/project");
        Path cutPath = projectRoot.resolve("module-a/src/main/java/com/example/CrashController.java");

        Path outputPath = projectFileService.deriveGeneratedTestPath(projectRoot, cutPath);

        assertEquals(
                projectRoot.resolve("module-a/src/test/java/com/example/CrashControllerTest.java"),
                outputPath
        );
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
    void readContextEntriesFormatsPathAndSource() throws Exception {
        Path projectRoot = tempDir.resolve("petclinic");
        writeSource(
                projectRoot,
                "src/main/java/com/example/support/Helper.java",
                """
                package com.example.support;

                public class Helper {
                }
                """
        );

        assertEquals(
                List.of(
                        "com/example/support/Helper.java =\npackage com.example.support;\n\npublic class Helper {\n}\n"
                ),
                projectFileService.readContextEntries(projectRoot, List.of("com/example/support/Helper.java"))
        );
    }

    @Test
    void inferCutPathFromTestPathRewritesConventionalTestNames() throws Exception {
        Path projectRoot = tempDir.resolve("workspace");
        writeSource(
                projectRoot,
                "src/main/java/com/example/CrashController.java",
                "package com.example;\nclass CrashController {}\n"
        );
        Path testPath = writeSource(
                projectRoot,
                "src/test/java/com/example/CrashControllerTest.java",
                "package com.example;\nclass CrashControllerTest {}\n"
        );

        assertEquals(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                projectFileService.inferCutPathFromTestPath(projectRoot, testPath)
        );
    }

    @Test
    void writeFileDoesNotFormatJavaSource() throws Exception {
        Path javaFile = tempDir.resolve("Example.java");
        String source = "package com.example;class Example{void run(){}}";

        projectFileService.writeFile(javaFile, source);

        assertEquals(source, Files.readString(javaFile));
    }

    private Path writeSource(Path projectRoot, String relativePath, String content) throws Exception {
        Path path = projectRoot.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
