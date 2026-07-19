package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageBatchPlannerTest {

    @TempDir
    Path tempDir;

    @Test
    void directRefreshSkipsPreparationAndUsesItsExactSnapshot() {
        CoverageReportService.CoverageSnapshot freshSnapshot = snapshot("direct.xml", 50.0d);
        JavaProjectService.JavaClassDescriptor target = targetDescriptor();
        AtomicInteger preparationCalls = new AtomicInteger();
        AtomicInteger availabilityCalls = new AtomicInteger();
        AtomicReference<CoverageReportService.CoverageSnapshot> resolvedSnapshot = new AtomicReference<>();
        CoverageBatchPlanner planner = new CoverageBatchPlanner(
                (root, ui, logs, out) -> freshSnapshot,
                (root, model, ui, logs, out) -> {
                    preparationCalls.incrementAndGet();
                    throw new AssertionError("Codex preparation must not run after a successful direct refresh.");
                },
                (root, threshold, snapshot) -> {
                    assertEquals(85.0d, threshold, 0.0001d);
                    resolvedSnapshot.set(snapshot);
                    return List.of(target);
                },
                root -> availabilityCalls.incrementAndGet()
        );

        CoverageBatchPlanner.CoverageBatchPlan plan = plan(planner, 85.0d);

        assertEquals(0, preparationCalls.get());
        assertEquals(1, availabilityCalls.get());
        assertSame(freshSnapshot, resolvedSnapshot.get());
        assertSame(freshSnapshot, plan.preparation().coverageSnapshot());
        assertEquals(List.of(target), plan.targets());
    }

    @Test
    void zeroMissesReturnBeforeCodexAvailabilityIsRequired() {
        AtomicInteger preparationCalls = new AtomicInteger();
        AtomicInteger availabilityCalls = new AtomicInteger();
        CoverageBatchPlanner planner = new CoverageBatchPlanner(
                (root, ui, logs, out) -> snapshot("complete.xml", 100.0d),
                (root, model, ui, logs, out) -> {
                    preparationCalls.incrementAndGet();
                    throw new AssertionError("Codex preparation must not run.");
                },
                (root, threshold, snapshot) -> List.of(),
                root -> availabilityCalls.incrementAndGet()
        );

        CoverageBatchPlanner.CoverageBatchPlan plan = plan(planner, 80.0d);

        assertTrue(plan.targets().isEmpty());
        assertEquals(0, preparationCalls.get());
        assertEquals(0, availabilityCalls.get());
    }

    @Test
    void failedDirectRefreshUsesThePreparationSnapshot() {
        CoverageReportService.CoverageSnapshot repairedSnapshot = snapshot("repaired.xml", 72.0d);
        JavaProjectService.JavaClassDescriptor target = targetDescriptor();
        AtomicInteger preparationCalls = new AtomicInteger();
        AtomicInteger availabilityCalls = new AtomicInteger();
        AtomicReference<CoverageReportService.CoverageSnapshot> resolvedSnapshot = new AtomicReference<>();
        CoverageBatchPlanner planner = new CoverageBatchPlanner(
                (root, ui, logs, out) -> {
                    throw new IllegalStateException("fixture build failed");
                },
                (root, model, ui, logs, out) -> {
                    preparationCalls.incrementAndGet();
                    return new CodexCliUnitTestGenerator.ProjectPreparation(
                            new CodexCliUnitTestGenerator.AgentUsage(10, 2, 3, 1),
                            repairedSnapshot
                    );
                },
                (root, threshold, snapshot) -> {
                    resolvedSnapshot.set(snapshot);
                    return List.of(target);
                },
                root -> availabilityCalls.incrementAndGet()
        );

        CoverageBatchPlanner.CoverageBatchPlan plan = plan(planner, 80.0d);

        assertEquals(1, preparationCalls.get());
        assertEquals(0, availabilityCalls.get());
        assertSame(repairedSnapshot, resolvedSnapshot.get());
        assertSame(repairedSnapshot, plan.preparation().coverageSnapshot());
    }

    @Test
    void activeRefreshNeverFallsBackToCodexPreparation() {
        AtomicInteger preparationCalls = new AtomicInteger();
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicInteger availabilityCalls = new AtomicInteger();
        CoverageBatchPlanner planner = new CoverageBatchPlanner(
                (root, ui, logs, out) -> {
                    throw new CoverageRefreshService.RefreshInProgressException("already running");
                },
                (root, model, ui, logs, out) -> {
                    preparationCalls.incrementAndGet();
                    return new CodexCliUnitTestGenerator.ProjectPreparation(
                            CodexCliUnitTestGenerator.AgentUsage.zero(),
                            snapshot("unexpected.xml", 0.0d)
                    );
                },
                (root, threshold, snapshot) -> {
                    resolverCalls.incrementAndGet();
                    return List.of();
                },
                root -> availabilityCalls.incrementAndGet()
        );

        assertThrows(
                CoverageRefreshService.RefreshInProgressException.class,
                () -> plan(planner, 80.0d)
        );
        assertEquals(0, preparationCalls.get());
        assertEquals(0, resolverCalls.get());
        assertEquals(0, availabilityCalls.get());
    }

    private CoverageBatchPlanner.CoverageBatchPlan plan(CoverageBatchPlanner planner, double threshold) {
        return planner.plan(
                tempDir,
                threshold,
                null,
                new TerminalUi(new PrintWriter(new StringWriter(), true)),
                false,
                new PrintWriter(new StringWriter(), true)
        );
    }

    private CoverageReportService.CoverageSnapshot snapshot(String name, double coverage) {
        return new CoverageReportService.CoverageSnapshot(
                tempDir.resolve(name),
                coverage,
                coverage,
                Map.of()
        );
    }

    private JavaProjectService.JavaClassDescriptor targetDescriptor() {
        return new JavaProjectService.JavaClassDescriptor(
                tempDir,
                tempDir,
                tempDir.resolve("src/main/java/com/example/OrderService.java"),
                "com.example",
                "OrderService",
                "com.example.OrderService"
        );
    }
}
