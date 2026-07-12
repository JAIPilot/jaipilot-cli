package com.jaipilot.cli.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public final class JavaProjectService {

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
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.isRegularFile(projectRoot.resolve("build.gradle"))
                || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        throw new IllegalStateException("Unable to detect Maven or Gradle project under " + projectRoot);
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

    public boolean supportsCoverage(Path projectRoot) {
        return detectBuildTool(projectRoot).supportsCoverage(projectRoot);
    }

    public Optional<List<String>> buildCoverageCommand(JavaClassDescriptor descriptor) {
        BuildTool buildTool = detectBuildTool(descriptor.moduleRoot());
        if (!buildTool.supportsCoverage(descriptor.moduleRoot())) {
            return Optional.empty();
        }
        return Optional.of(buildTool.coverageCommand(descriptor));
    }

    public List<String> buildValidationCommand(JavaClassDescriptor descriptor) {
        BuildTool buildTool = detectBuildTool(descriptor.moduleRoot());
        return buildTool.testCommand(descriptor);
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
                && !normalized.contains("/src/test/");
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
        Path testPath = moduleRoot.resolve("src/test/java").resolve(packagePath).resolve(className + "Test.java").normalize();
        String testClassName = className + "Test";
        String testFullyQualifiedName = packageName.isBlank() ? testClassName : packageName + "." + testClassName;
        return new JavaClassDescriptor(
                projectRoot,
                moduleRoot,
                cutPath.normalize(),
                packageName,
                className,
                fullyQualifiedName,
                testPath,
                testClassName,
                testFullyQualifiedName
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

    public enum BuildTool {
        MAVEN("maven") {
            @Override
            List<String> testCommand(JavaClassDescriptor descriptor) {
                String executable = Files.isRegularFile(descriptor.moduleRoot().resolve("mvnw")) ? "./mvnw" : "mvn";
                return List.of(executable, "-Dtest=" + descriptor.testClassName(), "test");
            }

            @Override
            List<String> coverageCommand(JavaClassDescriptor descriptor) {
                String executable = Files.isRegularFile(descriptor.moduleRoot().resolve("mvnw")) ? "./mvnw" : "mvn";
                return List.of(executable, "-Dtest=" + descriptor.testClassName(), "test", "jacoco:report");
            }
        },
        GRADLE("gradle") {
            @Override
            List<String> testCommand(JavaClassDescriptor descriptor) {
                String executable = Files.isRegularFile(descriptor.moduleRoot().resolve("gradlew")) ? "./gradlew" : "gradle";
                return List.of(executable, "test", "--tests", descriptor.testFullyQualifiedName());
            }

            @Override
            List<String> coverageCommand(JavaClassDescriptor descriptor) {
                String executable = Files.isRegularFile(descriptor.moduleRoot().resolve("gradlew")) ? "./gradlew" : "gradle";
                return List.of(executable, "test", "jacocoTestReport", "--tests", descriptor.testFullyQualifiedName());
            }
        };

        private final String displayName;

        BuildTool(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        abstract List<String> testCommand(JavaClassDescriptor descriptor);

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

        abstract List<String> coverageCommand(JavaClassDescriptor descriptor);
    }

    public record JavaClassDescriptor(
            Path projectRoot,
            Path moduleRoot,
            Path cutPath,
            String packageName,
            String className,
            String fullyQualifiedName,
            Path testPath,
            String testClassName,
            String testFullyQualifiedName
    ) {
    }
}
