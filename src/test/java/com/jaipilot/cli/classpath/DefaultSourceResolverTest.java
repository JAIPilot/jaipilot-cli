package com.jaipilot.cli.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir);
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
        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir);

        Optional<ResolvedSource> source = resolver.resolveSource(classResult, options);

        assertTrue(source.isPresent());
        assertEquals(SourceOrigin.SOURCES_JAR, source.get().origin());
        assertEquals(sourcesJar.toAbsolutePath().normalize(), source.get().sourceContainer());
        assertTrue(source.get().sourceText().contains("class Util"));
    }

    @Test
    void returnsEmptyWhenWorkspaceSourceIsMissing() throws Exception {
        Path sourcePath = tempDir.resolve("src/main/java/com/example/Widget.java");
        Path classOutput = tempDir.resolve("target/classes/com/example");
        Files.createDirectories(classOutput);
        Files.write(classOutput.resolve("Widget.class"), new byte[] {1, 2, 3});
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, "package com.example; public class Widget {}\n");
        Files.delete(sourcePath);

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.Widget",
                LocationKind.PROJECT_MAIN_CLASS,
                tempDir.resolve("target/classes"),
                "com/example/Widget.class",
                Optional.of(sourcePath),
                Optional.empty()
        );

        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir);
        Optional<ResolvedSource> source = resolver.resolveSource(classResult, ResolutionOptions.defaults());

        assertTrue(source.isEmpty());
    }

    @Test
    void returnsEmptyWhenExternalSourcesJarIsMissing() throws Exception {
        Path dependencyJar = tempDir.resolve("repo/no-sources-1.0.0.jar");
        createJarWithClass(dependencyJar, "com/example/NoSources.class");

        ClassResolutionResult classResult = new ClassResolutionResult(
                "com.example.NoSources",
                LocationKind.EXTERNAL_JAR,
                dependencyJar,
                "com/example/NoSources.class",
                Optional.empty(),
                Optional.empty()
        );

        ResolutionOptions options = new ResolutionOptions(List.of(), true, true);
        DefaultSourceResolver resolver = new DefaultSourceResolver(tempDir);
        Optional<ResolvedSource> source = resolver.resolveSource(classResult, options);

        assertTrue(source.isEmpty());
    }

    private static void createJarWithClass(Path jarPath, String entryName) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(new byte[] {1, 2, 3});
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
