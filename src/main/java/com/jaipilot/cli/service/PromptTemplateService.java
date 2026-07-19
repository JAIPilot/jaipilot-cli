package com.jaipilot.cli.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PromptTemplateService {

    private static final String INITIAL_TEMPLATE_PATH = "prompts/generate-java-tests.md";
    private static final String PREPARATION_TEMPLATE_PATH = "prompts/prepare-java-project.md";
    private static final String BATCH_VALIDATION_TEMPLATE_PATH = "prompts/validate-java-test-batch.md";

    private final ProjectFileService fileService;
    private final String initialTemplate;
    private final String preparationTemplate;
    private final String batchValidationTemplate;

    public PromptTemplateService(ProjectFileService fileService) {
        this.fileService = Objects.requireNonNull(fileService, "fileService");
        this.initialTemplate = fileService.readResource(INITIAL_TEMPLATE_PATH);
        this.preparationTemplate = fileService.readResource(PREPARATION_TEMPLATE_PATH);
        this.batchValidationTemplate = fileService.readResource(BATCH_VALIDATION_TEMPLATE_PATH);
    }

    public String buildInitialPrompt(JavaProjectService.JavaClassDescriptor descriptor) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfReferenced(values, "PROJECT_ROOT", descriptor.projectRoot().toString());
        putIfReferenced(values, "MODULE_ROOT", descriptor.moduleRoot().toString());
        putIfReferenced(values, "CLASS_UNDER_TEST", descriptor.fullyQualifiedName());
        putIfReferenced(values, "CLASS_FILE", descriptor.cutPath().toString());
        putIfReferenced(values, "SOURCE_CONTENT", () -> fileService.readFile(descriptor.cutPath()));
        return render(initialTemplate, values);
    }

    public String buildPreparationPrompt(java.nio.file.Path projectRoot) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfReferenced(preparationTemplate, values, "PROJECT_ROOT", projectRoot.toString());
        return render(preparationTemplate, values);
    }

    public String buildBatchValidationPrompt(java.nio.file.Path projectRoot, List<java.nio.file.Path> generatedTests) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfReferenced(batchValidationTemplate, values, "PROJECT_ROOT", projectRoot.toString());
        putIfReferenced(
                batchValidationTemplate,
                values,
                "GENERATED_TEST_PATHS",
                generatedTests.stream()
                        .map(path -> "- " + path)
                        .reduce((left, right) -> left + System.lineSeparator() + right)
                        .orElse("- None")
        );
        return render(batchValidationTemplate, values);
    }

    private void putIfReferenced(Map<String, String> values, String key, String value) {
        if (initialTemplate.contains(placeholder(key))) {
            values.put(key, value);
        }
    }

    private void putIfReferenced(Map<String, String> values, String key, java.util.function.Supplier<String> valueSupplier) {
        if (initialTemplate.contains(placeholder(key))) {
            values.put(key, valueSupplier.get());
        }
    }

    private void putIfReferenced(String template, Map<String, String> values, String key, String value) {
        if (template.contains(placeholder(key))) {
            values.put(key, value);
        }
    }

    private String placeholder(String key) {
        return "{{" + key + "}}";
    }

    private String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }
}
