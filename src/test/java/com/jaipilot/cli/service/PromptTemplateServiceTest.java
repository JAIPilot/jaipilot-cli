package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptTemplateServiceTest {

    private final ProjectFileService fileService = new ProjectFileService();
    private final PromptTemplateService promptTemplateService = new PromptTemplateService(fileService);

    @TempDir
    Path tempDir;

    @Test
    void buildInitialPromptLoadsMarkdownTemplateAndRendersValues() throws Exception {
        Path projectRoot = tempDir.resolve("sample");
        Path sourcePath = projectRoot.resolve("src/main/java/com/example/OrderService.java");
        Path testPath = projectRoot.resolve("src/test/java/com/example/OrderServiceTest.java");
        Files.createDirectories(sourcePath.getParent());
        Files.createDirectories(testPath.getParent());
        Files.writeString(sourcePath, "package com.example; class OrderService {}");
        Files.writeString(testPath, "package com.example; class OrderServiceTest {}");

        JavaProjectService.JavaClassDescriptor descriptor = new JavaProjectService.JavaClassDescriptor(
                projectRoot,
                projectRoot,
                sourcePath,
                "com.example",
                "OrderService",
                "com.example.OrderService",
                testPath,
                "OrderServiceTest",
                "com.example.OrderServiceTest"
        );

        String prompt = promptTemplateService.buildInitialPrompt(descriptor);

        assertTrue(prompt.contains("Generate or update JUnit tests for one Java production class."));
        assertTrue(prompt.contains(projectRoot.toString()));
        assertTrue(prompt.contains(sourcePath.toString()));
        assertTrue(prompt.contains(testPath.toString()));
        assertTrue(prompt.contains("package com.example; class OrderService {}"));
        assertFalse(prompt.contains("Supabase"));
        assertFalse(prompt.contains("JAIPilot backend"));
    }
}
