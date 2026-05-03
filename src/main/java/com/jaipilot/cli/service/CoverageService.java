package com.jaipilot.cli.service;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class CoverageService {

    private static final Duration COVERAGE_TIMEOUT = Duration.ofMinutes(10);
    private static final String SRC_MAIN_JAVA_MARKER = "/src/main/java/";

    private final ProcessExecutor processExecutor;

    public CoverageService() {
        this(new ProcessExecutor());
    }

    CoverageService(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor == null ? new ProcessExecutor() : processExecutor;
    }

    public CoverageMeasurement measureLineCoverage(Path projectRoot, Path cutPath, PrintWriter progressWriter) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedCutPath = cutPath.toAbsolutePath().normalize();

        String classInternalName = resolveClassInternalName(normalizedProjectRoot, normalizedCutPath);
        if (classInternalName == null) {
            return CoverageMeasurement.unavailable("Class is not under src/main/java.");
        }

        BuildTool buildTool = detectBuildTool(normalizedProjectRoot);
        if (buildTool == BuildTool.NONE) {
            return CoverageMeasurement.unavailable("No Maven or Gradle build found.");
        }

        try {
            ProcessExecutor.ExecutionResult commandResult = processExecutor.execute(
                    coverageCommand(normalizedProjectRoot, buildTool),
                    normalizedProjectRoot,
                    COVERAGE_TIMEOUT,
                    false,
                    progressWriter
            );
            if (commandResult.timedOut()) {
                return CoverageMeasurement.unavailable("Coverage command timed out.");
            }
            if (commandResult.exitCode() != 0) {
                return CoverageMeasurement.unavailable("Coverage command failed (exit code " + commandResult.exitCode() + ").");
            }

            Path reportPath = coverageReportPath(normalizedProjectRoot, buildTool);
            if (!Files.isRegularFile(reportPath)) {
                return CoverageMeasurement.unavailable("Coverage report not found at " + reportPath + ".");
            }
            return parseLineCoverageFromReport(reportPath, classInternalName);
        } catch (Exception exception) {
            return CoverageMeasurement.unavailable("Coverage measurement failed: " + exception.getMessage());
        }
    }

    static String resolveClassInternalName(Path projectRoot, Path cutPath) {
        String relativePath;
        if (cutPath.startsWith(projectRoot)) {
            relativePath = projectRoot.relativize(cutPath).toString();
        } else {
            relativePath = cutPath.toString();
        }
        String normalized = relativePath.replace('\\', '/');

        String marker = "src/main/java/";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex >= 0) {
            normalized = normalized.substring(markerIndex + marker.length());
        } else if (normalized.startsWith("/")) {
            int absoluteMarkerIndex = normalized.indexOf(SRC_MAIN_JAVA_MARKER);
            if (absoluteMarkerIndex >= 0) {
                normalized = normalized.substring(absoluteMarkerIndex + SRC_MAIN_JAVA_MARKER.length());
            }
        } else {
            return null;
        }

        if (!normalized.endsWith(".java")) {
            return null;
        }
        return normalized.substring(0, normalized.length() - ".java".length());
    }

    static CoverageMeasurement parseLineCoverageFromReport(Path reportPath, String classInternalName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        var documentBuilder = factory.newDocumentBuilder();
        var document = documentBuilder.parse(reportPath.toFile());
        NodeList classNodes = document.getElementsByTagName("class");

        for (int index = 0; index < classNodes.getLength(); index++) {
            Node classNode = classNodes.item(index);
            if (!(classNode instanceof Element classElement)) {
                continue;
            }
            String className = classElement.getAttribute("name");
            if (!classInternalName.equals(className)) {
                continue;
            }
            return extractLineCoverage(classElement, classInternalName);
        }

        return CoverageMeasurement.unavailable("Class not found in coverage report.");
    }

    private static CoverageMeasurement extractLineCoverage(Element classElement, String classInternalName) {
        NodeList children = classElement.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node childNode = children.item(index);
            if (!(childNode instanceof Element counterElement)) {
                continue;
            }
            if (!"counter".equals(counterElement.getTagName())) {
                continue;
            }
            if (!"LINE".equals(counterElement.getAttribute("type"))) {
                continue;
            }

            long covered = parseLong(counterElement.getAttribute("covered"));
            long missed = parseLong(counterElement.getAttribute("missed"));
            long total = covered + missed;
            if (total <= 0) {
                return CoverageMeasurement.unavailable("No line coverage counters found for class " + classInternalName + ".");
            }
            return CoverageMeasurement.of(covered, missed);
        }

        return CoverageMeasurement.unavailable("No line coverage counters found for class " + classInternalName + ".");
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value.trim());
    }

    private BuildTool detectBuildTool(Path projectRoot) {
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (
                Files.isRegularFile(projectRoot.resolve("build.gradle"))
                        || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))
                        || Files.isRegularFile(projectRoot.resolve("settings.gradle"))
                        || Files.isRegularFile(projectRoot.resolve("settings.gradle.kts"))
        ) {
            return BuildTool.GRADLE;
        }
        return BuildTool.NONE;
    }

    private List<String> coverageCommand(Path projectRoot, BuildTool buildTool) {
        return switch (buildTool) {
            case MAVEN -> {
                if (Files.isRegularFile(projectRoot.resolve("mvnw"))) {
                    yield List.of("./mvnw", "-q", "-DskipTests=false", "test", "jacoco:report");
                }
                yield List.of("mvn", "-q", "-DskipTests=false", "test", "jacoco:report");
            }
            case GRADLE -> {
                if (Files.isRegularFile(projectRoot.resolve("gradlew"))) {
                    yield List.of("./gradlew", "-q", "test", "jacocoTestReport");
                }
                yield List.of("gradle", "-q", "test", "jacocoTestReport");
            }
            case NONE -> List.of();
        };
    }

    private Path coverageReportPath(Path projectRoot, BuildTool buildTool) {
        return switch (buildTool) {
            case MAVEN -> projectRoot.resolve("target/site/jacoco/jacoco.xml");
            case GRADLE -> projectRoot.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
            case NONE -> projectRoot.resolve("jacoco.xml");
        };
    }

    private enum BuildTool {
        MAVEN,
        GRADLE,
        NONE
    }

    public record CoverageMeasurement(
            boolean available,
            double linePercent,
            long coveredLines,
            long missedLines,
            String reason
    ) {

        static CoverageMeasurement of(long coveredLines, long missedLines) {
            long total = coveredLines + missedLines;
            if (total <= 0) {
                return unavailable("No executable lines found.");
            }
            double percent = (coveredLines * 100.0) / total;
            return new CoverageMeasurement(true, percent, coveredLines, missedLines, null);
        }

        static CoverageMeasurement unavailable(String reason) {
            String normalizedReason = reason == null || reason.isBlank() ? "Unavailable." : reason;
            return new CoverageMeasurement(false, 0.0, 0L, 0L, normalizedReason);
        }

        public long totalLines() {
            return coveredLines + missedLines;
        }

        public String formattedPercent() {
            return String.format("%.2f%%", linePercent);
        }

        public String formattedPercentOrNa() {
            return available ? formattedPercent() : "N/A";
        }
    }
}
