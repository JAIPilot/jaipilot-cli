package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptTemplateServiceTest {

    private final ProjectFileService fileService = new ProjectFileService();
    private final PromptTemplateService promptTemplateService = new PromptTemplateService(fileService);

    @TempDir
    Path tempDir;

    @Test
    void buildInitialPromptUsesSavedTemplateWithoutReadingUnusedPlaceholders() {
        Path projectRoot = tempDir.resolve("sample");
        Path sourcePath = projectRoot.resolve("src/main/java/com/example/OrderService.java");

        JavaProjectService.JavaClassDescriptor descriptor = new JavaProjectService.JavaClassDescriptor(
                projectRoot,
                projectRoot,
                sourcePath,
                "com.example",
                "OrderService",
                "com.example.OrderService"
        );

        String prompt = promptTemplateService.buildInitialPrompt(descriptor);

        assertTrue(prompt.contains("Generate or update JUnit tests for one Java production class."));
        assertTrue(prompt.contains("Current class under test: `com.example.OrderService`"));
        assertTrue(prompt.contains("Source file: `" + sourcePath + "`"));
        assertTrue(prompt.contains("Before editing, read these files if they exist:"));
        assertTrue(prompt.contains("keep iterating until that target test passes"));
        assertTrue(prompt.contains("report that blocker instead of fixing unrelated code or tests"));
        assertTrue(prompt.contains("isolated sandbox workspace in parallel"));
        assertTrue(prompt.contains("name matches the build's test-discovery includes"));
        assertFalse(prompt.contains("package com.example; class OrderService {}"));
        assertFalse(prompt.contains("Target test class:"));
        assertFalse(prompt.contains("com.example.OrderServiceTest"));
        assertFalse(prompt.contains("Supabase"));
        assertFalse(prompt.contains("JAIPilot backend"));
    }

    @Test
    void buildPreparationPromptIncludesProjectPreparationInstructions() {
        Path projectRoot = tempDir.resolve("sample");

        String prompt = promptTemplateService.buildPreparationPrompt(projectRoot);

        assertTrue(prompt.contains("Prepare this Java repository so subsequent test generation can succeed reliably."));
        assertTrue(prompt.contains("Project root: `" + projectRoot + "`"));
        assertTrue(prompt.contains("Run the relevant build, test, and JaCoCo coverage flow yourself."));
        assertTrue(prompt.contains("Update `.jaipilot/project-memory.md` with the exact verified build, test, and coverage commands"));
        assertTrue(prompt.contains("Do not stop until the repository is in a working state"));
        assertFalse(prompt.contains("Supabase"));
        assertFalse(prompt.contains("JAIPilot backend"));
    }

    @Test
    void buildBatchValidationPromptRequiresCleanDiscoveredTestsAndFreshCoverage() {
        Path projectRoot = tempDir.resolve("sample");
        Path generatedTest = projectRoot.resolve("src/test/java/com/example/OrderServiceTests.java");

        String prompt = promptTemplateService.buildBatchValidationPrompt(projectRoot, java.util.List.of(generatedTest));

        assertTrue(prompt.contains("Validate the merged Java tests"));
        assertTrue(prompt.contains("Project root: `" + projectRoot + "`"));
        assertTrue(prompt.contains("clean full test suite"));
        assertTrue(prompt.contains("actually discovers and executes the generated tests"));
        assertTrue(prompt.contains("freshly generated JaCoCo XML report"));
        assertTrue(prompt.contains("Do not modify production code."));
        assertTrue(prompt.contains("- " + generatedTest));
        assertTrue(prompt.contains("Do not edit any path outside the generated test file allowlist"));
    }
}
