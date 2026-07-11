package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
