package com.jaipilot.cli.service;

import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class CoverageBatchPlanner {

    private final CoverageRefresher coverageRefresher;
    private final ProjectPreparer projectPreparer;
    private final TargetResolver targetResolver;
    private final CodexAvailability codexAvailability;

    public CoverageBatchPlanner(
            CoverageRefreshService coverageRefreshService,
            CodexCliUnitTestGenerator generator,
            JavaProjectService projectService
    ) {
        this(
                coverageRefreshService::refresh,
                generator::prepareProject,
                projectService::findClassesBelowCoverage,
                generator::ensureCodexAvailable
        );
    }

    CoverageBatchPlanner(
            CoverageRefresher coverageRefresher,
            ProjectPreparer projectPreparer,
            TargetResolver targetResolver,
            CodexAvailability codexAvailability
    ) {
        this.coverageRefresher = Objects.requireNonNull(coverageRefresher, "coverageRefresher");
        this.projectPreparer = Objects.requireNonNull(projectPreparer, "projectPreparer");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
        this.codexAvailability = Objects.requireNonNull(codexAvailability, "codexAvailability");
    }

    public CoverageBatchPlan plan(
            Path projectRoot,
            double threshold,
            String model,
            TerminalUi ui,
            boolean showLogs,
            PrintWriter out
    ) {
        CodexCliUnitTestGenerator.ProjectPreparation preparation;
        boolean preparedByCodex = false;
        ui.info("Refreshing clean full-suite coverage before resolving targets.");
        try {
            CoverageReportService.CoverageSnapshot snapshot = coverageRefresher.refresh(
                    projectRoot,
                    ui,
                    showLogs,
                    out
            );
            preparation = new CodexCliUnitTestGenerator.ProjectPreparation(
                    CodexCliUnitTestGenerator.AgentUsage.zero(),
                    snapshot
            );
            ui.success("Fresh full-suite coverage is ready for target selection.");
        } catch (CoverageRefreshService.RefreshInProgressException exception) {
            throw exception;
        } catch (Exception directRefreshFailure) {
            ui.warn("Direct coverage refresh failed; asking Codex to repair project: "
                    + firstLine(directRefreshFailure));
            try {
                preparation = projectPreparer.prepare(projectRoot, model, ui, showLogs, out);
                preparedByCodex = true;
            } catch (Exception preparationFailure) {
                preparationFailure.addSuppressed(directRefreshFailure);
                throw new IllegalStateException("Codex project preparation failed.", preparationFailure);
            }
            ui.success("Codex completed project preparation.");
            ui.info(
                    "Preparation tokens: input=%d cached=%d output=%d reasoning=%d total=%d".formatted(
                            preparation.usage().inputTokens(),
                            preparation.usage().cachedInputTokens(),
                            preparation.usage().outputTokens(),
                            preparation.usage().reasoningOutputTokens(),
                            preparation.usage().totalTokens()
                    )
            );
            ui.success("Fresh full-suite coverage is ready for target selection.");
        }
        ui.blankLine();

        CoverageReportService.CoverageSnapshot snapshot = Objects.requireNonNull(
                preparation.coverageSnapshot(),
                "Coverage preparation did not return a fresh snapshot."
        );
        List<JavaProjectService.JavaClassDescriptor> targets = targetResolver.resolve(
                projectRoot,
                threshold,
                snapshot
        );
        if (!preparedByCodex && !targets.isEmpty()) {
            codexAvailability.ensureAvailable(projectRoot);
        }
        return new CoverageBatchPlan(preparation, List.copyOf(targets));
    }

    private String firstLine(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.toString();
        }
        return message.lines().findFirst().orElse(message);
    }

    @FunctionalInterface
    interface CoverageRefresher {
        CoverageReportService.CoverageSnapshot refresh(
                Path projectRoot,
                TerminalUi ui,
                boolean showLogs,
                PrintWriter out
        );
    }

    @FunctionalInterface
    interface ProjectPreparer {
        CodexCliUnitTestGenerator.ProjectPreparation prepare(
                Path projectRoot,
                String model,
                TerminalUi ui,
                boolean showLogs,
                PrintWriter out
        ) throws Exception;
    }

    @FunctionalInterface
    interface TargetResolver {
        List<JavaProjectService.JavaClassDescriptor> resolve(
                Path projectRoot,
                double threshold,
                CoverageReportService.CoverageSnapshot snapshot
        );
    }

    @FunctionalInterface
    interface CodexAvailability {
        void ensureAvailable(Path projectRoot);
    }

    public record CoverageBatchPlan(
            CodexCliUnitTestGenerator.ProjectPreparation preparation,
            List<JavaProjectService.JavaClassDescriptor> targets
    ) {
    }
}
