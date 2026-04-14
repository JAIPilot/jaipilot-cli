package com.jaipilot.cli.service;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.process.BuildTool;
import com.jaipilot.cli.process.ExecutionResult;
import com.jaipilot.cli.process.GradleCommandBuilder;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import java.io.File;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JunitLlmSessionRunner {

    private static final int MAX_INTERACTIONS = 10;
    private static final int MAX_FETCH_ATTEMPTS = 120;
    private static final long FETCH_DELAY_MILLIS = 1_000L;
    private static final String MISSING_CONTEXT_CLASS_PLACEHOLDER = "Class not found";
    private static final String MAVEN_CLASSPATH_OUTPUT_FILE = ".classpath.txt";
    private static final String GRADLE_CLASSPATH_OUTPUT_FILE = ".gradle-classpath.txt";
    private static final Duration CONTEXT_RESOLUTION_TIMEOUT = Duration.ofSeconds(120);
    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());
    private static final String GRADLE_CLASSPATH_INIT_SCRIPT = """
            allprojects { project ->
                project.tasks.register("jaipilotWriteCompileClasspath") {
                    doLast {
                        String outputPath = project.findProperty("jaipilotClasspathOutput")?.toString()
                        if (!outputPath) {
                            throw new GradleException("Missing -PjaipilotClasspathOutput")
                        }
                        def javaPluginApplied = project.plugins.hasPlugin("java")
                                || project.plugins.hasPlugin("java-library")
                        def entries = new LinkedHashSet<String>()
                        if (javaPluginApplied) {
                            def sourceSets = project.extensions.findByName("sourceSets")
                            def mainSourceSet = sourceSets == null ? null : sourceSets.findByName("main")
                            if (mainSourceSet != null) {
                                mainSourceSet.output.classesDirs.files.each { file ->
                                    if (file != null) {
                                        entries.add(file.absolutePath)
                                    }
                                }
                                if (mainSourceSet.output.resourcesDir != null) {
                                    entries.add(mainSourceSet.output.resourcesDir.absolutePath)
                                }
                                mainSourceSet.compileClasspath.files.each { file ->
                                    if (file != null) {
                                        entries.add(file.absolutePath)
                                    }
                                }
                            }
                        }
                        new File(outputPath).text = entries.join(File.pathSeparator)
                    }
                }
            }
            """;

    private final JunitLlmBackendClient backendClient;
    private final ProjectFileService fileService;
    private final UsedContextClassPathCache usedContextClassPathCache;
    private final JunitLlmConsoleLogger consoleLogger;
    private final MavenCommandBuilder mavenCommandBuilder;
    private final GradleCommandBuilder gradleCommandBuilder;
    private final ProcessExecutor processExecutor;
    private final MockitoVersionResolver mockitoVersionResolver;

    public JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            UsedContextClassPathCache usedContextClassPathCache,
            JunitLlmConsoleLogger consoleLogger
    ) {
        this(
                backendClient,
                fileService,
                usedContextClassPathCache,
                consoleLogger,
                new MavenCommandBuilder(),
                new GradleCommandBuilder(),
                new ProcessExecutor(),
                new MockitoVersionResolver(fileService)
        );
    }

    JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            UsedContextClassPathCache usedContextClassPathCache,
            JunitLlmConsoleLogger consoleLogger,
            MavenCommandBuilder mavenCommandBuilder,
            GradleCommandBuilder gradleCommandBuilder,
            ProcessExecutor processExecutor,
            MockitoVersionResolver mockitoVersionResolver
    ) {
        this.backendClient = backendClient;
        this.fileService = fileService;
        this.usedContextClassPathCache = usedContextClassPathCache;
        this.consoleLogger = consoleLogger;
        this.mavenCommandBuilder = mavenCommandBuilder == null ? new MavenCommandBuilder() : mavenCommandBuilder;
        this.gradleCommandBuilder = gradleCommandBuilder == null ? new GradleCommandBuilder() : gradleCommandBuilder;
        this.processExecutor = processExecutor == null ? new ProcessExecutor() : processExecutor;
        this.mockitoVersionResolver = mockitoVersionResolver == null
                ? new MockitoVersionResolver(fileService)
                : mockitoVersionResolver;
    }

    public JunitLlmSessionResult run(JunitLlmSessionRequest sessionRequest) throws Exception {
        String cutCode = fileService.readFile(sessionRequest.cutPath());
        String cutName = fileService.stripJavaExtension(sessionRequest.cutPath().getFileName().toString());
        String testClassName = fileService.stripJavaExtension(sessionRequest.outputPath().getFileName().toString());
        Path cacheKeyPath = cacheKeyPath(sessionRequest);
        List<String> importedContextPaths = fileService.resolveImportedContextClassPaths(
                sessionRequest.projectRoot(),
                sessionRequest.cutPath()
        );
        List<String> cachedContextPaths = usedContextClassPathCache.read(cacheKeyPath);
        consoleLogger.announceCacheRead(cacheKeyPath, cachedContextPaths);
        String mockitoVersion = mockitoVersionResolver.resolve(
                sessionRequest.projectRoot(),
                sessionRequest.cutPath()
        );

        String currentSessionId = blankToNull(sessionRequest.sessionId());
        String currentTestCode = normalizeNullableText(sessionRequest.newTestClassCode());
        List<String> requestedContextClasses = List.of();

        for (int interaction = 1; interaction <= MAX_INTERACTIONS; interaction++) {
            consoleLogger.announceStatus(sessionRequest.operation());
            InvokeJunitLlmRequest invokeRequest = new InvokeJunitLlmRequest(
                    currentSessionId,
                    sessionRequest.operation().apiValue(),
                    cutName,
                    testClassName,
                    mockitoVersion,
                    cutCode,
                    buildCachedContextClasses(
                            sessionRequest.projectRoot(),
                            sessionRequest.cutPath(),
                            importedContextPaths,
                            cachedContextPaths
                    ),
                    normalizeText(sessionRequest.initialTestClassCode()),
                    requestedContextClasses,
                    normalizeText(currentTestCode),
                    blankToNull(sessionRequest.clientLogs())
            );
            InvokeJunitLlmResponse invokeResponse = backendClient.invoke(invokeRequest);
            currentSessionId = firstNonBlank(invokeResponse.sessionId(), currentSessionId);

            FetchJobResponse fetchJobResponse = pollJob(invokeResponse.jobId());
            currentSessionId = mergeSessionId(currentSessionId, fetchJobResponse);

            FetchJobResponse.FetchJobOutput output = requireOutput(fetchJobResponse);
            List<String> usedContextClassPaths = output.usedContextClassPaths() == null
                    ? List.of()
                    : output.usedContextClassPaths();
            usedContextClassPathCache.write(cacheKeyPath, usedContextClassPaths);
            boolean hasFinalTestFile = output.finalTestFile() != null && !output.finalTestFile().isBlank();
            if (hasFinalTestFile) {
                currentTestCode = output.finalTestFile();
            }

            List<String> requiredContextPaths = output.requiredContextClassPaths();
            if (requiredContextPaths != null && !requiredContextPaths.isEmpty()) {
                printContextPaths(requiredContextPaths);
                requestedContextClasses = resolveRequiredContextClassesViaJavap(
                        sessionRequest,
                        requiredContextPaths
                );
                if (!hasFinalTestFile) {
                    continue;
                }
            }

            if (currentTestCode == null || currentTestCode.isBlank()) {
                throw new IllegalStateException("Backend did not return a test file.");
            }

            fileService.writeFile(sessionRequest.outputPath(), currentTestCode);
            return new JunitLlmSessionResult(
                    currentSessionId,
                    sessionRequest.outputPath(),
                    usedContextClassPaths
            );
        }

        throw new IllegalStateException("Exceeded the maximum number of backend interactions.");
    }

    private FetchJobResponse pollJob(String jobId) throws Exception {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalStateException("Backend did not return a job.");
        }
        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            FetchJobResponse response = backendClient.fetchJob(jobId);
            String status = normalizeStatus(response.status());

            if (isDone(status)) {
                return response;
            }
            if (isFailure(status)) {
                throw new IllegalStateException(firstNonBlank(
                        response.errorMessage(),
                        "Backend job failed."
                ));
            }
            Thread.sleep(FETCH_DELAY_MILLIS);
        }
        throw new IllegalStateException("Timed out while waiting for backend response.");
    }

    private FetchJobResponse.FetchJobOutput requireOutput(FetchJobResponse response) {
        if (response.output() == null) {
            throw new IllegalStateException(firstNonBlank(
                    response.errorMessage(),
                    "Backend response was empty."
            ));
        }
        return response.output();
    }

    private String mergeSessionId(String currentSessionId, FetchJobResponse response) {
        if (response.output() == null) {
            return currentSessionId;
        }
        return firstNonBlank(response.output().sessionId(), currentSessionId);
    }

    private boolean isDone(String status) {
        return "done".equals(status) || "completed".equals(status) || "success".equals(status);
    }

    private boolean isFailure(String status) {
        return "error".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value;
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private void printContextPaths(List<String> requiredContextPaths) {
        for (String requiredContextPath : requiredContextPaths) {
            consoleLogger.announceRequiredContextPath(requiredContextPath);
        }
    }

    private Path cacheKeyPath(JunitLlmSessionRequest sessionRequest) {
        return sessionRequest.cutPath().toAbsolutePath().normalize();
    }

    private List<String> resolveRequiredContextClassesViaJavap(
            JunitLlmSessionRequest sessionRequest,
            List<String> requiredContextClassNames
    ) {
        if (requiredContextClassNames == null || requiredContextClassNames.isEmpty()) {
            return List.of();
        }

        BuildTool buildTool = fileService.detectBuildTool(sessionRequest.projectRoot(), null).orElse(null);
        if (buildTool == BuildTool.MAVEN) {
            return resolveRequiredContextClassesViaMavenJavap(sessionRequest, requiredContextClassNames);
        }
        if (buildTool == BuildTool.GRADLE) {
            return resolveRequiredContextClassesViaGradleJavap(sessionRequest, requiredContextClassNames);
        }
        return missingContextClasses(requiredContextClassNames);
    }

    private List<String> resolveRequiredContextClassesViaMavenJavap(
            JunitLlmSessionRequest sessionRequest,
            List<String> requiredContextClassNames
    ) {
        Path moduleRoot = resolveMavenModuleRoot(sessionRequest);
        ExecutionResult classpathBuildResult;
        try {
            classpathBuildResult = processExecutor.execute(
                    mavenCommandBuilder.buildCompileClasspath(
                            moduleRoot,
                            null,
                            List.of("-q"),
                            MAVEN_CLASSPATH_OUTPUT_FILE
                    ),
                    moduleRoot,
                    CONTEXT_RESOLUTION_TIMEOUT,
                    false,
                    QUIET_WRITER
            );
        } catch (Exception exception) {
            return missingContextClasses(requiredContextClassNames);
        }
        if (!isSuccessful(classpathBuildResult)) {
            return missingContextClasses(requiredContextClassNames);
        }

        Path classpathFile = moduleRoot.resolve(MAVEN_CLASSPATH_OUTPUT_FILE).toAbsolutePath().normalize();
        if (!Files.isRegularFile(classpathFile)) {
            return missingContextClasses(requiredContextClassNames);
        }

        String dependencyClasspath;
        try {
            dependencyClasspath = Files.readString(classpathFile, StandardCharsets.UTF_8).trim();
        } catch (Exception exception) {
            return missingContextClasses(requiredContextClassNames);
        }
        String javapClasspath = moduleRoot.resolve("target/classes").toAbsolutePath().normalize()
                + (dependencyClasspath.isBlank() ? "" : File.pathSeparator + dependencyClasspath);
        return runJavapForClasses(moduleRoot, javapClasspath, requiredContextClassNames);
    }

    private List<String> resolveRequiredContextClassesViaGradleJavap(
            JunitLlmSessionRequest sessionRequest,
            List<String> requiredContextClassNames
    ) {
        Path projectRoot = sessionRequest.projectRoot().toAbsolutePath().normalize();
        String gradleProjectPath = fileService.deriveGradleProjectPath(projectRoot, sessionRequest.cutPath());
        Path classpathFile = projectRoot.resolve(GRADLE_CLASSPATH_OUTPUT_FILE).toAbsolutePath().normalize();
        Path initScript;
        try {
            initScript = Files.createTempFile("jaipilot-gradle-classpath-", ".gradle");
            Files.writeString(initScript, GRADLE_CLASSPATH_INIT_SCRIPT, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return missingContextClasses(requiredContextClassNames);
        }

        ExecutionResult classpathBuildResult;
        try {
            classpathBuildResult = processExecutor.execute(
                    gradleCommandBuilder.buildCompileClasspath(
                            projectRoot,
                            null,
                            List.of("--quiet"),
                            gradleProjectPath,
                            initScript,
                            classpathFile
                    ),
                    projectRoot,
                    CONTEXT_RESOLUTION_TIMEOUT,
                    false,
                    QUIET_WRITER
            );
        } catch (Exception exception) {
            tryDelete(initScript);
            return missingContextClasses(requiredContextClassNames);
        }
        tryDelete(initScript);
        if (!isSuccessful(classpathBuildResult) || !Files.isRegularFile(classpathFile)) {
            return missingContextClasses(requiredContextClassNames);
        }

        String javapClasspath;
        try {
            javapClasspath = Files.readString(classpathFile, StandardCharsets.UTF_8).trim();
        } catch (Exception exception) {
            return missingContextClasses(requiredContextClassNames);
        }
        if (javapClasspath.isBlank()) {
            return missingContextClasses(requiredContextClassNames);
        }
        return runJavapForClasses(projectRoot, javapClasspath, requiredContextClassNames);
    }

    private List<String> runJavapForClasses(Path workingDirectory, String javapClasspath, List<String> classNames) {
        List<String> resolvedContextClasses = new ArrayList<>();
        for (String requiredContextClassName : classNames) {
            String className = normalizeRequiredClassName(requiredContextClassName);
            if (className.isBlank()) {
                resolvedContextClasses.add(MISSING_CONTEXT_CLASS_PLACEHOLDER);
                continue;
            }
            ExecutionResult javapResult;
            try {
                javapResult = processExecutor.execute(
                        List.of("javap", "-classpath", javapClasspath, className),
                        workingDirectory,
                        CONTEXT_RESOLUTION_TIMEOUT,
                        false,
                        QUIET_WRITER
                );
            } catch (Exception exception) {
                resolvedContextClasses.add(MISSING_CONTEXT_CLASS_PLACEHOLDER);
                continue;
            }
            if (!isSuccessful(javapResult) || javapResult.output() == null || javapResult.output().isBlank()) {
                resolvedContextClasses.add(MISSING_CONTEXT_CLASS_PLACEHOLDER);
            } else {
                resolvedContextClasses.add(javapResult.output());
            }
        }
        return List.copyOf(resolvedContextClasses);
    }

    private String normalizeRequiredClassName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("import ")) {
            normalized = normalized.substring("import ".length()).trim();
        }
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length()).trim();
        }
        if (normalized.endsWith(".java")) {
            normalized = normalized.substring(0, normalized.length() - ".java".length()).trim();
        }
        if (normalized.startsWith("src/main/java/")) {
            normalized = normalized.substring("src/main/java/".length());
        }
        if (normalized.startsWith("src/test/java/")) {
            normalized = normalized.substring("src/test/java/".length());
        }
        normalized = normalized.replace('/', '.').replace('\\', '.');
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    private Path resolveMavenModuleRoot(JunitLlmSessionRequest sessionRequest) {
        Path moduleRoot = fileService.findNearestMavenProjectRoot(sessionRequest.cutPath());
        if (moduleRoot != null) {
            return moduleRoot.toAbsolutePath().normalize();
        }
        return sessionRequest.projectRoot().toAbsolutePath().normalize();
    }

    private List<String> missingContextClasses(List<String> requiredContextClassNames) {
        return requiredContextClassNames.stream()
                .map(ignored -> MISSING_CONTEXT_CLASS_PLACEHOLDER)
                .toList();
    }

    private boolean isSuccessful(ExecutionResult result) {
        return result != null && !result.timedOut() && result.exitCode() == 0;
    }

    private List<String> buildCachedContextClasses(
            Path projectRoot,
            Path preferredSourcePath,
            List<String> importedContextPaths,
            List<String> cachedContextPaths
    ) {
        Set<String> contextPaths = new LinkedHashSet<>(importedContextPaths);
        contextPaths.addAll(cachedContextPaths);
        return fileService.readCachedContextEntries(projectRoot, preferredSourcePath, List.copyOf(contextPaths));
    }
}
