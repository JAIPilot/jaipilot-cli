package com.jaipilot.cli.classpath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildToolClassResolutionService {

    private static final System.Logger LOGGER = System.getLogger(BuildToolClassResolutionService.class.getName());

    private final BuildToolClasspathResolver classpathResolver;
    private final ClassLocator classLocator;
    private final Set<String> compileFallbackAttemptedFingerprints;

    public BuildToolClassResolutionService() {
        this(new BuildToolClasspathResolver(), new ClasspathClassLocator(), ConcurrentHashMap.newKeySet());
    }

    BuildToolClassResolutionService(
            BuildToolClasspathResolver classpathResolver,
            ClassLocator classLocator,
            Set<String> compileFallbackAttemptedFingerprints
    ) {
        this.classpathResolver = classpathResolver;
        this.classLocator = classLocator;
        this.compileFallbackAttemptedFingerprints = compileFallbackAttemptedFingerprints;
    }

    public ResolvedClasspath resolveClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options) {
        return classpathResolver.resolveTestClasspath(projectRoot, moduleRoot, options);
    }

    public ClassResolutionResult locate(
            String fqcnOrImport,
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        ResolutionOptions normalizedOptions = options == null ? ResolutionOptions.defaults() : options;
        String normalizedFqcn = ClassNameParser.normalizeFqcn(fqcnOrImport);

        ResolvedClasspath classpath = classpathResolver.resolveTestClasspath(projectRoot, moduleRoot, normalizedOptions);
        ClassResolutionResult result = classLocator.locate(normalizedFqcn, classpath);
        if (result.kind() != LocationKind.NOT_FOUND) {
            return result;
        }

        if (!normalizedOptions.allowCompileFallback()) {
            LOGGER.log(System.Logger.Level.DEBUG, "compile fallback skipped for {0}", classpath.moduleRoot());
            return result;
        }

        if (!compileFallbackAttemptedFingerprints.add(classpath.fingerprint())) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "compile fallback skipped because it already ran for fingerprint {0}",
                    classpath.fingerprint());
            return result;
        }

        LOGGER.log(System.Logger.Level.INFO, "compile fallback triggered after class miss for module {0}", classpath.moduleRoot());
        ResolvedClasspath fallbackClasspath = classpathResolver.resolveTestClasspath(
                projectRoot,
                moduleRoot,
                normalizedOptions,
                true
        );
        return classLocator.locate(normalizedFqcn, fallbackClasspath);
    }

    public ClassResolutionResult locateOrThrow(
            String fqcnOrImport,
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        ClassResolutionResult result = locate(fqcnOrImport, projectRoot, moduleRoot, options);
        if (result.kind() != LocationKind.NOT_FOUND) {
            return result;
        }
        throw new ClasspathResolutionException(new ResolutionFailure(
                ResolutionFailureCategory.CLASS_NOT_FOUND_ON_RESOLVED_CLASSPATH,
                null,
                moduleRoot,
                "locate class " + fqcnOrImport,
                "Class was not found on resolved classpath."
        ));
    }

    public Optional<ResolvedSource> resolveSource(
            ClassResolutionResult classResult,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        return new DefaultSourceResolver(moduleRoot).resolveSource(classResult, options);
    }

    public ResolvedSource resolveSourceOrThrow(
            ClassResolutionResult classResult,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        return resolveSource(classResult, moduleRoot, options)
                .orElseThrow(() -> new ClasspathResolutionException(new ResolutionFailure(
                        ResolutionFailureCategory.SOURCE_NOT_AVAILABLE,
                        null,
                        moduleRoot,
                        "resolve source for " + (classResult == null ? "<null>" : classResult.fqcn()),
                        "Source was not available."
                )));
    }

    public Optional<ResolvedSource> resolveSourceByFqcn(
            String fqcnOrImport,
            Path projectRoot,
            Path moduleRoot,
            ResolutionOptions options
    ) {
        ResolutionOptions normalizedOptions = options == null ? ResolutionOptions.defaults() : options;
        String normalizedFqcn = ClassNameParser.normalizeFqcn(fqcnOrImport);
        ResolvedClasspath classpath = classpathResolver.resolveTestClasspath(projectRoot, moduleRoot, normalizedOptions);

        Optional<ResolvedSource> workspaceSource = resolveExactWorkspaceJava(
                normalizedFqcn,
                List.copyOf(workspaceSourceRoots(classpath)),
                classpath.moduleRoot()
        );
        if (workspaceSource.isPresent()) {
            return workspaceSource;
        }

        Optional<ResolvedSource> generatedSource = resolveExactWorkspaceJava(
                normalizedFqcn,
                generatedSourceRoots(classpath.moduleRoot()),
                classpath.moduleRoot()
        );
        if (generatedSource.isPresent()) {
            return generatedSource;
        }

        for (String candidateBinaryName : binaryNameCandidates(normalizedFqcn)) {
            ClassResolutionResult classResult = locate(
                    candidateBinaryName,
                    projectRoot,
                    moduleRoot,
                    normalizedOptions
            );
            if (classResult.kind() == LocationKind.NOT_FOUND) {
                continue;
            }
            Optional<ResolvedSource> resolved = resolveSource(classResult, moduleRoot, normalizedOptions);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedSource> resolveExactWorkspaceJava(
            String normalizedFqcn,
            List<Path> roots,
            Path moduleRoot
    ) {
        for (String sourceEntry : sourceEntryCandidates(normalizedFqcn)) {
            for (Path root : roots) {
                Path candidate = root.resolve(sourceEntry).toAbsolutePath().normalize();
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                return Optional.of(new ResolvedSource(
                        normalizedFqcn,
                        SourceOrigin.WORKSPACE_FILE,
                        candidate,
                        readSourceFile(candidate, moduleRoot)
                ));
            }
        }
        return Optional.empty();
    }

    private List<Path> workspaceSourceRoots(ResolvedClasspath classpath) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.addAll(classpath.mainSourceRoots());
        roots.addAll(classpath.testSourceRoots());
        return List.copyOf(roots);
    }

    private List<Path> generatedSourceRoots(Path moduleRoot) {
        if (moduleRoot == null) {
            return List.of();
        }

        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(moduleRoot.resolve("target/generated-sources"));
        roots.add(moduleRoot.resolve("target/generated-test-sources"));
        roots.add(moduleRoot.resolve("target"));
        roots.add(moduleRoot.resolve("build/generated/sources"));
        roots.add(moduleRoot.resolve("build/generated/test-sources"));
        roots.add(moduleRoot.resolve("build/generated"));
        return roots.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();
    }

    private List<String> sourceEntryCandidates(String normalizedFqcn) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizedFqcn.replace('.', '/') + ".java");

        int firstDollar = normalizedFqcn.indexOf('$');
        if (firstDollar > 0) {
            String outer = normalizedFqcn.substring(0, firstDollar);
            candidates.add(outer.replace('.', '/') + ".java");
        }

        String current = normalizedFqcn;
        while (true) {
            int lastDot = current.lastIndexOf('.');
            if (lastDot <= 0) {
                break;
            }
            String outer = current.substring(0, lastDot);
            String segment = lastSegment(outer);
            if (segment.isEmpty() || !Character.isUpperCase(segment.charAt(0))) {
                break;
            }
            candidates.add(outer.replace('.', '/') + ".java");
            current = outer;
        }
        return List.copyOf(candidates);
    }

    private List<String> binaryNameCandidates(String normalizedFqcn) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizedFqcn);

        int firstDollar = normalizedFqcn.indexOf('$');
        if (firstDollar > 0) {
            candidates.add(normalizedFqcn.substring(0, firstDollar));
        }

        List<Integer> trailingInnerBoundaries = trailingInnerBoundaries(normalizedFqcn);
        for (int depth = 1; depth <= trailingInnerBoundaries.size(); depth++) {
            char[] chars = normalizedFqcn.toCharArray();
            for (int index = 0; index < depth; index++) {
                chars[trailingInnerBoundaries.get(index)] = '$';
            }
            candidates.add(new String(chars));
        }

        String current = normalizedFqcn;
        while (true) {
            int lastDot = current.lastIndexOf('.');
            if (lastDot <= 0) {
                break;
            }
            String outer = current.substring(0, lastDot);
            String segment = lastSegment(outer);
            if (segment.isEmpty() || !Character.isUpperCase(segment.charAt(0))) {
                break;
            }
            candidates.add(outer);
            current = outer;
        }
        return List.copyOf(candidates);
    }

    private List<Integer> trailingInnerBoundaries(String normalizedFqcn) {
        List<Integer> boundaries = new ArrayList<>();
        int searchFrom = normalizedFqcn.length() - 1;
        while (true) {
            int boundary = normalizedFqcn.lastIndexOf('.', searchFrom);
            if (boundary <= 0) {
                break;
            }
            String segmentBeforeBoundary = segmentBefore(normalizedFqcn, boundary);
            if (segmentBeforeBoundary.isEmpty() || !Character.isUpperCase(segmentBeforeBoundary.charAt(0))) {
                break;
            }
            boundaries.add(boundary);
            searchFrom = boundary - 1;
        }
        return boundaries;
    }

    private String segmentBefore(String value, int boundaryIndex) {
        int previousBoundary = value.lastIndexOf('.', boundaryIndex - 1);
        return value.substring(previousBoundary + 1, boundaryIndex);
    }

    private String lastSegment(String value) {
        int boundary = value.lastIndexOf('.');
        if (boundary < 0) {
            return value;
        }
        return value.substring(boundary + 1);
    }

    private String readSourceFile(Path sourcePath, Path moduleRoot) {
        try {
            return Files.readString(sourcePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ClasspathResolutionException(new ResolutionFailure(
                    ResolutionFailureCategory.SOURCE_NOT_AVAILABLE,
                    null,
                    moduleRoot,
                    "read source " + sourcePath,
                    exception.getMessage()
            ), exception);
        }
    }
}
