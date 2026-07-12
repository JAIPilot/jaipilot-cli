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
        assertTrue(prompt.contains("isolated sandbox workspace in parallel"));
        assertTrue(prompt.contains("- After editing, stop and let JAIPilot run validation."));
        assertFalse(prompt.contains("package com.example; class OrderService {}"));
        assertFalse(prompt.contains("Target test class:"));
        assertFalse(prompt.contains("com.example.OrderServiceTest"));
        assertFalse(prompt.contains("Supabase"));
        assertFalse(prompt.contains("JAIPilot backend"));
    }
}
