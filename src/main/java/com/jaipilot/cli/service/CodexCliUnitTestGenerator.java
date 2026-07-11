package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CodexCliUnitTestGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration CODEX_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration VALIDATION_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COVERAGE_TIMEOUT = Duration.ofMinutes(15);
    private static final int MAX_FIX_ATTEMPTS = 1;
    private static final String INPUT_COST_ENV = "JAIPILOT_CODEX_INPUT_COST_PER_MILLION_USD";
    private static final String CACHED_INPUT_COST_ENV = "JAIPILOT_CODEX_CACHED_INPUT_COST_PER_MILLION_USD";
    private static final String OUTPUT_COST_ENV = "JAIPILOT_CODEX_OUTPUT_COST_PER_MILLION_USD";
    private static final String REASONING_OUTPUT_COST_ENV = "JAIPILOT_CODEX_REASONING_OUTPUT_COST_PER_MILLION_USD";
    private static final String INPUT_COST_PROPERTY = "jaipilot.codex.inputCostPerMillionUsd";
    private static final String CACHED_INPUT_COST_PROPERTY = "jaipilot.codex.cachedInputCostPerMillionUsd";
    private static final String OUTPUT_COST_PROPERTY = "jaipilot.codex.outputCostPerMillionUsd";
    private static final String REASONING_OUTPUT_COST_PROPERTY = "jaipilot.codex.reasoningOutputCostPerMillionUsd";

    private final ProjectFileService fileService;
    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final ProcessExecutor processExecutor;
    private final PricingConfiguration pricingConfiguration;

    public CodexCliUnitTestGenerator(ProjectFileService fileService, JavaProjectService projectService) {
        this(
                fileService,
                projectService,
                new CoverageReportService(),
                new ProcessExecutor(),
                PricingConfiguration.fromEnvironment()
        );
    }

    CodexCliUnitTestGenerator(
            ProjectFileService fileService,
            JavaProjectService projectService,
            CoverageReportService coverageReportService,
            ProcessExecutor processExecutor,
            PricingConfiguration pricingConfiguration
    ) {
        this.fileService = fileService;
        this.projectService = projectService;
        this.coverageReportService = coverageReportService;
        this.processExecutor = processExecutor;
        this.pricingConfiguration = pricingConfiguration;
    }

    public Optional<String> codexVersion(Path workingDirectory) {
        try {
            ProcessExecutor.ExecutionResult result = processExecutor.execute(
                    List.of("codex", "--version"),
                    workingDirectory,
                    Duration.ofSeconds(15),
                    false,
                    new PrintWriter(System.err, true)
            );
            if (result.exitCode() == 0) {
                return Optional.of(result.output().trim());
            }
        } catch (Exception ignored) {
            // Ignore and report absence.
        }
        return Optional.empty();
    }

    public GenerationResult generate(
            JavaProjectService.JavaClassDescriptor descriptor,
            String model,
            PrintWriter out
    ) throws Exception {
        ensureCodexAvailable(descriptor.projectRoot());
        boolean testExistedBefore = Files.isRegularFile(descriptor.testPath());
        CoverageReportService.CoverageSnapshot beforeCoverage = projectService.buildCoverageCommand(descriptor).isPresent()
                ? coverageReportService.readProjectSnapshot(descriptor.moduleRoot()).orElse(null)
                : null;

        AgentUsage initialUsage = runCodex(descriptor, model, buildInitialPrompt(descriptor), out);
        if (!Files.isRegularFile(descriptor.testPath())) {
            throw new IllegalStateException("Expected generated test file was not created: " + descriptor.testPath());
        }

        ProcessExecutor.ExecutionResult validationResult = validate(descriptor, out);
        AgentUsage repairUsage = AgentUsage.zero();
        for (int attempt = 1; validationResult.exitCode() != 0 && attempt <= MAX_FIX_ATTEMPTS; attempt++) {
            out.printf("Validation failed for %s. Asking Codex to repair the generated test.%n", descriptor.className());
            repairUsage = repairUsage.plus(runCodex(descriptor, model, buildRepairPrompt(descriptor, validationResult.output()), out));
            validationResult = validate(descriptor, out);
        }

        if (validationResult.exitCode() != 0) {
            throw new IllegalStateException(
                    "Validation failed for " + descriptor.testFullyQualifiedName() + ":\n" + validationResult.output()
            );
        }

        CoverageDelta coverageDelta = captureCoverageDelta(descriptor, beforeCoverage, out);
        AgentUsage totalUsage = initialUsage.plus(repairUsage);
        CostEstimate estimatedCost = pricingConfiguration.estimate(totalUsage);
        out.printf(
                "Agent summary for %s: input=%d cached=%d output=%d reasoning=%d total=%d estimated-cost=%s%n",
                descriptor.className(),
                totalUsage.inputTokens(),
                totalUsage.cachedInputTokens(),
                totalUsage.outputTokens(),
                totalUsage.reasoningOutputTokens(),
                totalUsage.totalTokens(),
                estimatedCost.display()
        );
        return new GenerationResult(
                descriptor.testPath(),
                validationResult.output(),
                totalUsage,
                coverageDelta,
                estimatedCost,
                testExistedBefore
        );
    }

    private void ensureCodexAvailable(Path workingDirectory) {
        if (codexVersion(workingDirectory).isEmpty()) {
            throw new IllegalStateException("Codex CLI is not installed or not available on PATH.");
        }
    }

    private AgentUsage runCodex(
            JavaProjectService.JavaClassDescriptor descriptor,
            String model,
            String prompt,
            PrintWriter out
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("codex");
        command.add("-a");
        command.add("never");
        command.add("-s");
        command.add("workspace-write");
        if (model != null && !model.isBlank()) {
            command.add("-m");
            command.add(model.trim());
        }
        command.add("exec");
        command.add("--json");
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");
        command.add("-");

        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                command,
                descriptor.projectRoot(),
                CODEX_TIMEOUT,
                true,
                out,
                prompt
        );
        if (result.timedOut()) {
            throw new IllegalStateException("Codex timed out while generating tests for " + descriptor.className() + ".");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "Codex failed while generating tests for " + descriptor.className() + ":\n" + result.output()
            );
        }
        AgentUsage usage = parseUsage(result.output());
        out.printf(
                "Codex usage for %s: input=%d cached=%d output=%d reasoning=%d total=%d%n",
                descriptor.className(),
                usage.inputTokens(),
                usage.cachedInputTokens(),
                usage.outputTokens(),
                usage.reasoningOutputTokens(),
                usage.totalTokens()
        );
        return usage;
    }

    private ProcessExecutor.ExecutionResult validate(
            JavaProjectService.JavaClassDescriptor descriptor,
            PrintWriter out
    ) throws Exception {
        List<String> command = projectService.buildValidationCommand(descriptor);
        out.printf("Running validation: %s%n", String.join(" ", command));
        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                command,
                descriptor.moduleRoot(),
                VALIDATION_TIMEOUT,
                true,
                out
        );
        if (result.timedOut()) {
            throw new IllegalStateException(
                    "Validation timed out for " + descriptor.testFullyQualifiedName() + "."
            );
        }
        return result;
    }

    private CoverageDelta captureCoverageDelta(
            JavaProjectService.JavaClassDescriptor descriptor,
            CoverageReportService.CoverageSnapshot beforeCoverage,
            PrintWriter out
    ) throws Exception {
        Optional<List<String>> coverageCommand = projectService.buildCoverageCommand(descriptor);
        if (coverageCommand.isEmpty()) {
            return CoverageDelta.unavailable("JaCoCo task was not detected in the build.");
        }
        out.printf("Running JaCoCo coverage: %s%n", String.join(" ", coverageCommand.get()));
        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                coverageCommand.get(),
                descriptor.moduleRoot(),
                COVERAGE_TIMEOUT,
                true,
                out
        );
        if (result.timedOut()) {
            return CoverageDelta.unavailable("JaCoCo coverage command timed out.");
        }
        if (result.exitCode() != 0) {
            return CoverageDelta.unavailable("JaCoCo coverage command failed.");
        }
        CoverageReportService.CoverageSnapshot afterCoverage = coverageReportService.readProjectSnapshot(descriptor.moduleRoot())
                .orElse(null);
        if (afterCoverage == null) {
            return CoverageDelta.unavailable("Coverage snapshot unavailable after JaCoCo run.");
        }
        CoverageReportService.ClassCoverage beforeClass = classCoverageOrZero(beforeCoverage, descriptor);
        CoverageReportService.ClassCoverage afterClass = classCoverageOrZero(afterCoverage, descriptor);
        if (beforeClass == null) {
            out.printf(
                    "Coverage for %s: line %.1f%%, branch %.1f%% (baseline unavailable)%n",
                    descriptor.className(),
                    afterClass.lineCoverage(),
                    afterClass.branchCoverage()
            );
        } else {
            out.printf(
                    "Coverage delta for %s: line %.1f%% -> %.1f%%, branch %.1f%% -> %.1f%%%n",
                    descriptor.className(),
                    beforeClass.lineCoverage(),
                    afterClass.lineCoverage(),
                    beforeClass.branchCoverage(),
                    afterClass.branchCoverage()
            );
        }
        return new CoverageDelta(beforeCoverage, afterCoverage, beforeClass, afterClass, null);
    }

    private AgentUsage parseUsage(String output) {
        AgentUsage usage = AgentUsage.zero();
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = OBJECT_MAPPER.readTree(trimmed);
                if (!"turn.completed".equals(node.path("type").asText())) {
                    continue;
                }
                JsonNode usageNode = node.path("usage");
                usage = new AgentUsage(
                        usageNode.path("input_tokens").asLong(0L),
                        usageNode.path("cached_input_tokens").asLong(0L),
                        usageNode.path("output_tokens").asLong(0L),
                        usageNode.path("reasoning_output_tokens").asLong(0L)
                );
            } catch (Exception ignored) {
                // Ignore non-JSON or unexpected lines in mixed output.
            }
        }
        return usage;
    }

    private String buildInitialPrompt(JavaProjectService.JavaClassDescriptor descriptor) {
        String sourceContent = fileService.readFile(descriptor.cutPath());
        String existingTest = Files.isRegularFile(descriptor.testPath())
                ? fileService.readFile(descriptor.testPath())
                : "";
        return """
                Generate or update JUnit tests for one Java production class.

                Before editing, read these files if they exist:
                - AGENTS.md
                - .jaipilot/project-memory.md
                - .agents/skills/jaipilot-generate/SKILL.md

                Rules:
                - Do not call any JAIPilot, Supabase, or custom backend endpoint.
                - Use only local repository files and locally installed tools.
                - Do not modify production code.
                - Prefer JUnit 5 and follow existing project conventions.
                - Improve JaCoCo line and branch coverage for the class under test.
                - Keep tests deterministic and avoid flaky timing/network behavior.
                - Write the test file at the exact target path below.
                - After editing, stop and let JAIPilot run validation.

                Project root: %s
                Module root: %s
                Class under test: %s
                Class file: %s
                Target test file: %s
                Target test class: %s

                Source file content:
                ```java
                %s
                ```

                Existing test content:
                ```java
                %s
                ```
                """.formatted(
                descriptor.projectRoot(),
                descriptor.moduleRoot(),
                descriptor.fullyQualifiedName(),
                descriptor.cutPath(),
                descriptor.testPath(),
                descriptor.testFullyQualifiedName(),
                sourceContent,
                existingTest
        );
    }

    private String buildRepairPrompt(
            JavaProjectService.JavaClassDescriptor descriptor,
            String validationOutput
    ) {
        String currentTest = fileService.readFile(descriptor.testPath());
        return """
                Repair the generated JUnit test for this Java class.

                Rules:
                - Do not modify production code.
                - Only edit the test file at the target path.
                - Fix the test so it passes the project's local build command.

                Class under test: %s
                Target test file: %s

                Current test content:
                ```java
                %s
                ```

                Validation output:
                ```text
                %s
                ```
                """.formatted(
                descriptor.fullyQualifiedName(),
                descriptor.testPath(),
                currentTest,
                validationOutput
        );
    }

    public record GenerationResult(
            Path outputPath,
            String validationOutput,
            AgentUsage usage,
            CoverageDelta coverageDelta,
            CostEstimate estimatedCost,
            boolean testExistedBefore
    ) {
    }

    public record AgentUsage(
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long reasoningOutputTokens
    ) {
        public static AgentUsage zero() {
            return new AgentUsage(0L, 0L, 0L, 0L);
        }

        public AgentUsage plus(AgentUsage other) {
            return new AgentUsage(
                    inputTokens + other.inputTokens,
                    cachedInputTokens + other.cachedInputTokens,
                    outputTokens + other.outputTokens,
                    reasoningOutputTokens + other.reasoningOutputTokens
            );
        }

        public long totalTokens() {
            return inputTokens + cachedInputTokens + outputTokens + reasoningOutputTokens;
        }
    }

    public record CoverageDelta(
            CoverageReportService.CoverageSnapshot beforeProjectSnapshot,
            CoverageReportService.CoverageSnapshot afterProjectSnapshot,
            CoverageReportService.ClassCoverage beforeClassCoverage,
            CoverageReportService.ClassCoverage afterClassCoverage,
            String unavailableReason
    ) {
        static CoverageDelta unavailable(String reason) {
            return new CoverageDelta(null, null, null, null, reason);
        }

        public boolean available() {
            return afterClassCoverage != null;
        }
    }

    public record CostEstimate(
            Double usd,
            String status
    ) {
        static CostEstimate available(double usd) {
            return new CostEstimate(usd, null);
        }

        static CostEstimate unavailable(String status) {
            return new CostEstimate(null, status);
        }

        public boolean available() {
            return usd != null;
        }

        public String display() {
            if (!available()) {
                return status;
            }
            return String.format(Locale.ROOT, "$%.4f", usd);
        }
    }

    record PricingConfiguration(
            boolean configured,
            double inputCostPerMillionUsd,
            double cachedInputCostPerMillionUsd,
            double outputCostPerMillionUsd,
            double reasoningOutputCostPerMillionUsd,
            String unavailableReason
    ) {
        static PricingConfiguration fromEnvironment() {
            Double input = readDouble(INPUT_COST_ENV, INPUT_COST_PROPERTY);
            Double cachedInput = readDouble(CACHED_INPUT_COST_ENV, CACHED_INPUT_COST_PROPERTY);
            Double output = readDouble(OUTPUT_COST_ENV, OUTPUT_COST_PROPERTY);
            Double reasoningOutput = readDouble(REASONING_OUTPUT_COST_ENV, REASONING_OUTPUT_COST_PROPERTY);
            if (input == null || output == null) {
                return new PricingConfiguration(
                        false,
                        0.0d,
                        0.0d,
                        0.0d,
                        0.0d,
                        "unavailable (set " + INPUT_COST_ENV + " and " + OUTPUT_COST_ENV + " to enable)"
                );
            }
            return new PricingConfiguration(
                    true,
                    input,
                    cachedInput == null ? input : cachedInput,
                    output,
                    reasoningOutput == null ? output : reasoningOutput,
                    null
            );
        }

        CostEstimate estimate(AgentUsage usage) {
            if (!configured) {
                return CostEstimate.unavailable(unavailableReason);
            }
            double usd = (usage.inputTokens() * inputCostPerMillionUsd
                    + usage.cachedInputTokens() * cachedInputCostPerMillionUsd
                    + usage.outputTokens() * outputCostPerMillionUsd
                    + usage.reasoningOutputTokens() * reasoningOutputCostPerMillionUsd) / 1_000_000.0d;
            return CostEstimate.available(usd);
        }

        private static Double readDouble(String environmentKey, String propertyKey) {
            String rawValue = System.getenv(environmentKey);
            if (rawValue == null || rawValue.isBlank()) {
                rawValue = System.getProperty(propertyKey);
            }
            if (rawValue == null || rawValue.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(rawValue.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(
                        "Invalid pricing value for " + environmentKey + " / " + propertyKey + ": " + rawValue,
                        exception
                );
            }
        }
    }

    private CoverageReportService.ClassCoverage classCoverageOrZero(
            CoverageReportService.CoverageSnapshot snapshot,
            JavaProjectService.JavaClassDescriptor descriptor
    ) {
        if (snapshot == null) {
            return null;
        }
        return snapshot.classCoverageByName().getOrDefault(
                descriptor.fullyQualifiedName(),
                new CoverageReportService.ClassCoverage(descriptor.fullyQualifiedName(), 0.0d, 0.0d)
        );
    }
}
