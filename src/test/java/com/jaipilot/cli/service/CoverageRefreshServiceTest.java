package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageRefreshServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void mavenRefreshDeletesStaleReportRunsExactFullSuiteAndReturnsFreshSnapshot() throws Exception {
        Path projectRoot = createMavenProject("maven-success");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        writeReport(reportPath, 10, 0);
        writeReport(projectRoot.resolve("fresh-report.xml"), 1, 9);
        writeWrapper(projectRoot, "mvnw", "target/site/jacoco/jacoco.xml", true, 0, null);

        CoverageReportService.CoverageSnapshot snapshot = refreshService().refresh(
                projectRoot,
                terminalUi(),
                false,
                new PrintWriter(new StringWriter(), true)
        );

        assertEquals(List.of("-B", "clean", "verify"), Files.readAllLines(projectRoot.resolve("wrapper-args.txt")));
        assertEquals(reportPath, snapshot.reportPath());
        assertEquals(90.0d, snapshot.totalLineCoverage(), 0.0001d);
        assertEquals(90.0d, snapshot.classCoverage("com.example.OrderService").orElseThrow().lineCoverage(), 0.0001d);
    }

    @Test
    void gradleRefreshUsesExactTasksAndDiscoversConventionalXmlReportName() throws Exception {
        Path projectRoot = createGradleProject("gradle-success");
        Path reportPath = projectRoot.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
        writeReport(reportPath, 10, 0);
        writeReport(projectRoot.resolve("fresh-report.xml"), 2, 8);
        writeWrapper(
                projectRoot,
                "gradlew",
                "build/reports/jacoco/test/jacocoTestReport.xml",
                true,
                0,
                null
        );

        CoverageReportService.CoverageSnapshot snapshot = refreshService().refresh(
                projectRoot,
                terminalUi(),
                false,
                new PrintWriter(new StringWriter(), true)
        );

        assertEquals(
                List.of("--no-daemon", "clean", "test", "jacocoTestReport"),
                Files.readAllLines(projectRoot.resolve("wrapper-args.txt"))
        );
        assertEquals(reportPath, snapshot.reportPath());
        assertEquals(80.0d, snapshot.totalLineCoverage(), 0.0001d);
    }

    @Test
    void gradleAggregationRefreshUsesTheAggregateReportTask() throws Exception {
        Path projectRoot = createGradleProject("gradle-aggregate-success");
        Files.writeString(
                projectRoot.resolve("build.gradle"),
                "plugins { id 'java-reporting'; id 'jacoco-report-aggregation' }\n"
        );
        Path reportPath = projectRoot.resolve(
                "build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml"
        );
        writeReport(projectRoot.resolve("fresh-report.xml"), 1, 9);
        writeWrapper(
                projectRoot,
                "gradlew",
                "build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml",
                true,
                0,
                null
        );

        CoverageReportService.CoverageSnapshot snapshot = refreshService().refresh(
                projectRoot,
                terminalUi(),
                false,
                new PrintWriter(new StringWriter(), true)
        );

        assertEquals(
                List.of("--no-daemon", "clean", "testCodeCoverageReport"),
                Files.readAllLines(projectRoot.resolve("wrapper-args.txt"))
        );
        assertEquals(reportPath, snapshot.reportPath());
        assertEquals(90.0d, snapshot.totalLineCoverage(), 0.0001d);
    }

    @Test
    void failedBuildDiscardsReportCreatedByTheFailedRefresh() throws Exception {
        Path projectRoot = createMavenProject("maven-failure");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        writeReport(reportPath, 10, 0);
        writeReport(projectRoot.resolve("fresh-report.xml"), 0, 10);
        writeWrapper(
                projectRoot,
                "mvnw",
                "target/site/jacoco/jacoco.xml",
                true,
                7,
                "fixture build failed"
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> refreshService().refresh(
                        projectRoot,
                        terminalUi(),
                        false,
                        new PrintWriter(new StringWriter(), true)
                )
        );

        assertTrue(failure.getMessage().contains("Clean full-suite coverage refresh failed"));
        assertTrue(failure.getMessage().contains("fixture build failed"));
        assertFalse(Files.exists(reportPath));
    }

    @Test
    void successfulBuildWithoutNewReportFailsInsteadOfReturningStaleCoverage() throws Exception {
        Path projectRoot = createMavenProject("maven-no-report");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        writeReport(reportPath, 0, 10);
        writeWrapper(projectRoot, "mvnw", "target/site/jacoco/jacoco.xml", false, 0, null);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> refreshService().refresh(
                        projectRoot,
                        terminalUi(),
                        false,
                        new PrintWriter(new StringWriter(), true)
                )
        );

        assertTrue(failure.getMessage().contains("did not generate a readable JaCoCo XML report"));
        assertFalse(Files.exists(reportPath));
    }

    @Test
    void liveLogsAreNotRepeatedInTheFailureMessage() throws Exception {
        Path projectRoot = createMavenProject("maven-live-failure");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        writeReport(projectRoot.resolve("fresh-report.xml"), 0, 10);
        writeWrapper(
                projectRoot,
                "mvnw",
                "target/site/jacoco/jacoco.xml",
                true,
                9,
                "fixture build failed"
        );
        StringWriter liveOutput = new StringWriter();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> refreshService().refresh(
                        projectRoot,
                        terminalUi(),
                        true,
                        new PrintWriter(liveOutput, true)
                )
        );

        assertTrue(liveOutput.toString().contains("fixture build failed"));
        assertFalse(failure.getMessage().contains("fixture build failed"));
        assertTrue(failure.getMessage().contains("output was streamed above"));
        assertFalse(Files.exists(reportPath));
    }

    @Test
    void malformedReportFromSuccessfulBuildIsDiscarded() throws Exception {
        Path projectRoot = createMavenProject("maven-malformed-report");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        Files.writeString(projectRoot.resolve("fresh-report.xml"), "<report>");
        writeWrapper(projectRoot, "mvnw", "target/site/jacoco/jacoco.xml", true, 0, null);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> refreshService().refresh(
                        projectRoot,
                        terminalUi(),
                        false,
                        new PrintWriter(new StringWriter(), true)
                )
        );

        assertTrue(failure.getMessage().contains("Failed to parse JaCoCo report"));
        assertFalse(Files.exists(reportPath));
    }

    @Test
    void overlappingRefreshIsRejectedBeforeItCanDeleteTheActiveBuildsReport() throws Exception {
        Path projectRoot = createMavenProject("overlapping-refresh");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        writeReport(projectRoot.resolve("fresh-report.xml"), 0, 10);
        Path wrapper = projectRoot.resolve("mvnw");
        Files.writeString(wrapper, """
                #!/bin/sh
                set -eu
                touch wrapper-started
                sleep 1
                mkdir -p target/site/jacoco
                cp fresh-report.xml target/site/jacoco/jacoco.xml
                """);
        assertTrue(wrapper.toFile().setExecutable(true, false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CoverageReportService.CoverageSnapshot> activeRefresh = executor.submit(() -> refreshService().refresh(
                    projectRoot,
                    terminalUi(),
                    false,
                    new PrintWriter(new StringWriter(), true)
            ));
            waitForFile(projectRoot.resolve("wrapper-started"));

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> refreshService().refresh(
                            projectRoot,
                            terminalUi(),
                            false,
                            new PrintWriter(new StringWriter(), true)
                    )
            );

            assertTrue(failure.getMessage().contains("already running"));
            assertThrows(
                    CoverageRefreshService.RefreshInProgressException.class,
                    () -> refreshService().readCachedSnapshot(projectRoot)
            );
            assertEquals(100.0d, activeRefresh.get(5, TimeUnit.SECONDS).totalLineCoverage(), 0.0001d);
            assertTrue(Files.isRegularFile(reportPath));
        } finally {
            executor.shutdownNow();
        }
    }

    private Path createMavenProject(String name) throws Exception {
        Path projectRoot = tempDir.resolve(name);
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n");
        Path wrapperProperties = projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(wrapperProperties.getParent());
        Files.writeString(wrapperProperties, "distributionUrl=https://repo.maven.apache.org/maven2\n");
        return projectRoot;
    }

    private Path createGradleProject(String name) throws Exception {
        Path projectRoot = tempDir.resolve(name);
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins { id 'java'; id 'jacoco' }\n");
        Path wrapperProperties = projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(wrapperProperties.getParent());
        Files.writeString(wrapperProperties, "distributionUrl=https://services.gradle.org/distributions/gradle.zip\n");
        return projectRoot;
    }

    private void writeWrapper(
            Path projectRoot,
            String wrapperName,
            String relativeReportPath,
            boolean createReport,
            int exitCode,
            String output
    ) throws Exception {
        String reportCommands = createReport
                ? "mkdir -p \"$(dirname '" + relativeReportPath + "')\"\n"
                        + "cp fresh-report.xml '" + relativeReportPath + "'\n"
                : "";
        String outputCommand = output == null ? "" : "printf '%s\\n' '" + output + "'\n";
        Path wrapper = projectRoot.resolve(wrapperName);
        Files.writeString(wrapper, """
                #!/bin/sh
                set -eu
                if [ -e '%s' ]; then
                  printf 'stale report was not deleted before the build\n' >&2
                  exit 91
                fi
                printf '%%s\n' "$@" > wrapper-args.txt
                %s%s
                exit %d
                """.formatted(relativeReportPath, reportCommands, outputCommand, exitCode));
        assertTrue(wrapper.toFile().setExecutable(true, false));
    }

    private void writeReport(Path reportPath, int missedLines, int coveredLines) throws Exception {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, """
                <report name="fixture">
                  <package name="com/example">
                    <class name="com/example/OrderService">
                      <counter type="LINE" missed="%d" covered="%d"/>
                      <counter type="BRANCH" missed="1" covered="3"/>
                    </class>
                  </package>
                  <counter type="LINE" missed="%d" covered="%d"/>
                  <counter type="BRANCH" missed="1" covered="3"/>
                </report>
                """.formatted(missedLines, coveredLines, missedLines, coveredLines));
    }

    private CoverageRefreshService refreshService() {
        ProjectFileService fileService = new ProjectFileService();
        CoverageReportService reportService = new CoverageReportService();
        JavaProjectService projectService = new JavaProjectService(fileService, reportService);
        return new CoverageRefreshService(
                projectService,
                reportService,
                new ProcessExecutor(),
                Duration.ofSeconds(10)
        );
    }

    private TerminalUi terminalUi() {
        return new TerminalUi(new PrintWriter(new StringWriter(), true));
    }

    private void waitForFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(Files.exists(path), "Timed out waiting for " + path);
    }
}
