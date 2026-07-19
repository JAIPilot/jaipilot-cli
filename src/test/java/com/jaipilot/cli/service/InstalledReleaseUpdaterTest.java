package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledReleaseUpdaterTest {

    @TempDir
    Path tempDir;

    @Test
    void locatesOnlyBundledInstallerLayoutForCurrentVersion() throws Exception {
        Path appDirectory = tempDir.resolve("app");
        Path versionDirectory = appDirectory.resolve("versions/1.0.13");
        Path jar = createFile(versionDirectory.resolve("lib/jaipilot.jar"), "jar");
        Path installer = createFile(versionDirectory.resolve("libexec/install.sh"), "#!/bin/sh\n");

        InstalledReleaseUpdater.Installation installation = InstalledReleaseUpdater
                .locateInstalledLayout(jar, "v1.0.13")
                .orElseThrow();

        assertEquals(appDirectory.toRealPath(), installation.appDirectory());
        assertEquals(installer.toRealPath(), installation.installer());
    }

    @Test
    void rejectsDevelopmentAndIncompleteLayouts() throws Exception {
        Path classesDirectory = Files.createDirectories(tempDir.resolve("target/classes"));
        assertTrue(InstalledReleaseUpdater.locateInstalledLayout(classesDirectory, "1.0.13").isEmpty());

        Path wrongVersionJar = createFile(
                tempDir.resolve("app/versions/1.0.12/lib/jaipilot.jar"),
                "jar"
        );
        assertTrue(InstalledReleaseUpdater.locateInstalledLayout(wrongVersionJar, "1.0.13").isEmpty());

        Path missingInstallerJar = createFile(
                tempDir.resolve("other-app/versions/1.0.13/lib/jaipilot.jar"),
                "jar"
        );
        assertTrue(InstalledReleaseUpdater.locateInstalledLayout(missingInstallerJar, "1.0.13").isEmpty());

        Path arbitraryJar = createFile(tempDir.resolve("lib/jaipilot.jar"), "jar");
        assertTrue(InstalledReleaseUpdater.locateInstalledLayout(arbitraryJar, "1.0.13").isEmpty());
    }

    @Test
    void invokesBundledInstallerWithExactSelfUpdateArguments() throws Exception {
        Path appDirectory = Files.createDirectories(tempDir.resolve("installed app"));
        Path capturedArguments = tempDir.resolve("arguments.txt");
        Path installer = createFile(
                tempDir.resolve("bundled installer.sh"),
                "#!/bin/sh\nprintf '%s\\n' \"$0\" \"$@\" > \"" + capturedArguments + "\"\n"
        );
        InstalledReleaseUpdater updater = new InstalledReleaseUpdater(
                new ProcessExecutor(),
                Duration.ofSeconds(5)
        );

        updater.install(
                new InstalledReleaseUpdater.Installation(appDirectory, installer),
                "v1.0.14",
                new PrintWriter(new StringWriter(), true)
        );

        assertEquals(
                List.of(
                        installer.toString(),
                        "--version",
                        "1.0.14",
                        "--app-dir",
                        appDirectory.toString(),
                        "--no-bin-link"
                ),
                Files.readAllLines(capturedArguments)
        );
    }

    @Test
    void reportsNonzeroInstallerExitCode() throws Exception {
        Path appDirectory = Files.createDirectories(tempDir.resolve("app"));
        Path installer = createFile(tempDir.resolve("install.sh"), "#!/bin/sh\nexit 23\n");
        InstalledReleaseUpdater updater = new InstalledReleaseUpdater(
                new ProcessExecutor(),
                Duration.ofSeconds(5)
        );

        IOException failure = assertThrows(
                IOException.class,
                () -> updater.install(
                        new InstalledReleaseUpdater.Installation(appDirectory, installer),
                        "1.0.14",
                        new PrintWriter(new StringWriter(), true)
                )
        );

        assertEquals("installer exited with code 23", failure.getMessage());
    }

    @Test
    void rejectsInvalidTargetVersionBeforeStartingInstaller() throws Exception {
        Path appDirectory = Files.createDirectories(tempDir.resolve("app"));
        Path marker = tempDir.resolve("ran.txt");
        Path installer = createFile(
                tempDir.resolve("install.sh"),
                "#!/bin/sh\ntouch \"" + marker + "\"\n"
        );
        InstalledReleaseUpdater updater = new InstalledReleaseUpdater(
                new ProcessExecutor(),
                Duration.ofSeconds(5)
        );

        IOException failure = assertThrows(
                IOException.class,
                () -> updater.install(
                        new InstalledReleaseUpdater.Installation(appDirectory, installer),
                        "1.0.14-rc.1",
                        new PrintWriter(new StringWriter(), true)
                )
        );

        assertEquals("Invalid release version", failure.getMessage());
        assertFalse(Files.exists(marker));
    }

    private Path createFile(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
        return path;
    }
}
