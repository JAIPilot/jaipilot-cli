package com.jaipilot.cli.service;

import com.jaipilot.cli.files.ProjectFileService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class MockitoVersionResolver {

    private static final List<String> GRADLE_BUILD_FILES = List.of("build.gradle.kts", "build.gradle");
    private static final String MOCKITO_GROUP_ID = "org.mockito";
    private static final List<String> MOCKITO_ARTIFACT_PRIORITY = List.of(
            "mockito-core",
            "mockito-junit-jupiter",
            "mockito-inline"
    );

    private static final Pattern GRADLE_COORDINATE_PATTERN = Pattern.compile(
            "[\"']org\\.mockito:mockito[^:\"']*:([^\"']+)[\"']"
    );
    private static final Pattern GRADLE_MAP_NOTATION_PATTERN = Pattern.compile(
            "group\\s*[:=]\\s*[\"']org\\.mockito[\"'][\\s\\S]{0,200}?version\\s*[:=]\\s*[\"']([^\"']+)[\"']"
    );
    private static final Pattern GRADLE_VARIABLE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:(?:val|var|def)\\s+)?([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*[\"']([^\"']+)[\"']\\s*$"
    );
    private static final Pattern TOML_VERSION_VALUE_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*[\"']([^\"']+)[\"']\\s*$"
    );
    private static final Pattern TOML_MODULE_DIRECT_VERSION_PATTERN = Pattern.compile(
            "\\{[^}]*module\\s*=\\s*\"org\\.mockito:[^\"]+\"[^}]*version\\s*=\\s*\"([^\"]+)\"[^}]*\\}"
    );
    private static final Pattern TOML_GROUP_DIRECT_VERSION_PATTERN = Pattern.compile(
            "\\{[^}]*group\\s*=\\s*\"org\\.mockito\"[^}]*version\\s*=\\s*\"([^\"]+)\"[^}]*\\}"
    );
    private static final Pattern TOML_MODULE_VERSION_REF_PATTERN = Pattern.compile(
            "\\{[^}]*module\\s*=\\s*\"org\\.mockito:[^\"]+\"[^}]*version\\.ref\\s*=\\s*\"([^\"]+)\"[^}]*\\}"
    );
    private static final Pattern TOML_GROUP_VERSION_REF_PATTERN = Pattern.compile(
            "\\{[^}]*group\\s*=\\s*\"org\\.mockito\"[^}]*version\\.ref\\s*=\\s*\"([^\"]+)\"[^}]*\\}"
    );

    private final ProjectFileService fileService;

    MockitoVersionResolver(ProjectFileService fileService) {
        this.fileService = Objects.requireNonNull(fileService, "fileService");
    }

    String resolve(Path projectRoot, Path sourcePath) {
        Path normalizedProjectRoot = normalizeDirectory(projectRoot);
        Path moduleRoot = resolveModuleRoot(normalizedProjectRoot, sourcePath);

        String mavenVersion = resolveFromMavenPom(moduleRoot);
        if (mavenVersion != null) {
            return mavenVersion;
        }

        String gradleVersion = resolveFromGradle(moduleRoot, normalizedProjectRoot);
        if (gradleVersion != null) {
            return gradleVersion;
        }

        return null;
    }

    private Path resolveModuleRoot(Path projectRoot, Path sourcePath) {
        if (sourcePath == null) {
            return projectRoot;
        }
        Path moduleRoot = fileService.findNearestBuildProjectRoot(sourcePath);
        if (moduleRoot == null) {
            return projectRoot;
        }
        Path normalizedModuleRoot = moduleRoot.toAbsolutePath().normalize();
        if (!normalizedModuleRoot.startsWith(projectRoot)) {
            return projectRoot;
        }
        return normalizedModuleRoot;
    }

    private String resolveFromMavenPom(Path moduleRoot) {
        Path pomPath = moduleRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) {
            return null;
        }

        Document document = parsePomDocument(pomPath);
        if (document == null) {
            return null;
        }

        Element project = document.getDocumentElement();
        if (project == null) {
            return null;
        }

        Map<String, String> properties = collectMavenProperties(project);
        mergeParentProperties(project, pomPath, properties);

        String dependencyVersion = resolveMockitoVersionFromDependencies(project, properties);
        if (dependencyVersion != null) {
            return dependencyVersion;
        }

        String propertyVersion = resolveProperty(properties, "mockito.version");
        if (propertyVersion != null) {
            return propertyVersion;
        }
        return resolveProperty(properties, "mockitoVersion");
    }

    private void mergeParentProperties(Element project, Path pomPath, Map<String, String> properties) {
        Path parentPomPath = resolveParentPomPath(project, pomPath);
        if (parentPomPath == null || !Files.isRegularFile(parentPomPath)) {
            return;
        }
        Document parentDocument = parsePomDocument(parentPomPath);
        if (parentDocument == null || parentDocument.getDocumentElement() == null) {
            return;
        }
        Map<String, String> parentProperties = collectMavenProperties(parentDocument.getDocumentElement());
        for (Map.Entry<String, String> entry : parentProperties.entrySet()) {
            properties.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private String resolveMockitoVersionFromDependencies(Element project, Map<String, String> properties) {
        List<MavenDependencyDecl> dependencies = new ArrayList<>();
        dependencies.addAll(readDependencies(project, "dependencies"));

        Element dependencyManagement = firstChildElement(project, "dependencyManagement");
        if (dependencyManagement != null) {
            dependencies.addAll(readDependencies(dependencyManagement, "dependencies"));
        }

        for (String artifactId : MOCKITO_ARTIFACT_PRIORITY) {
            for (MavenDependencyDecl dependency : dependencies) {
                String resolvedGroupId = resolvePropertyToken(dependency.groupId(), properties);
                String resolvedArtifactId = resolvePropertyToken(dependency.artifactId(), properties);
                if (!MOCKITO_GROUP_ID.equals(resolvedGroupId)) {
                    continue;
                }
                if (!artifactId.equals(resolvedArtifactId)) {
                    continue;
                }
                String resolvedVersion = resolvePropertyToken(dependency.version(), properties);
                if (resolvedVersion != null) {
                    return resolvedVersion;
                }
            }
        }

        for (MavenDependencyDecl dependency : dependencies) {
            String resolvedGroupId = resolvePropertyToken(dependency.groupId(), properties);
            String resolvedArtifactId = resolvePropertyToken(dependency.artifactId(), properties);
            if (!MOCKITO_GROUP_ID.equals(resolvedGroupId)) {
                continue;
            }
            if (resolvedArtifactId == null || !resolvedArtifactId.startsWith("mockito-")) {
                continue;
            }
            String resolvedVersion = resolvePropertyToken(dependency.version(), properties);
            if (resolvedVersion != null) {
                return resolvedVersion;
            }
        }

        return null;
    }

    private List<MavenDependencyDecl> readDependencies(Element root, String dependenciesNodeName) {
        Element dependencies = firstChildElement(root, dependenciesNodeName);
        if (dependencies == null) {
            return List.of();
        }

        List<MavenDependencyDecl> parsed = new ArrayList<>();
        for (Element dependency : childElements(dependencies, "dependency")) {
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            String version = childText(dependency, "version");
            parsed.add(new MavenDependencyDecl(groupId, artifactId, version));
        }
        return parsed;
    }

    private Path resolveParentPomPath(Element project, Path pomPath) {
        Element parent = firstChildElement(project, "parent");
        if (parent == null) {
            return null;
        }

        String relativePath = childText(parent, "relativePath");
        if (relativePath == null || relativePath.isBlank()) {
            relativePath = "../pom.xml";
        }

        Path parentPath = pomPath.getParent().resolve(relativePath).normalize();
        if (Files.isDirectory(parentPath)) {
            parentPath = parentPath.resolve("pom.xml").normalize();
        }
        return parentPath;
    }

    private Document parsePomDocument(Path pomPath) {
        try (InputStream inputStream = Files.newInputStream(pomPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(inputStream);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> collectMavenProperties(Element project) {
        Element properties = firstChildElement(project, "properties");
        if (properties == null) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        Node current = properties.getFirstChild();
        while (current != null) {
            if (current.getNodeType() == Node.ELEMENT_NODE) {
                String key = current.getNodeName();
                String value = trimmedText(current.getTextContent());
                if (key != null && !key.isBlank() && value != null) {
                    values.put(key, value);
                }
            }
            current = current.getNextSibling();
        }
        return values;
    }

    private String resolveFromGradle(Path moduleRoot, Path projectRoot) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(moduleRoot);
        roots.add(projectRoot);

        for (Path root : roots) {
            for (String fileName : GRADLE_BUILD_FILES) {
                Path buildFile = root.resolve(fileName);
                if (!Files.isRegularFile(buildFile)) {
                    continue;
                }
                String resolved = resolveFromGradleBuildFile(buildFile);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        for (Path root : roots) {
            Path versionCatalog = root.resolve("gradle").resolve("libs.versions.toml");
            if (!Files.isRegularFile(versionCatalog)) {
                continue;
            }
            String resolved = resolveFromVersionCatalog(versionCatalog);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private String resolveFromGradleBuildFile(Path buildFile) {
        String content = readText(buildFile);
        if (content == null || content.isBlank()) {
            return null;
        }

        Map<String, String> variables = parseGradleVariables(content);

        Matcher coordinateMatcher = GRADLE_COORDINATE_PATTERN.matcher(content);
        while (coordinateMatcher.find()) {
            String token = coordinateMatcher.group(1);
            String resolved = resolveGradleVersionToken(token, variables);
            if (resolved != null) {
                return resolved;
            }
        }

        Matcher mapNotationMatcher = GRADLE_MAP_NOTATION_PATTERN.matcher(content);
        while (mapNotationMatcher.find()) {
            String token = mapNotationMatcher.group(1);
            String resolved = resolveGradleVersionToken(token, variables);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private Map<String, String> parseGradleVariables(String content) {
        Map<String, String> variables = new LinkedHashMap<>();
        Matcher matcher = GRADLE_VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            variables.put(matcher.group(1), matcher.group(2));
        }
        return variables;
    }

    private String resolveFromVersionCatalog(Path catalogPath) {
        String content = readText(catalogPath);
        if (content == null || content.isBlank()) {
            return null;
        }

        Matcher moduleDirectVersionMatcher = TOML_MODULE_DIRECT_VERSION_PATTERN.matcher(content);
        if (moduleDirectVersionMatcher.find()) {
            return normalizeVersion(moduleDirectVersionMatcher.group(1));
        }
        Matcher groupDirectVersionMatcher = TOML_GROUP_DIRECT_VERSION_PATTERN.matcher(content);
        if (groupDirectVersionMatcher.find()) {
            return normalizeVersion(groupDirectVersionMatcher.group(1));
        }

        Map<String, String> versions = parseTomlVersions(content);

        Matcher moduleVersionRefMatcher = TOML_MODULE_VERSION_REF_PATTERN.matcher(content);
        if (moduleVersionRefMatcher.find()) {
            return resolveProperty(versions, moduleVersionRefMatcher.group(1));
        }
        Matcher groupVersionRefMatcher = TOML_GROUP_VERSION_REF_PATTERN.matcher(content);
        if (groupVersionRefMatcher.find()) {
            return resolveProperty(versions, groupVersionRefMatcher.group(1));
        }
        return null;
    }

    private Map<String, String> parseTomlVersions(String content) {
        Map<String, String> versions = new LinkedHashMap<>();
        boolean insideVersions = false;

        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                insideVersions = "[versions]".equals(trimmed);
                continue;
            }

            if (!insideVersions) {
                continue;
            }

            Matcher matcher = TOML_VERSION_VALUE_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                versions.put(matcher.group(1), matcher.group(2));
            }
        }
        return versions;
    }

    private String resolveGradleVersionToken(String token, Map<String, String> variables) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim();

        String variableName = null;
        if (normalized.startsWith("${") && normalized.endsWith("}")) {
            variableName = normalized.substring(2, normalized.length() - 1).trim();
        } else if (normalized.startsWith("$")) {
            variableName = normalized.substring(1).trim();
        }

        if (variableName != null) {
            if (variableName.startsWith("versions.")) {
                variableName = variableName.substring("versions.".length());
            }
            return resolveProperty(variables, variableName);
        }

        return normalizeVersion(normalized);
    }

    private String resolvePropertyToken(String token, Map<String, String> properties) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String current = token.trim();
        for (int index = 0; index < 8; index++) {
            if (!(current.startsWith("${") && current.endsWith("}"))) {
                break;
            }
            String propertyName = current.substring(2, current.length() - 1).trim();
            String resolved = properties.get(propertyName);
            if (resolved == null || resolved.isBlank()) {
                return null;
            }
            current = resolved.trim();
        }
        return normalizeVersion(current);
    }

    private String resolveProperty(Map<String, String> properties, String propertyName) {
        if (propertyName == null || propertyName.isBlank()) {
            return null;
        }
        String value = properties.get(propertyName);
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeVersion(value);
    }

    private String normalizeVersion(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.contains("${") || normalized.contains("$")) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ("latest.release".equals(lower) || "latest.integration".equals(lower)) {
            return null;
        }
        return normalized;
    }

    private String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private Path normalizeDirectory(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    private Element firstChildElement(Element parent, String localName) {
        for (Element child : childElements(parent, localName)) {
            return child;
        }
        return null;
    }

    private List<Element> childElements(Element parent, String localName) {
        if (parent == null || localName == null || localName.isBlank()) {
            return List.of();
        }
        List<Element> elements = new ArrayList<>();
        Node current = parent.getFirstChild();
        while (current != null) {
            if (current.getNodeType() == Node.ELEMENT_NODE && localName.equals(current.getNodeName())) {
                elements.add((Element) current);
            }
            current = current.getNextSibling();
        }
        return elements;
    }

    private String childText(Element parent, String localName) {
        Element child = firstChildElement(parent, localName);
        if (child == null) {
            return null;
        }
        return trimmedText(child.getTextContent());
    }

    private String trimmedText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized;
    }

    private record MavenDependencyDecl(
            String groupId,
            String artifactId,
            String version
    ) {
    }
}
