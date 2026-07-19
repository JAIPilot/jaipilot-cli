package com.jaipilot.cli.service;

import com.jaipilot.cli.JaiPilotVersionProvider;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class StartupUpdateService {

    private static final Pattern RELEASE_VERSION = Pattern.compile("^v?[0-9]+\\.[0-9]+\\.[0-9]+$");

    private final String currentVersion;
    private final InstallationLocator installationLocator;
    private final LatestReleaseProvider latestReleaseProvider;
    private final ReleaseInstaller releaseInstaller;

    StartupUpdateService(
            String currentVersion,
            InstallationLocator installationLocator,
            LatestReleaseProvider latestReleaseProvider,
            ReleaseInstaller releaseInstaller
    ) {
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.installationLocator = Objects.requireNonNull(installationLocator, "installationLocator");
        this.latestReleaseProvider = Objects.requireNonNull(latestReleaseProvider, "latestReleaseProvider");
        this.releaseInstaller = Objects.requireNonNull(releaseInstaller, "releaseInstaller");
    }

    public static StartupUpdateService createDefault() {
        InstalledReleaseUpdater updater = new InstalledReleaseUpdater();
        String currentVersion = JaiPilotVersionProvider.resolveVersion();
        return new StartupUpdateService(
                currentVersion,
                () -> updater.locateCurrentInstallation(currentVersion),
                new GitHubReleaseClient(),
                updater
        );
    }

    public Result checkForUpdate(Prompt prompt, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        Optional<InstalledReleaseUpdater.Installation> installation;
        Optional<String> latestVersion;
        try {
            installation = installationLocator.locate();
            if (installation.isEmpty()) {
                return Result.CONTINUE;
            }
            latestVersion = latestReleaseProvider.latestVersion();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.CONTINUE;
        } catch (Exception ignored) {
            return Result.CONTINUE;
        }

        if (latestVersion.isEmpty() || compareVersions(latestVersion.get(), currentVersion) <= 0) {
            return Result.CONTINUE;
        }

        String normalizedLatestVersion = normalizeVersion(latestVersion.get()).orElseThrow();
        TerminalUi ui = new TerminalUi(out);
        ui.info("JAIPilot %s is available (current %s).".formatted(normalizedLatestVersion, currentVersion));
        out.print("Update now? [y/N] (Enter to skip): ");
        out.flush();

        String answer = prompt.readLine();
        if (!isYes(answer)) {
            ui.info("Update skipped.");
            ui.blankLine();
            return Result.CONTINUE;
        }

        ui.info("Updating JAIPilot to %s...".formatted(normalizedLatestVersion));
        try {
            releaseInstaller.install(installation.orElseThrow(), normalizedLatestVersion, out);
            ui.success("Updated JAIPilot to %s. Restart jaipilot to use the new version."
                    .formatted(normalizedLatestVersion));
            return Result.UPDATED;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            TerminalUi errorUi = new TerminalUi(err);
            errorUi.warn("Update failed: %s. Continuing with JAIPilot %s."
                    .formatted(cleanMessage(exception), currentVersion));
            errorUi.blankLine();
            return Result.CONTINUE;
        }
    }

    static int compareVersions(String left, String right) {
        Optional<String> normalizedLeft = normalizeVersion(left);
        Optional<String> normalizedRight = normalizeVersion(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return 0;
        }

        String[] leftParts = normalizedLeft.get().split("\\.");
        String[] rightParts = normalizedRight.get().split("\\.");
        int partCount = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < partCount; index++) {
            String leftPart = index < leftParts.length ? stripLeadingZeroes(leftParts[index]) : "0";
            String rightPart = index < rightParts.length ? stripLeadingZeroes(rightParts[index]) : "0";
            int lengthComparison = Integer.compare(leftPart.length(), rightPart.length());
            if (lengthComparison != 0) {
                return lengthComparison;
            }
            int valueComparison = leftPart.compareTo(rightPart);
            if (valueComparison != 0) {
                return valueComparison;
            }
        }
        return 0;
    }

    static Optional<String> normalizeVersion(String version) {
        if (version == null || !RELEASE_VERSION.matcher(version.trim()).matches()) {
            return Optional.empty();
        }
        String normalized = version.trim();
        return Optional.of(normalized.startsWith("v") ? normalized.substring(1) : normalized);
    }

    private static String stripLeadingZeroes(String value) {
        String stripped = value.replaceFirst("^0+(?!$)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    private static boolean isYes(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }

    private static String cleanMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String firstLine = message.lines().findFirst().orElse("update error").trim();
        return firstLine.length() <= 160 ? firstLine : firstLine.substring(0, 157) + "...";
    }

    public enum Result {
        CONTINUE,
        UPDATED
    }

    @FunctionalInterface
    public interface Prompt {
        String readLine();
    }

    @FunctionalInterface
    interface InstallationLocator {
        Optional<InstalledReleaseUpdater.Installation> locate() throws Exception;
    }

    @FunctionalInterface
    interface LatestReleaseProvider {
        Optional<String> latestVersion() throws Exception;
    }

    @FunctionalInterface
    interface ReleaseInstaller {
        void install(
                InstalledReleaseUpdater.Installation installation,
                String version,
                PrintWriter out
        ) throws Exception;
    }
}
