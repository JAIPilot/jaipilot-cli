package com.jaipilot.cli.service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ProjectFileService {

    private static final System.Logger LOGGER = System.getLogger(ProjectFileService.class.getName());
    private static final String MISSING_CONTEXT_SOURCE_PLACEHOLDER = "Class not found";

    private static final List<Path> JAVA_SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "test", "java")
    );
    private static final List<String> GRADLE_BUILD_FILES = List.of(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts"
    );
    private static final Set<String> SKIPPED_SEARCH_DIRECTORY_NAMES = Set.of(
            ".git",
            ".gradle",
            ".idea",
            ".vscode",
            "target",
            "build",
            "out",
            "node_modules"
    );

    public ProjectFileService() {
    }

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

    public Path findNearestBuildProjectRoot(Path path) {
        Path current = path.normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (containsBuildFile(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public Path inferCutPathFromTestPath(Path projectRoot, Path testPath) {
        Path normalizedTestPath = testPath.normalize();
        Path normalizedProjectRoot = projectRoot.normalize();
        if (normalizedTestPath.startsWith(normalizedProjectRoot)) {
            Path relativeTestPath = normalizedProjectRoot.relativize(normalizedTestPath);
            Path rewritten = rewriteTestRoot(relativeTestPath);
            if (rewritten != null) {
                Path candidate = normalizedProjectRoot.resolve(rewritten).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }

        String testFileName = normalizedTestPath.getFileName() == null
                ? ""
                : normalizedTestPath.getFileName().toString();
        for (String suffix : List.of("Test.java", "Tests.java", "IT.java", "ITCase.java")) {
            if (!testFileName.endsWith(suffix)) {
                continue;
            }
            String candidateName = testFileName.substring(0, testFileName.length() - suffix.length()) + ".java";
            try (var paths = Files.walk(normalizedProjectRoot)) {
                Optional<Path> candidate = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName() != null && path.getFileName().toString().equals(candidateName))
                        .filter(path -> normalizeSeparators(path).contains("/src/main/java/"))
                        .findFirst();
                if (candidate.isPresent()) {
                    return candidate.get().normalize();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to infer class under test for " + testPath, exception);
            }
        }
        return null;
    }

    private String readContextSourceOrPlaceholder(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        try {
            return readContextSource(projectRoot, preferredSourcePath, requestedPath);
        } catch (IllegalStateException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Context source unavailable for {0}; using placeholder. Reason: {1}",
                    requestedPath,
                    exception.getMessage()
            );
            return MISSING_CONTEXT_SOURCE_PLACEHOLDER;
        }
    }

    public List<String> readContextEntries(Path projectRoot, List<String> contextPaths) {
        return readContextEntries(projectRoot, null, contextPaths);
    }

    public List<String> readContextEntries(Path projectRoot, Path preferredSourcePath, List<String> contextPaths) {
        if (contextPaths == null || contextPaths.isEmpty()) {
            return List.of();
        }

        return contextPaths.stream()
                .map(path -> path + " =\n" + readContextSourceOrPlaceholder(projectRoot, preferredSourcePath, path))
                .toList();
    }

    public String stripJavaExtension(String fileName) {
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - ".java".length());
        }
        return fileName;
    }

    private String readContextSource(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        String normalizedContextPath = normalizeContextPath(requestedPath);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Resolving context source from workspace [requestedPath={0}, normalizedPath={1}]",
                requestedPath,
                normalizedContextPath
        );
        Optional<Path> localPath = resolveRequestedContextPathIfPresent(projectRoot, preferredSourcePath, requestedPath);
        if (localPath.isPresent()) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Resolved context source from workspace file for {0}: {1}",
                    requestedPath,
                    localPath.get().toAbsolutePath().normalize()
            );
            return readFile(localPath.get());
        }

        LOGGER.log(
                System.Logger.Level.WARNING,
                "Unable to resolve context source for {0} in workspace",
                requestedPath
        );
        throw new IllegalStateException(unresolvedContextMessage(requestedPath));
    }

    private Optional<Path> resolveRequestedContextPathIfPresent(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        List<Path> candidates = preferredCandidates(projectRoot, preferredSourcePath, requestedPath);
        logCandidatePathsForContextLookup(requestedPath, candidates);
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "Matched requested context path {0} to workspace file {1}",
                        requestedPath,
                        candidate.toAbsolutePath().normalize()
                );
                return Optional.of(candidate.normalize());
            }
        }

        String normalizedSuffix = normalizeSuffix(requestedPath);
        LOGGER.log(
                System.Logger.Level.INFO,
                "No direct file match for context path {0}; scanning project sources by suffix {1}",
                requestedPath,
                normalizedSuffix
        );
        try {
            Optional<Path> resolvedPath = collectProjectJavaSources(projectRoot).stream()
                    .filter(path -> matchesRequestedSuffix(projectRoot, path, normalizedSuffix))
                    .sorted(preferredPathComparator(projectRoot, preferredSourcePath, normalizedSuffix))
                    .findFirst()
                    .map(Path::normalize);
            if (resolvedPath.isPresent()) {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "Resolved context path {0} by suffix scan to {1}",
                        requestedPath,
                        resolvedPath.get().toAbsolutePath().normalize()
                );
            } else {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Context path {0} not found in workspace source scan",
                        requestedPath
                );
            }
            return resolvedPath;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to search for context class path " + requestedPath, exception);
        }
    }


    private boolean containsBuildFile(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml")) || containsGradleBuildFile(directory);
    }

    private boolean containsGradleBuildFile(Path directory) {
        return GRADLE_BUILD_FILES.stream()
                .map(directory::resolve)
                .anyMatch(Files::isRegularFile);
    }

    private List<Path> preferredCandidates(Path projectRoot, Path preferredSourcePath, String requestedPath) {
        List<Path> candidates = new ArrayList<>();
        for (Path normalizedRequestedPath : requestedPathVariants(requestedPath)) {
            candidates.add(projectRoot.resolve(normalizedRequestedPath).normalize());
            for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
                candidates.add(projectRoot.resolve(sourceRoot).resolve(normalizedRequestedPath).normalize());
            }
            for (Path preferredRoot : preferredSearchRoots(projectRoot, preferredSourcePath)) {
                candidates.add(preferredRoot.resolve(normalizedRequestedPath).normalize());
                for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
                    candidates.add(preferredRoot.resolve(sourceRoot).resolve(normalizedRequestedPath).normalize());
                }
            }
        }
        return candidates.stream()
                .distinct()
                .toList();
    }

    private List<Path> preferredSearchRoots(Path projectRoot, Path preferredSourcePath) {
        if (preferredSourcePath == null) {
            return List.of();
        }

        List<Path> roots = new ArrayList<>();
        Path normalizedProjectRoot = projectRoot.normalize();
        Path current = preferredSourcePath.normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null && current.startsWith(normalizedProjectRoot)) {
            if (containsBuildFile(current)) {
                roots.add(current);
            }
            current = current.getParent();
        }
        return roots.stream().distinct().toList();
    }

    private List<Path> requestedPathVariants(String requestedPath) {
        List<String> candidates = new ArrayList<>();
        String normalized = requestedPath == null ? "" : requestedPath.trim().replace('\\', '/');
        if (!normalized.isBlank()) {
            candidates.add(normalized);
            if (!normalized.endsWith(".java")) {
                candidates.add(normalized + ".java");
            }
            if (normalized.contains(".")) {
                String dotted = normalized.replace('.', '/');
                candidates.add(dotted);
                if (!dotted.endsWith(".java")) {
                    candidates.add(dotted + ".java");
                }
            }
        }
        return candidates.stream()
                .map(Path::of)
                .distinct()
                .toList();
    }

    private Comparator<Path> preferredPathComparator(Path projectRoot, Path preferredSourcePath, String normalizedSuffix) {
        List<Path> preferredRoots = preferredSearchRoots(projectRoot, preferredSourcePath);
        return Comparator
                .comparingInt((Path path) -> preferredRootIndex(preferredRoots, path))
                .thenComparingInt(path -> relativeDepth(projectRoot, path, normalizedSuffix))
                .thenComparing(this::normalizeSeparators);
    }

    private int preferredRootIndex(List<Path> preferredRoots, Path path) {
        for (int index = 0; index < preferredRoots.size(); index++) {
            if (path.normalize().startsWith(preferredRoots.get(index))) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int relativeDepth(Path projectRoot, Path path, String normalizedSuffix) {
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedPath = path.normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return Integer.MAX_VALUE;
        }
        String relativePath = normalizeSeparators(normalizedRoot.relativize(normalizedPath));
        if (!relativePath.endsWith(normalizedSuffix)) {
            return Integer.MAX_VALUE;
        }
        return relativePath.length() - normalizedSuffix.length();
    }

    private boolean matchesRequestedSuffix(Path projectRoot, Path path, String normalizedSuffix) {
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedPath = path.normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }
        String relativePath = normalizeSeparators(normalizedRoot.relativize(normalizedPath));
        if (relativePath.equals(normalizedSuffix)) {
            return true;
        }
        return relativePath.endsWith("/" + normalizedSuffix);
    }

    private List<Path> collectProjectJavaSources(Path projectRoot) throws IOException {
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (shouldSkipDirectory(projectRoot, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isProjectJavaSource(projectRoot, file)) {
                    sources.add(file.normalize());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sources;
    }

    private boolean shouldSkipDirectory(Path projectRoot, Path directory) {
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedDirectory = directory.normalize();
        if (normalizedDirectory.equals(normalizedRoot)) {
            return false;
        }
        Path fileName = normalizedDirectory.getFileName();
        if (fileName == null) {
            return false;
        }
        return SKIPPED_SEARCH_DIRECTORY_NAMES.contains(fileName.toString());
    }

    private boolean isProjectJavaSource(Path projectRoot, Path path) {
        if (!Files.isRegularFile(path) || path.getFileName() == null || !path.getFileName().toString().endsWith(".java")) {
            return false;
        }
        Path normalizedRoot = projectRoot.normalize();
        Path normalizedPath = path.normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }
        String relativePath = normalizeSeparators(normalizedRoot.relativize(normalizedPath));
        return isSourcePath(relativePath);
    }

    private boolean isSourcePath(String normalizedRelativePath) {
        return normalizedRelativePath.equals("src/main/java")
                || normalizedRelativePath.equals("src/test/java")
                || normalizedRelativePath.startsWith("src/main/java/")
                || normalizedRelativePath.startsWith("src/test/java/")
                || normalizedRelativePath.contains("/src/main/java/")
                || normalizedRelativePath.contains("/src/test/java/");
    }

    private String normalizeSuffix(String requestedPath) {
        return requestedPathVariants(requestedPath).stream()
                .map(this::normalizeSeparators)
                .findFirst()
                .orElse("");
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

    private Path rewriteTestRoot(Path projectRelative) {
        for (int index = 0; index <= projectRelative.getNameCount() - 3; index++) {
            if (projectRelative.getName(index).toString().equals("src")
                    && projectRelative.getName(index + 1).toString().equals("test")
                    && projectRelative.getName(index + 2).toString().equals("java")) {
                Path prefix = index == 0 ? null : projectRelative.subpath(0, index);
                Path suffix = projectRelative.subpath(index + 3, projectRelative.getNameCount());
                String className = stripTestSuffix(suffix.getFileName().toString());
                Path suffixParent = suffix.getParent();
                Path rewrittenSuffix = suffixParent == null ? Path.of(className) : suffixParent.resolve(className);
                Path mainSourceRoot = Path.of("src", "main", "java");
                return prefix == null
                        ? mainSourceRoot.resolve(rewrittenSuffix)
                        : prefix.resolve(mainSourceRoot).resolve(rewrittenSuffix);
            }
        }
        return null;
    }

    private String stripTestSuffix(String fileName) {
        for (String suffix : List.of("Test.java", "Tests.java", "IT.java", "ITCase.java")) {
            if (fileName.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length()) + ".java";
            }
        }
        return fileName;
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

    private String normalizeContextPath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            return "";
        }
        String normalized = requestedPath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("src/main/java/")) {
            return normalized.substring("src/main/java/".length());
        }
        if (normalized.startsWith("src/test/java/")) {
            return normalized.substring("src/test/java/".length());
        }
        int mainSegment = normalized.indexOf("/src/main/java/");
        if (mainSegment >= 0) {
            return normalized.substring(mainSegment + "/src/main/java/".length());
        }
        int testSegment = normalized.indexOf("/src/test/java/");
        if (testSegment >= 0) {
            return normalized.substring(testSegment + "/src/test/java/".length());
        }
        return normalized;
    }

    private String unresolvedContextMessage(String requestedPath) {
        return "Unable to resolve requested context class path " + requestedPath
                + ". Checked workspace sources under src/main/java and src/test/java.";
    }

    private void logCandidatePathsForContextLookup(String requestedPath, List<Path> candidates) {
        if (!LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            return;
        }
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Workspace candidate paths for {0}: {1}",
                requestedPath,
                candidates.size()
        );
        int maxEntries = Math.min(candidates.size(), 25);
        for (int index = 0; index < maxEntries; index++) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "candidate[{0}]={1}",
                    index,
                    candidates.get(index).toAbsolutePath().normalize()
            );
        }
        if (candidates.size() > maxEntries) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "... omitted {0} additional candidates",
                    candidates.size() - maxEntries
            );
        }
    }
}
