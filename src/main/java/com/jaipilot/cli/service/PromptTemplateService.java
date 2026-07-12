package com.jaipilot.cli.service;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PromptTemplateService {

    private static final String INITIAL_TEMPLATE_PATH = "prompts/generate-java-tests.md";

    private final ProjectFileService fileService;
    private final String initialTemplate;

    public PromptTemplateService(ProjectFileService fileService) {
        this.fileService = Objects.requireNonNull(fileService, "fileService");
        this.initialTemplate = fileService.readResource(INITIAL_TEMPLATE_PATH);
    }

    public String buildInitialPrompt(JavaProjectService.JavaClassDescriptor descriptor) {
        String existingTest = Files.isRegularFile(descriptor.testPath())
                ? fileService.readFile(descriptor.testPath())
                : "";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PROJECT_ROOT", descriptor.projectRoot().toString());
        values.put("MODULE_ROOT", descriptor.moduleRoot().toString());
        values.put("CLASS_UNDER_TEST", descriptor.fullyQualifiedName());
        values.put("CLASS_FILE", descriptor.cutPath().toString());
        values.put("TARGET_TEST_FILE", descriptor.testPath().toString());
        values.put("TARGET_TEST_CLASS", descriptor.testFullyQualifiedName());
        values.put("SOURCE_CONTENT", fileService.readFile(descriptor.cutPath()));
        values.put("EXISTING_TEST_CONTENT", existingTest);
        return render(initialTemplate, values);
    }

    private String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }
}
