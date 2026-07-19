package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StartupUpdateServiceTest {

    private static final InstalledReleaseUpdater.Installation INSTALLATION =
            new InstalledReleaseUpdater.Installation(
                    Path.of("/opt/jaipilot"),
                    Path.of("/opt/jaipilot/versions/1.0.13/libexec/install.sh")
            );

    @Test
    void installsNewerReleaseWhenUserConfirms() {
        AtomicReference<String> installedVersion = new AtomicReference<>();
        AtomicReference<InstalledReleaseUpdater.Installation> installedLocation = new AtomicReference<>();
        Output output = new Output();
        StartupUpdateService service = service(
                "v1.0.14",
                (installation, version, out) -> {
                    installedLocation.set(installation);
                    installedVersion.set(version);
                }
        );

        StartupUpdateService.Result result = service.checkForUpdate(() -> " yes ", output.out(), output.err());

        assertEquals(StartupUpdateService.Result.UPDATED, result);
        assertEquals(INSTALLATION, installedLocation.get());
        assertEquals("1.0.14", installedVersion.get());
        assertTrue(output.stdout().contains("JAIPilot 1.0.14 is available (current 1.0.13)."));
        assertTrue(output.stdout().contains("Update now? [y/N] (Enter to skip): "));
        assertTrue(output.stdout().contains("Updated JAIPilot to 1.0.14."));
        assertEquals("", output.stderr());
    }

    @Test
    void skipsUpdateWhenUserDeclines() {
        AtomicInteger installCalls = new AtomicInteger();
        Output output = new Output();
        StartupUpdateService service = service(
                "1.0.14",
                (installation, version, out) -> installCalls.incrementAndGet()
        );

        StartupUpdateService.Result result = service.checkForUpdate(() -> "no", output.out(), output.err());

        assertEquals(StartupUpdateService.Result.CONTINUE, result);
        assertEquals(0, installCalls.get());
        assertTrue(output.stdout().contains("Update skipped."));
        assertEquals("", output.stderr());
    }

    @Test
    void endOfInputDefaultsToSkip() {
        AtomicInteger installCalls = new AtomicInteger();
        Output output = new Output();
        StartupUpdateService service = service(
                "1.0.14",
                (installation, version, out) -> installCalls.incrementAndGet()
        );

        StartupUpdateService.Result result = service.checkForUpdate(() -> null, output.out(), output.err());

        assertEquals(StartupUpdateService.Result.CONTINUE, result);
        assertEquals(0, installCalls.get());
        assertTrue(output.stdout().contains("Update skipped."));
    }

    @Test
    void doesNotPromptWhenLatestReleaseIsNotNewer() {
        AtomicInteger promptCalls = new AtomicInteger();
        AtomicInteger installCalls = new AtomicInteger();
        Output output = new Output();
        StartupUpdateService service = service(
                "1.0.13",
                (installation, version, out) -> installCalls.incrementAndGet()
        );

        StartupUpdateService.Result result = service.checkForUpdate(
                () -> {
                    promptCalls.incrementAndGet();
                    return "yes";
                },
                output.out(),
                output.err()
        );

        assertEquals(StartupUpdateService.Result.CONTINUE, result);
        assertEquals(0, promptCalls.get());
        assertEquals(0, installCalls.get());
        assertEquals("", output.stdout());
        assertEquals("", output.stderr());
    }

    @Test
    void releaseLookupFailureFailsOpenWithoutNoisyOutput() {
        AtomicInteger promptCalls = new AtomicInteger();
        Output output = new Output();
        StartupUpdateService service = new StartupUpdateService(
                "1.0.13",
                () -> Optional.of(INSTALLATION),
                () -> {
                    throw new IOException("GitHub unavailable\nresponse body must not leak");
                },
                (installation, version, out) -> {
                    throw new AssertionError("installer must not run");
                }
        );

        StartupUpdateService.Result result = service.checkForUpdate(
                () -> {
                    promptCalls.incrementAndGet();
                    return "yes";
                },
                output.out(),
                output.err()
        );

        assertEquals(StartupUpdateService.Result.CONTINUE, result);
        assertEquals(0, promptCalls.get());
        assertEquals("", output.stdout());
        assertEquals("", output.stderr());
    }

    @Test
    void interruptedReleaseLookupRestoresInterruptAndFailsOpen() {
        Output output = new Output();
        StartupUpdateService service = new StartupUpdateService(
                "1.0.13",
                () -> Optional.of(INSTALLATION),
                () -> {
                    throw new InterruptedException("cancelled");
                },
                (installation, version, out) -> {
                    throw new AssertionError("installer must not run");
                }
        );

        try {
            StartupUpdateService.Result result = service.checkForUpdate(() -> "yes", output.out(), output.err());

            assertEquals(StartupUpdateService.Result.CONTINUE, result);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("", output.stdout());
            assertEquals("", output.stderr());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void installFailureWarnsCleanlyAndContinues() {
        Output output = new Output();
        StartupUpdateService service = service(
                "1.0.14",
                (installation, version, out) -> {
                    throw new IOException("installer exited with code 23\nraw subprocess output");
                }
        );

        StartupUpdateService.Result result = service.checkForUpdate(() -> "y", output.out(), output.err());

        assertEquals(StartupUpdateService.Result.CONTINUE, result);
        assertTrue(output.stderr().contains(
                "Update failed: installer exited with code 23. Continuing with JAIPilot 1.0.13."
        ));
        assertFalse(output.stderr().contains("raw subprocess output"));
        assertFalse(output.stderr().contains("IOException"));
    }

    @Test
    void comparesNumericReleaseComponentsRatherThanLexicographically() {
        assertTrue(StartupUpdateService.compareVersions("1.0.10", "1.0.9") > 0);
        assertTrue(StartupUpdateService.compareVersions("v2.0.0", "1.999.0") > 0);
        assertTrue(StartupUpdateService.compareVersions("1.2.0", "1.2.1") < 0);
        assertEquals(0, StartupUpdateService.compareVersions("1.2.0", "v1.2.0"));
        assertEquals(0, StartupUpdateService.compareVersions("1.0003.0", "1.3.0"));
        assertEquals(0, StartupUpdateService.compareVersions("not-a-version", "1.0.0"));
    }

    private StartupUpdateService service(
            String latestVersion,
            StartupUpdateService.ReleaseInstaller installer
    ) {
        return new StartupUpdateService(
                "1.0.13",
                () -> Optional.of(INSTALLATION),
                () -> Optional.of(latestVersion),
                installer
        );
    }

    private static final class Output {

        private final StringWriter stdout = new StringWriter();
        private final StringWriter stderr = new StringWriter();

        PrintWriter out() {
            return new PrintWriter(stdout, true);
        }

        PrintWriter err() {
            return new PrintWriter(stderr, true);
        }

        String stdout() {
            return stdout.toString();
        }

        String stderr() {
            return stderr.toString();
        }
    }
}
