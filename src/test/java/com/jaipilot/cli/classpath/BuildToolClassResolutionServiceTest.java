package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildToolClassResolutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void compileFallbackRunsOnlyOncePerFingerprint() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Files.createDirectories(moduleRoot.resolve("target/classes/com/acme"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        Path compiledOutput = moduleRoot.resolve("target/classes");
        Path sourceRoot = moduleRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/acme"));
        Files.write(compiledOutput.resolve("com/acme/Widget.class"), new byte[] {1, 2, 3});
        Files.writeString(sourceRoot.resolve("com/acme/Widget.java"), "package com.acme; class Widget {}\n");

        AtomicInteger forceCompileCount = new AtomicInteger();
        CompileCapableClasspathResolver stubMavenResolver = new CompileCapableClasspathResolver() {
            @Override
            public ResolvedClasspath resolveTestClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options) {
                return resolveTestClasspath(projectRoot, moduleRoot, options, false);
            }

            @Override
            public ResolvedClasspath resolveTestClasspath(
                    Path projectRoot,
                    Path moduleRoot,
                    ResolutionOptions options,
                    boolean forceCompile
            ) {
                if (forceCompile) {
                    forceCompileCount.incrementAndGet();
                }
                return new ResolvedClasspath(
                        BuildToolType.MAVEN,
                        projectRoot,
                        moduleRoot,
                        List.of(compiledOutput),
                        forceCompile ? List.of(compiledOutput) : List.of(),
                        List.of(),
                        List.of(sourceRoot),
                        List.of(),
                        "fingerprint-1"
                );
            }
        };

        BuildToolClasspathResolver classpathResolver = new BuildToolClasspathResolver(
                new BuildToolDetector(),
                stubMavenResolver,
                stubMavenResolver
        );
        BuildToolClassResolutionService service = new BuildToolClassResolutionService(
                classpathResolver,
                new ClasspathClassLocator(),
                ConcurrentHashMap.newKeySet()
        );

        ResolutionOptions options = new ResolutionOptions(List.of(), true, false);

        ClassResolutionResult first = service.locate("com.acme.Widget", projectRoot, moduleRoot, options);
        ClassResolutionResult second = service.locate("com.acme.Widget", projectRoot, moduleRoot, options);

        assertEquals(LocationKind.PROJECT_MAIN_CLASS, first.kind());
        assertEquals(LocationKind.NOT_FOUND, second.kind());
        assertEquals(1, forceCompileCount.get());
    }

    @Test
    void resolveSourceByFqcnFindsExactJavaInGeneratedTargetRoot() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path generatedSource = moduleRoot.resolve("target/com/example/GeneratedDto.java");
        Files.createDirectories(generatedSource.getParent());
        Files.writeString(generatedSource, "package com.example; public class GeneratedDto {}\n");

        ResolvedClasspath classpath = new ResolvedClasspath(
                BuildToolType.MAVEN,
                projectRoot,
                moduleRoot,
                List.of(),
                List.of(),
                List.of(),
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(moduleRoot.resolve("src/test/java")),
                "generated-source-fingerprint"
        );

        AtomicInteger locateCalls = new AtomicInteger();
        ClassLocator classLocator = (fqcn, ignoredClasspath) -> {
            locateCalls.incrementAndGet();
            return new ClassResolutionResult(
                    fqcn,
                    LocationKind.NOT_FOUND,
                    null,
                    ClassNameParser.classEntryPath(fqcn),
                    Optional.empty(),
                    Optional.empty()
            );
        };

        BuildToolClassResolutionService service = new BuildToolClassResolutionService(
                stubClasspathResolver(classpath),
                classLocator,
                ConcurrentHashMap.newKeySet()
        );

        Optional<ResolvedSource> resolved = service.resolveSourceByFqcn(
                "com.example.GeneratedDto",
                projectRoot,
                moduleRoot,
                new ResolutionOptions(List.of(), false, true)
        );

        assertTrue(resolved.isPresent());
        assertEquals(SourceOrigin.WORKSPACE_FILE, resolved.get().origin());
        assertEquals(generatedSource.toAbsolutePath().normalize(), resolved.get().sourceContainer());
        assertTrue(resolved.get().sourceText().contains("GeneratedDto"));
        assertEquals(0, locateCalls.get());
    }

    @Test
    void resolveSourceByFqcnFallsBackToOuterJavaForInnerClassName() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path outerSource = moduleRoot.resolve("src/main/java/com/example/Outer.java");
        Files.createDirectories(outerSource.getParent());
        Files.writeString(outerSource, "package com.example; public class Outer { static class Inner {} }\n");

        ResolvedClasspath classpath = new ResolvedClasspath(
                BuildToolType.MAVEN,
                projectRoot,
                moduleRoot,
                List.of(),
                List.of(),
                List.of(),
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(moduleRoot.resolve("src/test/java")),
                "inner-class-source-fingerprint"
        );

        AtomicInteger locateCalls = new AtomicInteger();
        ClassLocator classLocator = (fqcn, ignoredClasspath) -> {
            locateCalls.incrementAndGet();
            return new ClassResolutionResult(
                    fqcn,
                    LocationKind.NOT_FOUND,
                    null,
                    ClassNameParser.classEntryPath(fqcn),
                    Optional.empty(),
                    Optional.empty()
            );
        };

        BuildToolClassResolutionService service = new BuildToolClassResolutionService(
                stubClasspathResolver(classpath),
                classLocator,
                ConcurrentHashMap.newKeySet()
        );

        Optional<ResolvedSource> resolved = service.resolveSourceByFqcn(
                "com.example.Outer.Inner",
                projectRoot,
                moduleRoot,
                new ResolutionOptions(List.of(), false, true)
        );

        assertTrue(resolved.isPresent());
        assertEquals(SourceOrigin.WORKSPACE_FILE, resolved.get().origin());
        assertEquals(outerSource.toAbsolutePath().normalize(), resolved.get().sourceContainer());
        assertTrue(resolved.get().sourceText().contains("class Outer"));
        assertEquals(0, locateCalls.get());
    }

    @Test
    void resolveSourceByFqcnFallsBackToDecompilationFromWorkspaceClassOutput() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Path moduleRoot = projectRoot;
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path sourcePath = moduleRoot.resolve("src/main/java/com/example/Widget.java");
        Path classOutput = moduleRoot.resolve("target/classes");
        writeAndCompile(
                sourcePath,
                classOutput,
                """
                package com.example;
                public class Widget {
                    public String value() {
                        return "ok";
                    }
                }
                """
        );
        Files.delete(sourcePath);

        ResolvedClasspath classpath = new ResolvedClasspath(
                BuildToolType.MAVEN,
                projectRoot,
                moduleRoot,
                List.of(classOutput),
                List.of(classOutput),
                List.of(),
                List.of(moduleRoot.resolve("src/main/java")),
                List.of(moduleRoot.resolve("src/test/java")),
                "workspace-decompile-fingerprint"
        );

        BuildToolClassResolutionService service = new BuildToolClassResolutionService(
                stubClasspathResolver(classpath),
                new ClasspathClassLocator(),
                ConcurrentHashMap.newKeySet()
        );

        Optional<ResolvedSource> resolved = service.resolveSourceByFqcn(
                "com.example.Widget",
                projectRoot,
                moduleRoot,
                new ResolutionOptions(List.of(), false, true)
        );

        assertTrue(resolved.isPresent());
        assertEquals(SourceOrigin.DECOMPILED_CLASS, resolved.get().origin());
        assertTrue(resolved.get().sourceText().contains("class Widget"));
        assertTrue(resolved.get().sourceText().contains("return \"ok\""));
    }

    private BuildToolClasspathResolver stubClasspathResolver(ResolvedClasspath classpath) {
        CompileCapableClasspathResolver stubResolver = new CompileCapableClasspathResolver() {
            @Override
            public ResolvedClasspath resolveTestClasspath(Path projectRoot, Path moduleRoot, ResolutionOptions options) {
                return classpath;
            }

            @Override
            public ResolvedClasspath resolveTestClasspath(
                    Path projectRoot,
                    Path moduleRoot,
                    ResolutionOptions options,
                    boolean forceCompile
            ) {
                return classpath;
            }
        };
        return new BuildToolClasspathResolver(
                new BuildToolDetector(),
                stubResolver,
                stubResolver
        );
    }

    private void writeAndCompile(Path sourcePath, Path classOutput, String source) throws IOException {
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, source);
        Files.createDirectories(classOutput);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler must be available during tests");

        int status = compiler.run(
                null,
                null,
                null,
                "-d",
                classOutput.toString(),
                sourcePath.toString()
        );
        assertEquals(0, status, "Compilation must succeed for fixture source");
    }
}
