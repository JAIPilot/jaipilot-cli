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
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class CoverageReportService {

    public Optional<Path> findCoverageReport(Path projectRoot) {
        List<Path> matches = new ArrayList<>();
        try (var paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("jacoco.xml"))
                    .filter(path -> normalize(path).contains("/target/site/jacoco/")
                            || normalize(path).contains("/build/reports/jacoco/"))
                    .forEach(matches::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan coverage reports under " + projectRoot, exception);
        }
        return matches.stream()
                .sorted(Comparator.comparingInt(path -> projectRoot.relativize(path).getNameCount()))
                .findFirst();
    }

    public Optional<CoverageSnapshot> readProjectSnapshot(Path projectRoot) {
        return findCoverageReport(projectRoot).map(this::readReportSnapshot);
    }

    public CoverageSnapshot readReportSnapshot(Path reportPath) {
        Document document = parse(reportPath);
        Map<String, ClassCoverage> coverageByClass = new HashMap<>();
        for (Element packageElement : childElements(document.getDocumentElement(), "package")) {
            for (Element classElement : childElements(packageElement, "class")) {
                String fullyQualifiedName = classElement.getAttribute("name").replace('/', '.');
                double lineCoverage = readCoverage(classElement, "LINE");
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

    public Map<String, Double> readLineCoverageByClass(Path reportPath) {
        Map<String, Double> coverageByClass = new HashMap<>();
        for (ClassCoverage coverage : readReportSnapshot(reportPath).classCoverageByName().values()) {
            coverageByClass.put(coverage.fullyQualifiedName(), coverage.lineCoverage());
        }
        return coverageByClass;
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
            return builder.parse(inputStream);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse JaCoCo report " + reportPath, exception);
        }
    }

    private double readCoverage(Element parent, String counterType) {
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
        return 0.0d;
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
