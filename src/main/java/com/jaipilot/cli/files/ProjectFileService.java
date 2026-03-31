package com.jaipilot.cli.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ProjectFileService {

    private static final List<Path> JAVA_SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "test", "java")
    );

    public Path resolvePath(Path projectRoot, Path path) {
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return projectRoot.resolve(path).normalize();
    }

    public String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read file " + path, exception);
        }
    }

    public void writeFile(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write file " + path, exception);
        }
    }

    public Path deriveGeneratedTestPath(Path projectRoot, Path cutPath) {
        Path normalizedCutPath = cutPath.normalize();
        Path projectRelative = projectRoot.relativize(normalizedCutPath);
        Path rewritten = rewriteSourceRoot(projectRelative);
        if (rewritten != null) {
            return projectRoot.resolve(rewritten).normalize();
        }

        String packageName = extractPackageName(readFile(normalizedCutPath));
        Path baseDirectory = projectRoot.resolve("src/test/java");
        Path packagePath = packageName.isBlank() ? Path.of("") : Path.of(packageName.replace('.', '/'));
        return baseDirectory
                .resolve(packagePath)
                .resolve(stripJavaExtension(normalizedCutPath.getFileName().toString()) + "Test.java")
                .normalize();
    }

    public Path findNearestMavenProjectRoot(Path path) {
        Path current = path.normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public String deriveTestSelector(Path testPath) {
        String content = readFile(testPath.normalize());
        String className = stripJavaExtension(testPath.getFileName().toString());
        String packageName = extractPackageName(content);
        if (packageName.isBlank()) {
            return className;
        }
        return packageName + "." + className;
    }

    public List<String> readRequestedContextSources(Path projectRoot, List<String> requestedPaths) {
        return requestedPaths.stream()
                .map(path -> readFile(resolveRequestedContextPath(projectRoot, path)))
                .toList();
    }

    public List<String> readCachedContextEntries(Path projectRoot, List<String> contextPaths) {
        if (contextPaths == null || contextPaths.isEmpty()) {
            return List.of();
        }

        return contextPaths.stream()
                .map(path -> path + " =\n" + readFile(resolveRequestedContextPath(projectRoot, path)))
                .toList();
    }

    public List<String> resolveImportedContextClassPaths(Path projectRoot, Path sourcePath) {
        Set<String> resolvedPaths = new LinkedHashSet<>();
        for (String importTarget : extractImportTargets(readFile(sourcePath.normalize()))) {
            if (importTarget.endsWith(".*")) {
                resolvedPaths.addAll(resolveStarImportPaths(projectRoot, importTarget.substring(0, importTarget.length() - 2)));
                continue;
            }
            resolveImportedContextClassPath(projectRoot, importTarget).ifPresent(resolvedPaths::add);
        }
        return List.copyOf(resolvedPaths);
    }

    public String stripJavaExtension(String fileName) {
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - ".java".length());
        }
        return fileName;
    }

    private Path resolveRequestedContextPath(Path projectRoot, String requestedPath) {
        return resolveRequestedContextPathIfPresent(projectRoot, requestedPath)
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to resolve requested context class path " + requestedPath
                ));
    }

    private Optional<Path> resolveRequestedContextPathIfPresent(Path projectRoot, String requestedPath) {
        Path normalizedRequestedPath = Path.of(requestedPath.replace('\\', '/'));
        Path directMatch = projectRoot.resolve(normalizedRequestedPath).normalize();
        if (Files.isRegularFile(directMatch)) {
            return Optional.of(directMatch);
        }

        for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
            Path sourceRootMatch = projectRoot.resolve(sourceRoot).resolve(normalizedRequestedPath).normalize();
            if (Files.isRegularFile(sourceRootMatch)) {
                return Optional.of(sourceRootMatch);
            }
        }

        try (var paths = Files.walk(projectRoot)) {
            String suffix = "/" + requestedPath.replace('\\', '/');
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> normalizeSeparators(path).endsWith(suffix))
                    .findFirst()
                    .map(Path::normalize);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to search for context class path " + requestedPath, exception);
        }
    }

    private Optional<String> resolveImportedContextClassPath(Path projectRoot, String importTarget) {
        String candidate = importTarget;
        while (candidate.contains(".")) {
            String requestedPath = candidate.replace('.', '/') + ".java";
            Optional<Path> resolvedPath = resolveRequestedContextPathIfPresent(projectRoot, requestedPath);
            if (resolvedPath.isPresent()) {
                return Optional.of(toContextClassPath(resolvedPath.get()));
            }
            int lastDot = candidate.lastIndexOf('.');
            candidate = candidate.substring(0, lastDot);
        }
        return Optional.empty();
    }

    private List<String> resolveStarImportPaths(Path projectRoot, String importTarget) {
        Optional<String> importedContextClassPath = resolveImportedContextClassPath(projectRoot, importTarget);
        if (importedContextClassPath.isPresent()) {
            return List.of(importedContextClassPath.get());
        }
        return resolveWildcardImportPaths(projectRoot, importTarget);
    }

    private List<String> resolveWildcardImportPaths(Path projectRoot, String packageName) {
        String packagePath = packageName.replace('.', '/');
        String mainSuffix = "/src/main/java/" + packagePath;
        String testSuffix = "/src/test/java/" + packagePath;
        Set<String> resolvedPaths = new LinkedHashSet<>();

        for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
            Path candidateDirectory = projectRoot.resolve(sourceRoot).resolve(packagePath).normalize();
            if (Files.isDirectory(candidateDirectory)) {
                resolvedPaths.addAll(readPackageJavaFiles(candidateDirectory));
            }
        }

        try (var paths = Files.walk(projectRoot)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> {
                        String normalizedPath = normalizeSeparators(path);
                        return normalizedPath.endsWith(mainSuffix) || normalizedPath.endsWith(testSuffix);
                    })
                    .forEach(path -> resolvedPaths.addAll(readPackageJavaFiles(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to search wildcard import package " + packageName, exception);
        }

        return List.copyOf(resolvedPaths);
    }

    private List<String> readPackageJavaFiles(Path directory) {
        try (var paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::toContextClassPath)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read package directory " + directory, exception);
        }
    }

    private List<String> extractImportTargets(String sourceCode) {
        List<String> importTargets = new ArrayList<>();
        for (String line : sourceCode.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ") || !trimmed.endsWith(";")) {
                continue;
            }
            String importTarget = trimmed.substring("import ".length(), trimmed.length() - 1).trim();
            if (importTarget.startsWith("static ")) {
                importTarget = importTarget.substring("static ".length()).trim();
                if (!importTarget.endsWith(".*")) {
                    int lastDot = importTarget.lastIndexOf('.');
                    if (lastDot > 0) {
                        importTarget = importTarget.substring(0, lastDot);
                    }
                }
            }
            importTargets.add(importTarget);
        }
        return importTargets;
    }

    private String toContextClassPath(Path path) {
        Path normalizedPath = path.normalize();
        for (int index = 0; index <= normalizedPath.getNameCount() - 3; index++) {
            if (normalizedPath.getName(index).toString().equals("src")
                    && (normalizedPath.getName(index + 1).toString().equals("main")
                    || normalizedPath.getName(index + 1).toString().equals("test"))
                    && normalizedPath.getName(index + 2).toString().equals("java")) {
                return normalizeSeparators(normalizedPath.subpath(index + 3, normalizedPath.getNameCount()));
            }
        }
        return normalizeSeparators(normalizedPath);
    }

    private Path rewriteSourceRoot(Path projectRelative) {
        for (int index = 0; index <= projectRelative.getNameCount() - 3; index++) {
            if (projectRelative.getName(index).toString().equals("src")
                    && projectRelative.getName(index + 1).toString().equals("main")
                    && projectRelative.getName(index + 2).toString().equals("java")) {
                Path prefix = index == 0 ? null : projectRelative.subpath(0, index);
                Path suffix = projectRelative.subpath(index + 3, projectRelative.getNameCount());
                String className = stripJavaExtension(suffix.getFileName().toString()) + "Test.java";
                Path suffixParent = suffix.getParent();
                Path rewrittenSuffix = suffixParent == null ? Path.of(className) : suffixParent.resolve(className);
                Path testSourceRoot = Path.of("src", "test", "java");
                return prefix == null
                        ? testSourceRoot.resolve(rewrittenSuffix)
                        : prefix.resolve(testSourceRoot).resolve(rewrittenSuffix);
            }
        }
        return null;
    }

    private String extractPackageName(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("package "))
                .findFirst()
                .map(line -> line.substring("package ".length()).replace(";", "").trim())
                .orElse("");
    }

    private String normalizeSeparators(Path path) {
        return path.toString().replace('\\', '/');
    }
}
