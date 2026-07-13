package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexCliUnitTestGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void buildToolCacheEnvironmentAddsSandboxFriendlyDefaults() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(tempDir, Map.of());

        assertEquals(
                "-Daether.enhancedLocalRepository.trackingFilename=ignore",
                environment.get("MAVEN_OPTS")
        );
        assertEquals(
                tempDir.resolve("build/jaipilot-gradle").normalize().toString(),
                environment.get("GRADLE_USER_HOME")
        );
    }

    @Test
    void buildToolCacheEnvironmentPreservesExistingMavenOptions() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(
                tempDir,
                Map.of("MAVEN_OPTS", "-Xmx2g")
        );

        assertEquals(
                "-Xmx2g -Daether.enhancedLocalRepository.trackingFilename=ignore",
                environment.get("MAVEN_OPTS")
        );
    }

    @Test
    void buildToolCacheEnvironmentDoesNotOverrideExplicitMavenTrackingOption() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(
                tempDir,
                Map.of("MAVEN_OPTS", "-Daether.enhancedLocalRepository.trackingFilename=custom")
        );

        assertFalse(environment.containsKey("MAVEN_OPTS"));
    }

    @Test
    void buildToolCacheEnvironmentDoesNotOverrideExplicitGradleHome() {
        Map<String, String> environment = CodexCliUnitTestGenerator.buildToolCacheEnvironment(
                tempDir,
                Map.of("GRADLE_USER_HOME", "/custom/gradle")
        );

        assertFalse(environment.containsKey("GRADLE_USER_HOME"));
    }
}
