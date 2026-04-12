package com.jaipilot.cli.service;

import com.jaipilot.cli.files.ProjectFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MockitoVersionResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveReadsMockitoVersionFromMavenPomProperty() throws Exception {
        write(
                tempDir.resolve("pom.xml"),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <mockito.version>5.12.0</mockito.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.mockito</groupId>
                      <artifactId>mockito-core</artifactId>
                      <version>${mockito.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """
        );
        Path sourcePath = write(
                tempDir.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );

        MockitoVersionResolver resolver = new MockitoVersionResolver(new ProjectFileService());

        String version = resolver.resolve(tempDir, sourcePath);

        assertEquals("5.12.0", version);
    }

    @Test
    void resolveReadsMockitoVersionFromGradleCoordinate() throws Exception {
        write(
                tempDir.resolve("build.gradle.kts"),
                """
                val mockitoVersion = "5.13.0"
                dependencies {
                    testImplementation("org.mockito:mockito-core:$mockitoVersion")
                }
                """
        );
        Path sourcePath = write(
                tempDir.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );

        MockitoVersionResolver resolver = new MockitoVersionResolver(new ProjectFileService());

        String version = resolver.resolve(tempDir, sourcePath);

        assertEquals("5.13.0", version);
    }

    @Test
    void resolveReturnsNullWhenMockitoDependencyIsAbsent() throws Exception {
        write(
                tempDir.resolve("pom.xml"),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                </project>
                """
        );
        Path sourcePath = write(
                tempDir.resolve("src/main/java/com/example/CrashController.java"),
                "package com.example;\npublic class CrashController {}\n"
        );

        MockitoVersionResolver resolver = new MockitoVersionResolver(new ProjectFileService());

        String version = resolver.resolve(tempDir, sourcePath);

        assertNull(version);
    }

    private Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
