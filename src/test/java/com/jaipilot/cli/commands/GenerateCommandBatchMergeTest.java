package com.jaipilot.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.service.JavaProjectService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerateCommandBatchMergeTest {

    @Test
    void divergentEditsToTheSamePathFailEveryContributingClass() {
        Path sharedTest = Path.of("src/test/java/com/example/SharedTests.java");
        Map<String, List<Path>> conflicts = GenerateCommand.findOutputConflicts(Map.of(
                "com.example.Alpha", Map.of(sharedTest, "class SharedTests { /* alpha */ }"),
                "com.example.Beta", Map.of(sharedTest, "class SharedTests { /* beta */ }")
        ));

        assertEquals(
                Map.of(
                        "com.example.Alpha", List.of(sharedTest),
                        "com.example.Beta", List.of(sharedTest)
                ),
                conflicts
        );
    }

    @Test
    void identicalSamePathOutputIsSafeAndIndependentFilesDoNotConflict() {
        Path sharedTest = Path.of("src/test/java/com/example/SharedTests.java");
        String sharedContent = "class SharedTests {}";
        Map<String, List<Path>> conflicts = GenerateCommand.findOutputConflicts(Map.of(
                "com.example.Alpha", Map.of(sharedTest, sharedContent),
                "com.example.Beta", Map.of(sharedTest, sharedContent),
                "com.example.Gamma", Map.of(
                        Path.of("src/test/java/com/example/GammaTests.java"),
                        "class GammaTests {}"
                )
        ));

        assertTrue(conflicts.isEmpty());
    }

    @Test
    void conflictPathsAndClassesAreReturnedDeterministically() {
        Path first = Path.of("src/test/java/com/example/AFirstTests.java");
        Path second = Path.of("src/test/java/com/example/ZSecondTests.java");

        Map<String, List<Path>> conflicts = GenerateCommand.findOutputConflicts(Map.of(
                "com.example.Zeta", Map.of(second, "zeta", first, "zeta"),
                "com.example.Alpha", Map.of(second, "alpha", first, "alpha")
        ));

        assertEquals(List.of("com.example.Alpha", "com.example.Zeta"), conflicts.keySet().stream().toList());
        assertEquals(List.of(first, second), conflicts.get("com.example.Alpha"));
        assertEquals(List.of(first, second), conflicts.get("com.example.Zeta"));
    }

    @Test
    void mergeCarriesEveryFileButDropsAConflictedWorkerTransactionally() {
        Path alpha = Path.of("src/test/java/com/example/AlphaTests.java");
        Path alphaFixture = Path.of("src/test/java/com/example/AlphaFixture.java");
        Path beta = Path.of("src/test/java/com/example/BetaTests.java");
        Map<Path, String> merged = GenerateCommand.collectMergeContents(
                Map.of(
                        "com.example.Alpha", Map.of(alpha, "alpha", alphaFixture, "fixture"),
                        "com.example.Beta", Map.of(beta, "beta")
                ),
                java.util.Set.of("com.example.Beta")
        );

        assertEquals(Map.of(alpha, "alpha", alphaFixture, "fixture"), merged);
    }

    @Test
    void targetIdentityDistinguishesDuplicateFullyQualifiedNamesAcrossModules() {
        Path root = Path.of("").toAbsolutePath().resolve("multi-module");
        JavaProjectService.JavaClassDescriptor first = new JavaProjectService.JavaClassDescriptor(
                root,
                root.resolve("module-a"),
                root.resolve("module-a/src/main/java/com/example/Shared.java"),
                "com.example",
                "Shared",
                "com.example.Shared"
        );
        JavaProjectService.JavaClassDescriptor second = new JavaProjectService.JavaClassDescriptor(
                root,
                root.resolve("module-b"),
                root.resolve("module-b/src/main/java/com/example/Shared.java"),
                "com.example",
                "Shared",
                "com.example.Shared"
        );

        assertEquals("module-a/src/main/java/com/example/Shared.java", GenerateCommand.targetIdentity(root, first));
        assertEquals("module-b/src/main/java/com/example/Shared.java", GenerateCommand.targetIdentity(root, second));
    }
}
