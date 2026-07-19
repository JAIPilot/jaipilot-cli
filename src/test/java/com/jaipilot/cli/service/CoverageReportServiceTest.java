package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageReportServiceTest {

    private final CoverageReportService coverageReportService = new CoverageReportService();

    @TempDir
    Path tempDir;

    @Test
    void readProjectSnapshotParsesTotalsAndClassesFromJacocoXml() throws Exception {
        Path projectRoot = tempDir.resolve("sample");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="sample">
                  <package name="com/example">
                    <class name="com/example/OrderService">
                      <counter type="LINE" missed="2" covered="8"/>
                      <counter type="BRANCH" missed="1" covered="3"/>
                    </class>
                    <class name="com/example/LegacyService">
                      <counter type="LINE" missed="7" covered="3"/>
                      <counter type="BRANCH" missed="4" covered="0"/>
                    </class>
                  </package>
                  <counter type="LINE" missed="9" covered="11"/>
                  <counter type="BRANCH" missed="5" covered="3"/>
                </report>
                """);

        CoverageReportService.CoverageSnapshot snapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow();

        assertEquals(reportPath, snapshot.reportPath());
        assertEquals(55.0d, snapshot.totalLineCoverage(), 0.0001d);
        assertEquals(37.5d, snapshot.totalBranchCoverage(), 0.0001d);
        assertEquals(80.0d, snapshot.classCoverage("com.example.OrderService").orElseThrow().lineCoverage(), 0.0001d);
        assertEquals(30.0d, snapshot.classCoverage("com.example.LegacyService").orElseThrow().lineCoverage(), 0.0001d);
        assertTrue(snapshot.classCoverage("com.example.MissingService").isEmpty());
    }

    @Test
    void readProjectSnapshotTreatsClassWithoutLineCounterAsNonCoverable() throws Exception {
        Path projectRoot = tempDir.resolve("sample-interface");
        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, """
                <report name="sample">
                  <package name="com/example">
                    <class name="com/example/RepositoryContract">
                      <counter type="METHOD" missed="0" covered="0"/>
                    </class>
                  </package>
                </report>
                """);

        CoverageReportService.ClassCoverage coverage = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow()
                .classCoverage("com.example.RepositoryContract")
                .orElseThrow();

        assertEquals(100.0d, coverage.lineCoverage(), 0.0001d);
        assertEquals(0.0d, coverage.branchCoverage(), 0.0001d);
    }

    @Test
    void readProjectSnapshotFindsCustomMavenCoverageReportsDirectory() throws Exception {
        Path projectRoot = tempDir.resolve("sample-custom");
        Path reportPath = projectRoot.resolve("target/coverage-reports/jacoco-ut/jacoco.xml");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="sample">
                  <package name="com/example">
                    <class name="com/example/OrderService">
                      <counter type="LINE" missed="0" covered="4"/>
                      <counter type="BRANCH" missed="0" covered="0"/>
                    </class>
                  </package>
                  <counter type="LINE" missed="0" covered="4"/>
                  <counter type="BRANCH" missed="0" covered="0"/>
                </report>
                """);

        CoverageReportService.CoverageSnapshot snapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow();

        assertEquals(reportPath, snapshot.reportPath());
        assertEquals(100.0d, snapshot.classCoverage("com.example.OrderService").orElseThrow().lineCoverage(), 0.0001d);
    }

    @Test
    void readProjectSnapshotFindsConventionalGradleXmlReportName() throws Exception {
        Path projectRoot = tempDir.resolve("sample-gradle");
        Path reportPath = projectRoot.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, """
                <report name="sample">
                  <package name="com/example">
                    <class name="com/example/OrderService">
                      <counter type="LINE" missed="1" covered="3"/>
                    </class>
                  </package>
                  <counter type="LINE" missed="1" covered="3"/>
                </report>
                """);

        CoverageReportService.CoverageSnapshot snapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow();

        assertEquals(reportPath, snapshot.reportPath());
        assertEquals(75.0d, snapshot.totalLineCoverage(), 0.0001d);
    }

    @Test
    void findCoverageReportsReturnsEveryKnownReportLocation() throws Exception {
        Path projectRoot = tempDir.resolve("multiple-reports");
        Path first = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        Path second = projectRoot.resolve("module/target/site/jacoco-it/jacoco.xml");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "<report/>");
        Files.writeString(second, "<report/>");

        assertEquals(List.of(first, second), coverageReportService.findCoverageReports(projectRoot));
    }

    @Test
    void readProjectSnapshotRejectsAmbiguousPerModuleReports() throws Exception {
        Path projectRoot = tempDir.resolve("ambiguous-modules");
        Path first = projectRoot.resolve("module-a/target/site/jacoco/jacoco.xml");
        Path second = projectRoot.resolve("module-b/target/site/jacoco/jacoco.xml");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "<report/>");
        Files.writeString(second, "<report/>");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coverageReportService.readProjectSnapshot(projectRoot)
        );

        assertTrue(failure.getMessage().contains("Multiple JaCoCo XML reports"));
        assertTrue(failure.getMessage().contains("aggregate report"));
    }

    @Test
    void readProjectSnapshotPrefersAndParsesNestedMavenAggregateReport() throws Exception {
        Path projectRoot = tempDir.resolve("aggregate-modules");
        Path moduleReport = projectRoot.resolve("module-a/target/site/jacoco/jacoco.xml");
        Path aggregateReport = projectRoot.resolve("target/site/jacoco-aggregate/jacoco.xml");
        Files.createDirectories(moduleReport.getParent());
        Files.createDirectories(aggregateReport.getParent());
        Files.writeString(moduleReport, "<report/>");
        Files.writeString(aggregateReport, """
                <report name="aggregate">
                  <group name="module-a">
                    <package name="com/example">
                      <class name="com/example/OrderService">
                        <counter type="LINE" missed="1" covered="9"/>
                      </class>
                    </package>
                  </group>
                  <counter type="LINE" missed="1" covered="9"/>
                </report>
                """);

        CoverageReportService.CoverageSnapshot snapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow();

        assertEquals(aggregateReport, snapshot.reportPath());
        assertEquals(90.0d, snapshot.totalLineCoverage(), 0.0001d);
        assertEquals(90.0d, snapshot.classCoverage("com.example.OrderService").orElseThrow().lineCoverage(), 0.0001d);
    }

    @Test
    void readProjectSnapshotRecognizesGradleAggregateReportName() throws Exception {
        Path projectRoot = tempDir.resolve("gradle-aggregate-modules");
        Path moduleReport = projectRoot.resolve("module-a/build/reports/jacoco/test/jacocoTestReport.xml");
        Path aggregateReport = projectRoot.resolve(
                "build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml"
        );
        Files.createDirectories(moduleReport.getParent());
        Files.createDirectories(aggregateReport.getParent());
        Files.writeString(moduleReport, "<report/>");
        Files.writeString(aggregateReport, """
                <report name="aggregate">
                  <group name="module-a">
                    <package name="com/example">
                      <class name="com/example/OrderService">
                        <counter type="LINE" missed="0" covered="6"/>
                      </class>
                    </package>
                  </group>
                  <counter type="LINE" missed="0" covered="6"/>
                </report>
                """);

        CoverageReportService.CoverageSnapshot snapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow();

        assertEquals(aggregateReport, snapshot.reportPath());
        assertEquals(100.0d, snapshot.totalLineCoverage(), 0.0001d);
    }
}
