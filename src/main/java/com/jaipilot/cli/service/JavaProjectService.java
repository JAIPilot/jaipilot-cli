package com.jaipilot.cli.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class JavaProjectService {

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private final ProjectFileService fileService;
    private final CoverageReportService coverageReportService;

    public JavaProjectService(ProjectFileService fileService, CoverageReportService coverageReportService) {
        this.fileService = Objects.requireNonNull(fileService, "fileService");
        this.coverageReportService = Objects.requireNonNull(coverageReportService, "coverageReportService");
    }

    public Path resolveProjectRoot(Path workingDirectory) {
        Path root = fileService.findNearestBuildProjectRoot(workingDirectory);
        return root != null ? root : workingDirectory;
    }

    public BuildTool detectBuildTool(Path projectRoot) {
        return detectBuildToolIfPresent(projectRoot)
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to detect Maven or Gradle project under " + projectRoot
                ));
    }

    public Optional<BuildTool> detectBuildToolIfPresent(Path projectRoot) {
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return Optional.of(BuildTool.MAVEN);
        }
        if (Files.isRegularFile(projectRoot.resolve("build.gradle"))
                || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle.kts"))) {
            return Optional.of(BuildTool.GRADLE);
        }
        return Optional.empty();
    }

    public Optional<String> resolveBuildWrapper(Path projectRoot) {
        return detectBuildToolIfPresent(projectRoot)
                .flatMap(buildTool -> buildTool.wrapperCommand(projectRoot));
    }

    public JavaClassDescriptor resolveClass(Path projectRoot, String selector) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("A class selector is required.");
        }
        if (looksLikePath(selector)) {
            Path cutPath = fileService.resolvePath(projectRoot, Path.of(selector));
            return describeProductionClass(cutPath, projectRoot);
        }

        List<JavaClassDescriptor> classes = findProductionClasses(projectRoot);
        List<JavaClassDescriptor> matches = classes.stream()
                .filter(descriptor -> descriptor.fullyQualifiedName().equals(selector)
                        || descriptor.className().equals(selector))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("No Java production class matched selector: " + selector);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Class selector is ambiguous. Use a path or fully qualified name: " + selector
            );
        }
        return matches.get(0);
    }

    public List<JavaClassDescriptor> findChangedProductionClasses(Path projectRoot) {
        LinkedHashMap<Path, JavaClassDescriptor> classes = new LinkedHashMap<>();
        for (String candidate : changedPaths(projectRoot)) {
            if (!candidate.endsWith(".java") || candidate.contains("/src/test/")) {
                continue;
            }
            Path path = projectRoot.resolve(candidate).normalize();
            if (!Files.isRegularFile(path) || !isProductionJavaPath(path)) {
                continue;
            }
            classes.put(path, describeProductionClass(path, projectRoot));
        }
        return sorted(classes.values());
    }

    public List<JavaClassDescriptor> findProductionClasses(Path projectRoot) {
        List<JavaClassDescriptor> classes = new ArrayList<>();
        try (var paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isProductionJavaPath)
                    .forEach(path -> classes.add(describeProductionClass(path, projectRoot)));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java classes under " + projectRoot, exception);
        }
        return sorted(classes);
    }

    public List<JavaClassDescriptor> findClassesBelowCoverage(Path projectRoot, double threshold) {
        CoverageReportService.CoverageSnapshot snapshot = coverageReportService.readProjectSnapshot(projectRoot)
                .orElseThrow(() -> new IllegalStateException(
                        "No JaCoCo XML report found. Generate coverage first, then retry."
                ));
        return sorted(findProductionClasses(projectRoot).stream()
                .filter(descriptor -> snapshot.classCoverageByName()
                        .getOrDefault(
                                descriptor.fullyQualifiedName(),
                                new CoverageReportService.ClassCoverage(descriptor.fullyQualifiedName(), 0.0d, 0.0d)
                        )
                        .lineCoverage() < threshold)
                .collect(Collectors.toList()));
    }

    public boolean hasLikelyTests(JavaClassDescriptor descriptor) {
        return Files.isRegularFile(conventionalTestPath(descriptor)) || !findLikelyTests(descriptor).isEmpty();
    }

    public Map<JavaClassDescriptor, Boolean> likelyTestPresence(Collection<JavaClassDescriptor> descriptors) {
        Map<Path, List<JavaTestDescriptor>> testsByModule = new HashMap<>();
        Map<JavaClassDescriptor, Boolean> results = new LinkedHashMap<>();
        for (JavaClassDescriptor descriptor : descriptors) {
            if (Files.isRegularFile(conventionalTestPath(descriptor))) {
                results.put(descriptor, true);
                continue;
            }
            List<JavaTestDescriptor> moduleTests = testsByModule.computeIfAbsent(
                    descriptor.moduleRoot(),
                    this::scanTestDescriptors
            );
            boolean hasLikelyTest = moduleTests.stream()
                    .anyMatch(candidate -> scoreTestCandidate(descriptor, candidate) > 0);
            results.put(descriptor, hasLikelyTest);
        }
        return Map.copyOf(results);
    }
    public List<JavaTestDescriptor> findLikelyTests(JavaClassDescriptor descriptor) {
        List<ScoredTestDescriptor> candidates = scanTestDescriptors(descriptor.moduleRoot()).stream()
                .map(candidate -> new ScoredTestDescriptor(candidate, scoreTestCandidate(descriptor, candidate)))
                .filter(candidate -> candidate.score() > 0)
                .sorted()
                .toList();
        return candidates.stream().map(ScoredTestDescriptor::descriptor).toList();
    }

    public List<JavaTestDescriptor> findTouchedTests(
            JavaClassDescriptor descriptor,
            Map<Path, ProjectFileService.FileFingerprint> beforeSnapshot
    ) {
        Map<Path, ProjectFileService.FileFingerprint> afterSnapshot = fileService.snapshotJavaTestFiles(descriptor.moduleRoot());
        List<JavaTestDescriptor> touchedTests = new ArrayList<>();
        for (Map.Entry<Path, ProjectFileService.FileFingerprint> entry : afterSnapshot.entrySet()) {
            ProjectFileService.FileFingerprint before = beforeSnapshot.get(entry.getKey());
            if (entry.getValue().equals(before)) {
                continue;
            }
            touchedTests.add(describeTestClass(entry.getKey(), descriptor.projectRoot()));
        }
        if (touchedTests.size() <= 1) {
            return touchedTests;
        }
        return touchedTests.stream()
                .map(candidate -> new ScoredTestDescriptor(candidate, scoreTestCandidate(descriptor, candidate)))
                .sorted()
                .map(ScoredTestDescriptor::descriptor)
                .toList();
    }

    public boolean supportsCoverage(Path projectRoot) {
        return detectBuildTool(projectRoot).supportsCoverage(projectRoot);
    }

    private List<String> changedPaths(Path projectRoot) {
        try {
            ProcessExecutor processExecutor = new ProcessExecutor();
            List<String> paths = new ArrayList<>();
            paths.addAll(lines(processExecutor.execute(
                    List.of("git", "diff", "--name-only", "--cached", "--diff-filter=ACMR"),
                    projectRoot,
                    java.time.Duration.ofSeconds(30),
                    false,
                    new java.io.PrintWriter(System.err, true)
            ).output()));
            paths.addAll(lines(processExecutor.execute(
                    List.of("git", "diff", "--name-only", "--diff-filter=ACMR"),
                    projectRoot,
                    java.time.Duration.ofSeconds(30),
                    false,
                    new java.io.PrintWriter(System.err, true)
            ).output()));
            paths.addAll(lines(processExecutor.execute(
                    List.of("git", "ls-files", "--others", "--exclude-standard"),
                    projectRoot,
                    java.time.Duration.ofSeconds(30),
                    false,
                    new java.io.PrintWriter(System.err, true)
            ).output()));
            return paths.stream()
                    .map(String::trim)
                    .filter(path -> !path.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to inspect git changes under " + projectRoot, exception);
        }
    }

    private List<String> lines(String value) {
        return value == null ? List.of() : value.lines().toList();
    }

    private boolean isProductionJavaPath(Path path) {
        String normalized = normalize(path);
        return normalized.endsWith(".java")
                && normalized.contains("/src/main/java/")
                && !normalized.contains("/src/test/")
                && !"package-info.java".equals(path.getFileName().toString());
    }

    private boolean looksLikePath(String selector) {
        return selector.endsWith(".java") || selector.contains("/") || selector.contains("\\");
    }

    private JavaClassDescriptor describeProductionClass(Path cutPath, Path projectRoot) {
        if (!Files.isRegularFile(cutPath)) {
            throw new IllegalStateException("Class file not found: " + cutPath);
        }
        Path moduleRoot = Optional.ofNullable(fileService.findNearestBuildProjectRoot(cutPath)).orElse(projectRoot);
        Path relative = moduleRoot.relativize(cutPath).normalize();
        if (relative.getNameCount() < 4) {
            throw new IllegalStateException("Unsupported Java source layout for " + cutPath);
        }
        if (!"src".equals(relative.getName(0).toString())
                || !"main".equals(relative.getName(1).toString())
                || !"java".equals(relative.getName(2).toString())) {
            throw new IllegalStateException("Expected class under src/main/java: " + cutPath);
        }
        Path packagePath = relative.getNameCount() == 4
                ? Path.of("")
                : relative.subpath(3, relative.getNameCount() - 1);
        String packageName = normalize(packagePath).replace('/', '.');
        if (".".equals(packageName)) {
            packageName = "";
        }
        String className = fileService.stripJavaExtension(relative.getFileName().toString());
        String fullyQualifiedName = packageName.isBlank() ? className : packageName + "." + className;
        return new JavaClassDescriptor(
                projectRoot,
                moduleRoot,
                cutPath.normalize(),
                packageName,
                className,
                fullyQualifiedName
        );
    }

    public JavaTestDescriptor describeTestClass(Path testPath, Path projectRoot) {
        if (!Files.isRegularFile(testPath)) {
            throw new IllegalStateException("Test file not found: " + testPath);
        }
        Path moduleRoot = Optional.ofNullable(fileService.findNearestBuildProjectRoot(testPath)).orElse(projectRoot);
        String className = fileService.stripJavaExtension(testPath.getFileName().toString());
        String packageName = readPackageName(testPath);
        String fullyQualifiedName = packageName.isBlank() ? className : packageName + "." + className;
        return new JavaTestDescriptor(
                moduleRoot,
                testPath.normalize(),
                packageName,
                className,
                fullyQualifiedName
        );
    }

    private List<JavaClassDescriptor> sorted(Collection<JavaClassDescriptor> descriptors) {
        return descriptors.stream()
                .sorted(Comparator.comparing(JavaClassDescriptor::fullyQualifiedName))
                .toList();
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private List<JavaTestDescriptor> scanTestDescriptors(Path moduleRoot) {
        List<JavaTestDescriptor> tests = new ArrayList<>();
        try (var paths = Files.walk(moduleRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isTestJavaPath)
                    .forEach(path -> tests.add(describeTestClass(path, moduleRoot)));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java test files under " + moduleRoot, exception);
        }
        return tests;
    }

    private boolean isTestJavaPath(Path path) {
        String normalized = normalize(path);
        return normalized.endsWith(".java") && normalized.contains("/src/test/java/");
    }

    private Path conventionalTestPath(JavaClassDescriptor descriptor) {
        Path packagePath = descriptor.packageName().isBlank()
                ? Path.of("")
                : Path.of(descriptor.packageName().replace('.', '/'));
        return descriptor.moduleRoot()
                .resolve("src/test/java")
                .resolve(packagePath)
                .resolve(descriptor.className() + "Test.java")
                .normalize();
    }

    private int scoreTestCandidate(JavaClassDescriptor sourceDescriptor, JavaTestDescriptor testDescriptor) {
        int score = 0;
        boolean hasClassRelevance = false;
        if (testDescriptor.className().equals(sourceDescriptor.className() + "Test")) {
            score += 8;
            hasClassRelevance = true;
        } else if (testDescriptor.className().contains(sourceDescriptor.className())) {
            score += 4;
            hasClassRelevance = true;
        }
        String testContents = fileService.readFile(testDescriptor.testPath());
        if (testContents.contains(sourceDescriptor.fullyQualifiedName())) {
            score += 8;
            hasClassRelevance = true;
        } else if (containsWord(testContents, sourceDescriptor.className())) {
            score += 3;
            hasClassRelevance = true;
        }
        if (!hasClassRelevance) {
            return 0;
        }
        if (testDescriptor.packageName().equals(sourceDescriptor.packageName())) {
            score += 2;
        }
        return score;
    }

    private boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    private String readPackageName(Path path) {
        var matcher = PACKAGE_DECLARATION.matcher(fileService.readFile(path));
        return matcher.find() ? matcher.group(1) : "";
    }

    public enum BuildTool {
        MAVEN("maven") {
            @Override
            Optional<String> wrapperCommand(Path projectRoot) {
                return Files.isRegularFile(projectRoot.resolve("mvnw"))
                        && Files.isExecutable(projectRoot.resolve("mvnw"))
                        && Files.isRegularFile(projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties"))
                        ? Optional.of("./mvnw")
                        : Optional.empty();
            }
        },
        GRADLE("gradle") {
            @Override
            Optional<String> wrapperCommand(Path projectRoot) {
                return Files.isRegularFile(projectRoot.resolve("gradlew"))
                        && Files.isExecutable(projectRoot.resolve("gradlew"))
                        && Files.isRegularFile(projectRoot.resolve("gradle/wrapper/gradle-wrapper.properties"))
                        ? Optional.of("./gradlew")
                        : Optional.empty();
            }
        };

        private final String displayName;

        BuildTool(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        boolean supportsCoverage(Path moduleRoot) {
            try (var paths = Files.walk(moduleRoot, 2)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals("pom.xml")
                                || path.getFileName().toString().equals("build.gradle")
                                || path.getFileName().toString().equals("build.gradle.kts"))
                        .map(path -> {
                            try {
                                return Files.readString(path);
                            } catch (IOException exception) {
                                throw new IllegalStateException("Failed to read build file " + path, exception);
                            }
                        })
                        .anyMatch(contents -> contents.toLowerCase(Locale.ROOT).contains("jacoco"));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to inspect build files under " + moduleRoot, exception);
            }
        }

        abstract Optional<String> wrapperCommand(Path projectRoot);
    }

    public record JavaClassDescriptor(
            Path projectRoot,
            Path moduleRoot,
            Path cutPath,
            String packageName,
            String className,
            String fullyQualifiedName
    ) {
    }

    public record JavaTestDescriptor(
            Path moduleRoot,
            Path testPath,
            String packageName,
            String className,
            String fullyQualifiedName
    ) {
    }

    private record ScoredTestDescriptor(
            JavaTestDescriptor descriptor,
            int score
    ) implements Comparable<ScoredTestDescriptor> {
        @Override
        public int compareTo(ScoredTestDescriptor other) {
            int scoreCompare = Integer.compare(other.score, score);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return descriptor.testPath().compareTo(other.descriptor.testPath());
        }
    }
}
