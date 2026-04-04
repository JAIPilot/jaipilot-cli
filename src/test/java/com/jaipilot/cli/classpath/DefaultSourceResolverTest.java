package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultSourceResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesWorkspaceSourceFromMappedPath() throws Exception {
        Path sourcePath = tempDir.resolve("src/main/java/com/example/Foo.java");
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, "package com.example; class Foo {}\n");

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.Foo",
                LocationKind.PROJECT_MAIN_CLASS,
                tempDir,
                "com/example/Foo.class",
                Optional.of(sourcePath),
                Optional.empty()
        );

        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir, tempDir);
        Optional<ResolvedSource> source = resolver.resolveSource(classResult, ResolutionOptions.defaults());

        assertTrue(source.isPresent());
        assertEquals(SourceOrigin.WORKSPACE_FILE, source.get().origin());
        assertEquals("com.example.Foo", source.get().fqcn());
        assertTrue(source.get().sourceText().contains("class Foo"));
    }

    @Test
    void resolvesExternalSourceFromSiblingSourcesJar() throws Exception {
        Path dependencyJar = tempDir.resolve("repo/acme-utils-1.0.0.jar");
        Path sourcesJar = tempDir.resolve("repo/acme-utils-1.0.0-sources.jar");
        Files.createDirectories(dependencyJar.getParent());
        Files.write(dependencyJar, new byte[] {0, 1});
        createSourcesJar(sourcesJar, "com/example/Util.java", "package com.example; class Util {}\n");

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.Util",
                LocationKind.EXTERNAL_JAR,
                dependencyJar,
                "com/example/Util.class",
                Optional.empty(),
                Optional.of(new MavenCoordinates("com.example", "acme-utils", "1.0.0"))
        );

        ResolutionOptions options = new ResolutionOptions(List.of(), true, true);
        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir, tempDir);

        Optional<ResolvedSource> source = resolver.resolveSource(classResult, options);

        assertTrue(source.isPresent());
        assertEquals(SourceOrigin.SOURCES_JAR, source.get().origin());
        assertEquals(sourcesJar.toAbsolutePath().normalize(), source.get().sourceContainer());
        assertTrue(source.get().sourceText().contains("class Util"));
    }

    @Test
    void fallsBackToCfrWhenWorkspaceSourceIsMissing() throws Exception {
        Path sourcePath = tempDir.resolve("src/main/java/com/example/Widget.java");
        Path classOutput = tempDir.resolve("target/classes");
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

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.Widget",
                LocationKind.PROJECT_MAIN_CLASS,
                classOutput,
                "com/example/Widget.class",
                Optional.of(sourcePath),
                Optional.empty()
        );

        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir, tempDir);
        Optional<ResolvedSource> source = resolver.resolveSource(classResult, ResolutionOptions.defaults());

        assertTrue(source.isPresent());
        assertEquals(SourceOrigin.DECOMPILED_CLASS, source.get().origin());
        assertTrue(source.get().sourceText().contains("class Widget"));
        assertTrue(source.get().sourceText().contains("return \"ok\""));
    }

    @Test
    void fallsBackToCfrWhenExternalSourcesJarIsMissing() throws Exception {
        Path sourcePath = tempDir.resolve("compile-src/com/example/NoSources.java");
        Path classOutput = tempDir.resolve("compile-out");
        writeAndCompile(
                sourcePath,
                classOutput,
                """
                package com.example;
                public class NoSources {
                    public int answer() {
                        return 42;
                    }
                }
                """
        );

        Path dependencyJar = tempDir.resolve("repo/no-sources-1.0.0.jar");
        createJarWithClass(
                dependencyJar,
                "com/example/NoSources.class",
                classOutput.resolve("com/example/NoSources.class")
        );

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.NoSources",
                LocationKind.EXTERNAL_JAR,
                dependencyJar,
                "com/example/NoSources.class",
                Optional.empty(),
                Optional.empty()
        );

        ResolutionOptions options = new ResolutionOptions(List.of(), true, true);
        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir, tempDir);
        Optional<ResolvedSource> source = resolver.resolveSource(classResult, options);

        assertTrue(source.isPresent());
        assertEquals(SourceOrigin.DECOMPILED_CLASS, source.get().origin());
        assertEquals(dependencyJar.toAbsolutePath().normalize(), source.get().sourceContainer());
        assertTrue(source.get().sourceText().contains("class NoSources"));
        assertTrue(source.get().sourceText().contains("return 42"));
    }

    private static void writeAndCompile(Path sourcePath, Path classOutput, String source) throws IOException {
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
        assertEquals(0, status, "Compilation must succeed for test fixture source");
    }

    private static void createJarWithClass(Path jarPath, String entryName, Path classFile) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(Files.readAllBytes(classFile));
            outputStream.closeEntry();
        }
    }

    private static void createSourcesJar(Path jarPath, String entryName, String sourceText) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(sourceText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            outputStream.closeEntry();
        }
    }
}
