package com.jaipilot.cli.service;

import com.jaipilot.cli.JaiPilotCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

final class InstalledReleaseUpdater implements StartupUpdateService.ReleaseInstaller {

    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);

    private final ProcessExecutor processExecutor;
    private final Duration installTimeout;

    InstalledReleaseUpdater() {
        this(new ProcessExecutor(), INSTALL_TIMEOUT);
    }

    InstalledReleaseUpdater(ProcessExecutor processExecutor, Duration installTimeout) {
        this.processExecutor = processExecutor;
        this.installTimeout = installTimeout;
    }

    Optional<Installation> locateCurrentInstallation(String currentVersion) {
        try {
            Path codeSource = Path.of(JaiPilotCli.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return locateInstalledLayout(codeSource, currentVersion);
        } catch (RuntimeException | URISyntaxException exception) {
            return Optional.empty();
        }
    }

    static Optional<Installation> locateInstalledLayout(Path codeSource, String currentVersion) {
        Optional<String> normalizedVersion = StartupUpdateService.normalizeVersion(currentVersion);
        if (normalizedVersion.isEmpty() || !Files.isRegularFile(codeSource)) {
            return Optional.empty();
        }

        try {
            Path jarPath = codeSource.toRealPath();
            if (!jarPath.getFileName().toString().equals("jaipilot.jar")) {
                return Optional.empty();
            }
            Path libDirectory = jarPath.getParent();
            Path versionDirectory = libDirectory == null ? null : libDirectory.getParent();
            Path versionsDirectory = versionDirectory == null ? null : versionDirectory.getParent();
            Path appDirectory = versionsDirectory == null ? null : versionsDirectory.getParent();
            if (libDirectory == null
                    || versionDirectory == null
                    || versionsDirectory == null
                    || appDirectory == null
                    || !libDirectory.getFileName().toString().equals("lib")
                    || !versionDirectory.getFileName().toString().equals(normalizedVersion.get())
                    || !versionsDirectory.getFileName().toString().equals("versions")) {
                return Optional.empty();
            }

            Path installer = versionDirectory.resolve("libexec/install.sh");
            if (!Files.isRegularFile(installer)) {
                return Optional.empty();
            }
            return Optional.of(new Installation(appDirectory, installer));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void install(Installation installation, String version, PrintWriter out)
            throws IOException, InterruptedException {
        String normalizedVersion = StartupUpdateService.normalizeVersion(version)
                .orElseThrow(() -> new IOException("Invalid release version"));
        List<String> command = List.of(
                "sh",
                installation.installer().toString(),
                "--version",
                normalizedVersion,
                "--app-dir",
                installation.appDirectory().toString(),
                "--no-bin-link"
        );
        ProcessExecutor.ExecutionResult result = processExecutor.execute(
                command,
                installation.appDirectory(),
                installTimeout,
                true,
                out
        );
        if (result.timedOut()) {
            throw new IOException("installer timed out after " + installTimeout.toMinutes() + " minutes");
        }
        if (result.exitCode() != 0) {
            throw new IOException("installer exited with code " + result.exitCode());
        }
    }

    record Installation(Path appDirectory, Path installer) {
    }
}
