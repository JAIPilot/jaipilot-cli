package com.jaipilot.cli.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveClassInternalNameResolvesFromSrcMainJavaPath() {
        Path projectRoot = tempDir.resolve("project");
        Path cutPath = projectRoot.resolve("src/main/java/com/example/web/VetController.java");

        String internalName = CoverageService.resolveClassInternalName(projectRoot, cutPath);

        assertEquals("com/example/web/VetController", internalName);
    }

    @Test
    void parseLineCoverageFromReportExtractsLineCounter() throws Exception {
        Path reportPath = tempDir.resolve("jacoco.xml");
        Files.writeString(
                reportPath,
                """
                <report name="demo">
                  <package name="com/example/web">
                    <class name="com/example/web/VetController">
                      <counter type="LINE" missed="2" covered="6"/>
                    </class>
                  </package>
                </report>
                """
        );

        CoverageService.CoverageMeasurement measurement = CoverageService.parseLineCoverageFromReport(
                reportPath,
                "com/example/web/VetController"
        );

        assertTrue(measurement.available());
        assertEquals(75.0, measurement.linePercent(), 0.0001);
        assertEquals(6L, measurement.coveredLines());
        assertEquals(2L, measurement.missedLines());
    }

    @Test
    void parseLineCoverageFromReportReturnsUnavailableWhenClassMissing() throws Exception {
        Path reportPath = tempDir.resolve("jacoco.xml");
        Files.writeString(
                reportPath,
                """
                <report name="demo">
                  <package name="com/example/web">
                    <class name="com/example/web/OtherController">
                      <counter type="LINE" missed="2" covered="6"/>
                    </class>
                  </package>
                </report>
                """
        );

        CoverageService.CoverageMeasurement measurement = CoverageService.parseLineCoverageFromReport(
                reportPath,
                "com/example/web/VetController"
        );

        assertFalse(measurement.available());
        assertNotNull(measurement.reason());
    }
}
