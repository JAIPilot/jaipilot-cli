package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCliUnitTestGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void buildToolCacheEnvironmentAddsSandboxFriendlyDefaults() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(tempDir, Map.of());

        assertEquals(
                "-Daether.enhancedLocalRepository.trackingFilename=ignore",
                environment.get("MAVEN_OPTS")
        );
        assertEquals(
                tempDir.resolve(".gradle/jaipilot").normalize().toString(),
                environment.get("GRADLE_USER_HOME")
        );
    }

    @Test
    void buildToolCacheEnvironmentPreservesExistingMavenOptions() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(
                tempDir,
                Map.of("MAVEN_OPTS", "-Xmx2g")
        );

        assertEquals(
                "-Xmx2g -Daether.enhancedLocalRepository.trackingFilename=ignore",
                environment.get("MAVEN_OPTS")
        );
    }

    @Test
    void buildToolCacheEnvironmentDoesNotOverrideExplicitMavenTrackingOption() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(
                tempDir,
                Map.of("MAVEN_OPTS", "-Daether.enhancedLocalRepository.trackingFilename=custom")
        );

        assertFalse(environment.containsKey("MAVEN_OPTS"));
    }

    @Test
    void buildToolCacheEnvironmentDoesNotOverrideExplicitGradleHome() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(
                tempDir,
                Map.of("GRADLE_USER_HOME", "/custom/gradle")
        );

        assertFalse(environment.containsKey("GRADLE_USER_HOME"));
    }

    @Test
    void totalTokensDoesNotDoubleCountCachedOrReasoningSubsets() {
        CodexCliUnitTestGenerator.AgentUsage usage = new CodexCliUnitTestGenerator.AgentUsage(
                1_000L,
                800L,
                200L,
                125L
        );

        assertEquals(1_200L, usage.totalTokens());
    }

    @Test
    void invalidatingCoverageRemovesTheDiscoveredReport() throws Exception {
        Path firstReport = tempDir.resolve("target/site/jacoco/jacoco.xml");
        Path secondReport = tempDir.resolve("module/target/site/jacoco-it/jacoco.xml");
        Files.createDirectories(firstReport.getParent());
        Files.createDirectories(secondReport.getParent());
        Files.writeString(firstReport, "<report/>");
        Files.writeString(secondReport, "<report/>");
        ProjectFileService fileService = new ProjectFileService();
        CodexCliUnitTestGenerator generator = new CodexCliUnitTestGenerator(
                fileService,
                new JavaProjectService(fileService, new CoverageReportService())
        );

        generator.invalidateCoverageReport(tempDir);

        assertFalse(Files.exists(firstReport));
        assertFalse(Files.exists(secondReport));
    }

    @Test
    void failureSummaryRendersErrorsWithoutRawJson() {
        ProjectFileService fileService = new ProjectFileService();
        CodexCliUnitTestGenerator generator = new CodexCliUnitTestGenerator(
                fileService,
                new JavaProjectService(fileService, new CoverageReportService())
        );
        TerminalUi ui = new TerminalUi(new PrintWriter(new StringWriter(), true));
        String output = """
                {"type":"thread.started","thread_id":"abc"}
                {"type":"item.completed","item":{"type":"command_execution","command":"./mvnw test","status":"failed","exit_code":1}}
                {"type":"turn.failed","message":"Tests failed"}
                """;

        String summary = generator.summarizeFailure(output, ui);

        assertTrue(summary.contains("[shell failed] ./mvnw test (exit 1)"));
        assertTrue(summary.contains("[codex error] Tests failed"));
        assertFalse(summary.contains("{\"type\""));
    }

    @Test
    void missingTestReportsIdentifiesGeneratedTestsSkippedByTheBuild() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path executedTest = tempDir.resolve("src/test/java/com/example/ExecutedTests.java");
        Path skippedTest = tempDir.resolve("src/test/java/com/example/SkippedTests.java");
        Files.createDirectories(executedTest.getParent());
        Files.writeString(executedTest, "package com.example; class ExecutedTests {}\n");
        Files.writeString(skippedTest, "package com.example; class SkippedTests {}\n");
        Path report = tempDir.resolve("target/surefire-reports/TEST-com.example.ExecutedTests.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "<testsuite tests=\"1\"/>");
        Files.writeString(
                report.resolveSibling("TEST-com.example.SkippedTests.xml"),
                "<testsuite tests=\"0\"/>"
        );
        Path gradleBinaryResults = tempDir.resolve("build/test-results/test/binary/output.bin");
        Files.createDirectories(gradleBinaryResults.getParent());
        Files.write(gradleBinaryResults, new byte[] {(byte) 0xc3, (byte) 0x28});
        ProjectFileService fileService = new ProjectFileService();
        CodexCliUnitTestGenerator generator = new CodexCliUnitTestGenerator(
                fileService,
                new JavaProjectService(fileService, new CoverageReportService())
        );

        List<String> missing = generator.findMissingTestReports(tempDir, List.of(executedTest, skippedTest));

        assertEquals(List.of("com.example.SkippedTests"), missing);
    }

    @Test
    void codexCommandDisablesNestedAgentsInsideParallelWorkers() {
        ProjectFileService fileService = new ProjectFileService();
        CodexCliUnitTestGenerator generator = new CodexCliUnitTestGenerator(
                fileService,
                new JavaProjectService(fileService, new CoverageReportService())
        );

        List<String> command = generator.buildCodexCommand("gpt-test");

        assertTrue(command.contains("features.multi_agent=false"));
        assertTrue(command.contains("hide_agent_reasoning=true"));
        assertEquals("gpt-test", command.get(command.indexOf("-m") + 1));
    }

    @Test
    void usageLimitFailuresAreNotRetried() {
        ProjectFileService fileService = new ProjectFileService();
        CodexCliUnitTestGenerator generator = new CodexCliUnitTestGenerator(
                fileService,
                new JavaProjectService(fileService, new CoverageReportService())
        );

        assertTrue(generator.isNonRetryableCodexFailure(
                new IllegalStateException("Codex failed", new RuntimeException("You've hit your usage limit."))
        ));
        assertFalse(generator.isNonRetryableCodexFailure(new RuntimeException("temporary network failure")));
    }

    @Test
    void validationScopeAllowsGeneratedTestsButRejectsOtherJavaChanges() {
        Path generatedTest = tempDir.resolve("src/test/java/com/example/GeneratedTests.java");
        Path production = tempDir.resolve("src/main/java/com/example/Production.java");
        Path unrelatedTest = tempDir.resolve("src/test/java/com/example/ExistingTests.java");
        ProjectFileService.FileFingerprint original = new ProjectFileService.FileFingerprint(1L, "before");
        ProjectFileService.FileFingerprint changed = new ProjectFileService.FileFingerprint(2L, "after");

        List<Path> unexpected = CodexCliUnitTestGenerator.findUnexpectedJavaChanges(
                Map.of(generatedTest, original, production, original, unrelatedTest, original),
                Map.of(generatedTest, changed, production, changed, unrelatedTest, changed),
                java.util.Set.of(generatedTest)
        );

        assertEquals(List.of(production, unrelatedTest), unexpected);
    }
}
