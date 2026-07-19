package com.jaipilot.cli.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

public final class CoverageReportService {

    public Optional<Path> findCoverageReport(Path projectRoot) {
        return findCoverageReports(projectRoot).stream().findFirst();
    }

    public List<Path> findCoverageReports(Path projectRoot) {
        List<Path> matches = new ArrayList<>();
        try (var paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isCoverageReportFile)
                    .filter(this::isKnownCoverageReportLocation)
                    .forEach(matches::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan coverage reports under " + projectRoot, exception);
        }
        return matches.stream()
                .sorted(Comparator.comparingInt(path -> projectRoot.relativize(path).getNameCount()))
                .toList();
    }

    public Optional<CoverageSnapshot> readProjectSnapshot(Path projectRoot) {
        List<Path> reports = findCoverageReports(projectRoot);
        if (reports.isEmpty()) {
            return Optional.empty();
        }
        if (reports.size() == 1) {
            return Optional.of(readReportSnapshot(reports.get(0)));
        }
        List<Path> aggregateReports = reports.stream()
                .filter(this::isAggregateCoverageReport)
                .toList();
        if (aggregateReports.size() == 1) {
            return Optional.of(readReportSnapshot(aggregateReports.get(0)));
        }
        throw new IllegalStateException(
                "Multiple JaCoCo XML reports were found. Configure one aggregate report so coverage is not assigned "
                        + "to the wrong module: " + reports
        );
    }

    public CoverageSnapshot readReportSnapshot(Path reportPath) {
        Document document = parse(reportPath);
        Map<String, ClassCoverage> coverageByClass = new HashMap<>();
        NodeList packageElements = document.getElementsByTagName("package");
        for (int packageIndex = 0; packageIndex < packageElements.getLength(); packageIndex++) {
            Element packageElement = (Element) packageElements.item(packageIndex);
            for (Element classElement : childElements(packageElement, "class")) {
                String fullyQualifiedName = classElement.getAttribute("name").replace('/', '.');
                double lineCoverage = readCoverage(classElement, "LINE", 100.0d);
                double branchCoverage = readCoverage(classElement, "BRANCH");
                coverageByClass.put(fullyQualifiedName, new ClassCoverage(fullyQualifiedName, lineCoverage, branchCoverage));
            }
        }
        return new CoverageSnapshot(
                reportPath,
                readCoverage(document.getDocumentElement(), "LINE"),
                readCoverage(document.getDocumentElement(), "BRANCH"),
                Map.copyOf(coverageByClass)
        );
    }

    private Document parse(Path reportPath) {
        try (InputStream inputStream = Files.newInputStream(reportPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/validation", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            // JaCoCo reports commonly declare report.dtd, but coverage parsing only needs the XML payload.
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // Warnings do not make an otherwise readable report unusable.
                }

                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            return builder.parse(inputStream);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse JaCoCo report " + reportPath, exception);
        }
    }

    private double readCoverage(Element parent, String counterType) {
        return readCoverage(parent, counterType, 0.0d);
    }

    private double readCoverage(Element parent, String counterType, double missingCoverage) {
        for (Element counterElement : childElements(parent, "counter")) {
            if (!counterType.equals(counterElement.getAttribute("type"))) {
                continue;
            }
            double missed = parseDouble(counterElement.getAttribute("missed"));
            double covered = parseDouble(counterElement.getAttribute("covered"));
            double total = missed + covered;
            if (total <= 0) {
                return 100.0d;
            }
            return (covered / total) * 100.0d;
        }
        return missingCoverage;
    }

    private List<Element> childElements(Element parent, String tagName) {
        List<Element> elements = new ArrayList<>();
        NodeList childNodes = parent.getChildNodes();
        for (int index = 0; index < childNodes.getLength(); index++) {
            if (childNodes.item(index) instanceof Element child && tagName.equals(child.getTagName())) {
                elements.add(child);
            }
        }
        return elements;
    }

    private double parseDouble(String value) {
        return value == null || value.isBlank() ? 0.0d : Double.parseDouble(value);
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private boolean isKnownCoverageReportLocation(Path path) {
        String normalized = normalize(path);
        return normalized.contains("/target/site/jacoco/")
                || normalized.contains("/target/site/jacoco-")
                || normalized.contains("/target/coverage-reports/")
                || normalized.contains("/build/reports/jacoco/");
    }

    private boolean isCoverageReportFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml");
    }

    private boolean isAggregateCoverageReport(Path path) {
        String normalized = normalize(path).toLowerCase(Locale.ROOT);
        return normalized.contains("/jacoco-aggregate/")
                || normalized.contains("/jacoco/aggregate/")
                || normalized.contains("testcodecoveragereport");
    }

    public record CoverageSnapshot(
            Path reportPath,
            double totalLineCoverage,
            double totalBranchCoverage,
            Map<String, ClassCoverage> classCoverageByName
    ) {
        public Optional<ClassCoverage> classCoverage(String fullyQualifiedName) {
            return Optional.ofNullable(classCoverageByName.get(fullyQualifiedName));
        }
    }

    public record ClassCoverage(
            String fullyQualifiedName,
            double lineCoverage,
            double branchCoverage
    ) {
    }
}
