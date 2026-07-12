package com.jaipilot.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.ui.TerminalUi;
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
    private static final String INPUT_COST_ENV = "JAIPILOT_CODEX_INPUT_COST_PER_MILLION_USD";
    private static final String CACHED_INPUT_COST_ENV = "JAIPILOT_CODEX_CACHED_INPUT_COST_PER_MILLION_USD";
    private static final String OUTPUT_COST_ENV = "JAIPILOT_CODEX_OUTPUT_COST_PER_MILLION_USD";
    private static final String REASONING_OUTPUT_COST_ENV = "JAIPILOT_CODEX_REASONING_OUTPUT_COST_PER_MILLION_USD";
    private static final String INPUT_COST_PROPERTY = "jaipilot.codex.inputCostPerMillionUsd";
    private static final String CACHED_INPUT_COST_PROPERTY = "jaipilot.codex.cachedInputCostPerMillionUsd";
    private static final String OUTPUT_COST_PROPERTY = "jaipilot.codex.outputCostPerMillionUsd";
    private static final String REASONING_OUTPUT_COST_PROPERTY = "jaipilot.codex.reasoningOutputCostPerMillionUsd";

    private final JavaProjectService projectService;
    private final CoverageReportService coverageReportService;
    private final ProcessExecutor processExecutor;
    private final PricingConfiguration pricingConfiguration;
    private final PromptTemplateService promptTemplateService;

    public CodexCliUnitTestGenerator(ProjectFileService fileService, JavaProjectService projectService) {
        this(
                fileService,
                projectService,
                new CoverageReportService(),
                new ProcessExecutor(),
                PricingConfiguration.fromEnvironment(),
                new PromptTemplateService(fileService)
        );
    }

    CodexCliUnitTestGenerator(
            ProjectFileService fileService,
            JavaProjectService projectService,
            CoverageReportService coverageReportService,
            ProcessExecutor processExecutor,
            PricingConfiguration pricingConfiguration,
            PromptTemplateService promptTemplateService
    ) {
        this.projectService = projectService;
        this.coverageReportService = coverageReportService;
        this.processExecutor = processExecutor;
        this.pricingConfiguration = pricingConfiguration;
        this.promptTemplateService = promptTemplateService;
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
            TerminalUi ui
    ) throws Exception {
        ensureCodexAvailable(descriptor.projectRoot());
        boolean testExistedBefore = Files.isRegularFile(descriptor.testPath());
        CoverageReportService.CoverageSnapshot beforeCoverage = projectService.buildCoverageCommand(descriptor).isPresent()
                ? coverageReportService.readProjectSnapshot(descriptor.moduleRoot()).orElse(null)
                : null;

        AgentUsage initialUsage = runCodex(descriptor, model, promptTemplateService.buildInitialPrompt(descriptor), ui);
        if (!Files.isRegularFile(descriptor.testPath())) {
            throw new IllegalStateException("Expected generated test file was not created: " + descriptor.testPath());
        }

        ProcessExecutor.ExecutionResult validationResult = validate(descriptor, ui);
        if (validationResult.exitCode() != 0) {
            throw new IllegalStateException(
                    "Validation failed for " + descriptor.testFullyQualifiedName() + ":\n" + tail(validationResult.output())
            );
        }

        CoverageDelta coverageDelta = captureCoverageDelta(descriptor, beforeCoverage, ui);
        CostEstimate estimatedCost = pricingConfiguration.estimate(initialUsage);
        return new GenerationResult(
                descriptor.testPath(),
                validationResult.output(),
                initialUsage,
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
            TerminalUi ui
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

        TerminalUi.Spinner spinner = ui.spinner("codex generating " + descriptor.testClassName());
        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                command,
                descriptor.projectRoot(),
                CODEX_TIMEOUT,
                false,
                new PrintWriter(System.err, true),
                prompt,
                spinner
        );
        if (result.timedOut()) {
            throw new IllegalStateException("Codex timed out while generating tests for " + descriptor.className() + ".");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "Codex failed while generating tests for " + descriptor.className() + ":\n" + tail(result.output())
            );
        }
        return parseUsage(result.output());
    }

    private ProcessExecutor.ExecutionResult validate(
            JavaProjectService.JavaClassDescriptor descriptor,
            TerminalUi ui
    ) throws Exception {
        List<String> command = projectService.buildValidationCommand(descriptor);
        TerminalUi.Spinner spinner = ui.spinner("validating " + descriptor.testClassName());
        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                command,
                descriptor.moduleRoot(),
                VALIDATION_TIMEOUT,
                false,
                new PrintWriter(System.err, true),
                null,
                spinner
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
            TerminalUi ui
    ) throws Exception {
        Optional<List<String>> coverageCommand = projectService.buildCoverageCommand(descriptor);
        if (coverageCommand.isEmpty()) {
            return CoverageDelta.unavailable("JaCoCo task was not detected in the build.");
        }
        TerminalUi.Spinner spinner = ui.spinner("running JaCoCo for " + descriptor.className());
        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                coverageCommand.get(),
                descriptor.moduleRoot(),
                COVERAGE_TIMEOUT,
                false,
                new PrintWriter(System.err, true),
                null,
                spinner
        );
        if (result.timedOut()) {
            return CoverageDelta.unavailable("JaCoCo coverage command timed out.");
        }
        if (result.exitCode() != 0) {
            return CoverageDelta.unavailable("JaCoCo coverage command failed:\n" + tail(result.output()));
        }
        CoverageReportService.CoverageSnapshot afterCoverage = coverageReportService.readProjectSnapshot(descriptor.moduleRoot())
                .orElse(null);
        if (afterCoverage == null) {
            return CoverageDelta.unavailable("Coverage snapshot unavailable after JaCoCo run.");
        }
        CoverageReportService.ClassCoverage beforeClass = classCoverageOrZero(beforeCoverage, descriptor);
        CoverageReportService.ClassCoverage afterClass = classCoverageOrZero(afterCoverage, descriptor);
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

    private String tail(String output) {
        List<String> lines = output == null ? List.of() : output.lines().toList();
        int start = Math.max(0, lines.size() - 60);
        return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
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

        public Double beforeLineCoverage() {
            return beforeClassCoverage == null ? null : beforeClassCoverage.lineCoverage();
        }

        public Double afterLineCoverage() {
            return afterClassCoverage == null ? null : afterClassCoverage.lineCoverage();
        }

        public Double beforeBranchCoverage() {
            return beforeClassCoverage == null ? null : beforeClassCoverage.branchCoverage();
        }

        public Double afterBranchCoverage() {
            return afterClassCoverage == null ? null : afterClassCoverage.branchCoverage();
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
