package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveShellCompleterTest {

    @TempDir
    Path tempDir;

    @Test
    void topLevelCompletionSuggestsShellCommands() throws Exception {
        InteractiveShellCompleter completer = completerForSampleProject();

        List<String> values = candidateValues(completer.suggest("/g", 2));

        assertTrue(values.contains("/generate"));
        assertTrue(values.contains("/status"));
    }

    @Test
    void generateCompletionSuggestsOptionsAndClassSelectors() throws Exception {
        InteractiveShellCompleter completer = completerForSampleProject();

        List<String> values = candidateValues(completer.suggest("/generate ", 10));

        assertTrue(values.contains("--coverage-below"));
        assertTrue(values.contains("--agent"));
        assertTrue(values.contains("all"));
        assertTrue(values.contains("OrderService"));
        assertTrue(values.contains("OwnerService"));
    }

    @Test
    void generateCompletionSuggestsClassesAfterAgentOption() throws Exception {
        InteractiveShellCompleter completer = completerForSampleProject();

        List<String> values = candidateValues(completer.suggest("/generate --agent codex ", 24));

        assertTrue(values.contains("OrderService"));
        assertTrue(values.contains("OwnerService"));
    }

    @Test
    void generateCompletionSuggestsFqcnAndNaturalLanguageModesContextually() throws Exception {
        InteractiveShellCompleter completer = completerForSampleProject();

        List<String> fqcnValues = candidateValues(completer.suggest("/generate com.example.O", 23));
        List<String> allModeValues = candidateValues(completer.suggest("/generate all ", 14));

        assertTrue(fqcnValues.contains("com.example.OrderService"));
        assertTrue(allModeValues.contains("changed"));
        assertTrue(allModeValues.contains("coverage"));
        assertTrue(allModeValues.contains("for"));
    }

    @Test
    void thresholdCompletionSuggestsCommonValues() throws Exception {
        InteractiveShellCompleter completer = completerForSampleProject();

        List<String> generateThresholds = candidateValues(completer.suggest("/generate --coverage-below ", 28));
        List<String> statusThresholds = candidateValues(completer.suggest("/status --threshold ", 20));

        assertTrue(generateThresholds.contains("80"));
        assertTrue(generateThresholds.contains("90"));
        assertTrue(statusThresholds.contains("80"));
    }

    private InteractiveShellCompleter completerForSampleProject() throws Exception {
        Path projectRoot = tempDir.resolve("sample");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        writeJava(projectRoot.resolve("src/main/java/com/example/OrderService.java"), "OrderService");
        writeJava(projectRoot.resolve("src/main/java/com/example/OwnerService.java"), "OwnerService");
        writeJava(projectRoot.resolve("src/main/java/com/example/web/VisitController.java"), "VisitController");

        JavaProjectService projectService = new JavaProjectService(new ProjectFileService(), new CoverageReportService());
        return new InteractiveShellCompleter(projectService, projectRoot);
    }

    private List<String> candidateValues(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::value).toList();
    }

    private void writeJava(Path path, String className) throws Exception {
        Files.createDirectories(path.getParent());
        String packageName = path.toString().contains("/web/") ? "com.example.web" : "com.example";
        Files.writeString(path, """
                package %s;

                class %s {
                }
                """.formatted(packageName, className));
    }
}
