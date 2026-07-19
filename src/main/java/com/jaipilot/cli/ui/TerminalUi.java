package com.jaipilot.cli.ui;

import com.jaipilot.cli.JaiPilotVersionProvider;
import com.jaipilot.cli.commands.StatusCommand;
import com.jaipilot.cli.service.ProcessExecutor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import picocli.CommandLine;

public final class TerminalUi {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final int DEFAULT_WIDTH = 96;
    private static final int MIN_WIDTH = 72;
    private static final int COVERAGE_BAR_WIDTH = 20;
    private static final char[] SPINNER_FRAMES = {'|', '/', '-', '\\'};

    private final PrintWriter out;
    private final CommandLine.Help.Ansi ansi;
    private final boolean animationsEnabled;
    private final int width;

    public TerminalUi(PrintWriter out) {
        this(out, CommandLine.Help.Ansi.AUTO, System.console() != null);
    }

    TerminalUi(PrintWriter out, CommandLine.Help.Ansi ansi, boolean animationsEnabled) {
        this.out = Objects.requireNonNull(out, "out");
        this.ansi = Objects.requireNonNull(ansi, "ansi");
        this.animationsEnabled = animationsEnabled;
        this.width = resolveWidth();
    }

    public void printShellWelcome(String projectRoot, String buildTool, String agent) {
        printBanner("Interactive shell ready");
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("project", projectRoot);
        metadata.put("build", buildTool);
        metadata.put("agent", agent);
        metadata.put("default coverage", "%.1f%%".formatted(StatusCommand.DEFAULT_COVERAGE_THRESHOLD));
        printKeyValues(metadata);
        out.println(style("faint", "Press Tab to open suggestions and complete commands, options, thresholds, and Java class selectors."));
        out.println();
        out.flush();
        printShellHelp();
    }

    public void printBanner(String subtitle) {
        out.println(style("bold,fg(cyan)", "JAIPilot " + JaiPilotVersionProvider.resolveVersion()));
        out.println(style("faint", subtitle));
        out.println();
        out.flush();
    }

    public void printShellHelp() {
        section("Commands");
        printCommandHints(List.of(
                new CommandHint("/generate <class>", "Generate tests for one Java production class."),
                new CommandHint("/generate all changed", "Generate tests for changed or uncommitted production classes."),
                new CommandHint("/generate all coverage 80", "Generate tests for classes below the current threshold."),
                new CommandHint("/generate <class> --show-logs", "Stream live build and Codex logs."),
                new CommandHint("/status", "Refresh full-suite coverage and show classes below threshold."),
                new CommandHint("/status --cached", "Read the existing JaCoCo report without running tests."),
                new CommandHint("/doctor", "Check local Codex, build, and JaCoCo prerequisites."),
                new CommandHint("/help", "Show interactive shell commands."),
                new CommandHint("/exit", "Close JAIPilot.")
        ));
    }

    public void section(String title) {
        String prefix = "-- " + title + " ";
        int dashCount = Math.max(6, Math.min(width, DEFAULT_WIDTH) - prefix.length());
        out.println(style("bold,fg(cyan)", prefix + "-".repeat(dashCount)));
        out.flush();
    }

    public void printKeyValues(Map<String, String> values) {
        int labelWidth = values.keySet().stream()
                .mapToInt(String::length)
                .max()
                .orElse(0);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.printf(
                    "%s %s%n",
                    style("bold,fg(cyan)", padRight(entry.getKey(), labelWidth)),
                    entry.getValue()
            );
        }
        out.println();
        out.flush();
    }

    public void printCommandHints(List<CommandHint> hints) {
        int commandWidth = hints.stream()
                .mapToInt(hint -> hint.command().length())
                .max()
                .orElse(0);
        for (CommandHint hint : hints) {
            out.printf(
                    "  %s  %s%n",
                    style("bold,fg(green)", padRight(hint.command(), commandWidth)),
                    style("faint", hint.description())
            );
        }
        out.println();
        out.flush();
    }

    public void printCoverageMeter(String label, double percent, double threshold) {
        out.printf(
                "%s %s%n",
                style("bold,fg(cyan)", padRight(label, 12)),
                coverageBar(percent, threshold)
        );
        out.flush();
    }

    public void printTable(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int index = 0; index < headers.size(); index++) {
            widths[index] = headers.get(index).length();
        }
        for (List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                widths[index] = Math.max(widths[index], visibleLength(row.get(index)));
            }
        }

        out.println(joinRow(headers, widths, true));
        out.println(joinSeparator(widths));
        for (List<String> row : rows) {
            out.println(joinRow(row, widths, false));
        }
        out.println();
        out.flush();
    }

    public void info(String message) {
        out.println(style("fg(cyan)", message));
        out.flush();
    }

    public void success(String message) {
        out.println("%s %s".formatted(badge(Tone.SUCCESS, "done"), message));
        out.flush();
    }

    public void warn(String message) {
        out.println("%s %s".formatted(badge(Tone.WARN, "warn"), message));
        out.flush();
    }

    public void error(String message) {
        out.println("%s %s".formatted(badge(Tone.ERROR, "error"), message));
        out.flush();
    }

    public void blankLine() {
        out.println();
        out.flush();
    }

    public String prompt() {
        return style("bold,fg(cyan)", "jaipilot") + style("faint", " > ");
    }

    public String badge(Tone tone, String text) {
        return style(toneMarkup(tone), "[" + text + "]");
    }

    public String accent(String text) {
        return style("bold,fg(green)", text);
    }

    public String muted(String text) {
        return style("faint", text);
    }

    public String highlight(String text) {
        return style("bold,fg(cyan)", text);
    }

    public String formatCoverage(Double percent, double threshold) {
        if (percent == null) {
            return muted("n/a");
        }
        return style(toneMarkup(coverageTone(percent, threshold)), "%.1f%%".formatted(percent));
    }

    public String coverageBar(double percent, double threshold) {
        double normalized = Math.max(0.0d, Math.min(100.0d, percent));
        int filled = (int) Math.round((normalized / 100.0d) * COVERAGE_BAR_WIDTH);
        String bar = "[" + "=".repeat(filled) + ".".repeat(COVERAGE_BAR_WIDTH - filled) + "]";
        return style(toneMarkup(coverageTone(percent, threshold)), bar + " " + "%.1f%%".formatted(percent));
    }

    public String formatTestState(boolean testPresent) {
        return testPresent ? badge(Tone.SUCCESS, "test present") : badge(Tone.WARN, "test missing");
    }

    public Spinner spinner(String label) {
        return new Spinner(label);
    }

    private String joinRow(List<String> values, int[] widths, boolean header) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append("  ");
            }
            String value = values.get(index);
            int visibleWidth = visibleLength(value);
            builder.append(value);
            builder.append(" ".repeat(Math.max(0, widths[index] - visibleWidth)));
        }
        return header ? style("bold,fg(cyan)", builder.toString()) : builder.toString();
    }

    private String joinSeparator(int[] widths) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < widths.length; index++) {
            if (index > 0) {
                builder.append("  ");
            }
            builder.append("-".repeat(Math.max(3, widths[index])));
        }
        return style("faint", builder.toString());
    }

    private String padRight(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private String style(String markup, String text) {
        return ansi.string("@|" + markup + " " + text + "|@");
    }

    private Tone coverageTone(double percent, double threshold) {
        if (percent >= threshold) {
            return Tone.SUCCESS;
        }
        if (percent >= Math.max(0.0d, threshold - 15.0d)) {
            return Tone.WARN;
        }
        return Tone.ERROR;
    }

    private String toneMarkup(Tone tone) {
        return switch (tone) {
            case PRIMARY -> "bold,fg(cyan)";
            case SUCCESS -> "bold,fg(green)";
            case WARN -> "bold,fg(yellow)";
            case ERROR -> "bold,fg(red)";
        };
    }

    private int visibleLength(String value) {
        return ANSI_ESCAPE.matcher(value).replaceAll("").length();
    }

    private int resolveWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns != null && !columns.isBlank()) {
            try {
                return Math.max(MIN_WIDTH, Integer.parseInt(columns.trim()));
            } catch (NumberFormatException ignored) {
                // Fall back to the default width.
            }
        }
        return DEFAULT_WIDTH;
    }

    public enum Tone {
        PRIMARY,
        SUCCESS,
        WARN,
        ERROR
    }

    public record CommandHint(
            String command,
            String description
    ) {
    }

    public final class Spinner implements ProcessExecutor.ProgressListener {

        private final String label;
        private int frameIndex;
        private int longestRenderedLine;
        private Duration lastElapsed = Duration.ZERO;

        private Spinner(String label) {
            this.label = label;
        }

        @Override
        public void onStart(List<String> command) {
            if (!animationsEnabled) {
                out.println("  %s %s".formatted(badge(Tone.PRIMARY, "run"), label));
                out.flush();
                return;
            }
            render(SPINNER_FRAMES[frameIndex], Duration.ZERO);
        }

        @Override
        public void onHeartbeat(Duration elapsed) {
            lastElapsed = elapsed;
            if (!animationsEnabled) {
                return;
            }
            frameIndex = (frameIndex + 1) % SPINNER_FRAMES.length;
            render(SPINNER_FRAMES[frameIndex], elapsed);
        }

        @Override
        public void onFinish(ProcessExecutor.ExecutionResult result, Duration elapsed) {
            lastElapsed = elapsed;
            Tone tone = result.timedOut() || result.exitCode() != 0 ? Tone.ERROR : Tone.SUCCESS;
            String state = result.timedOut() ? "timeout" : result.exitCode() == 0 ? "done" : "failed";
            finish("%s %s %s".formatted(badge(tone, state), label, muted(formatDuration(lastElapsed))));
        }

        private void render(char frame, Duration elapsed) {
            String line = "%s %s %s".formatted(
                    badge(Tone.PRIMARY, "run"),
                    label,
                    muted(frame + " " + formatDuration(elapsed))
            );
            int visibleLength = visibleLength(line);
            longestRenderedLine = Math.max(longestRenderedLine, visibleLength);
            out.print("\r" + line + " ".repeat(Math.max(0, longestRenderedLine - visibleLength)));
            out.flush();
        }

        private void finish(String line) {
            if (animationsEnabled) {
                int visibleLength = visibleLength(line);
                out.print("\r" + line + " ".repeat(Math.max(0, longestRenderedLine - visibleLength)));
                out.println();
            } else {
                out.println("  " + line);
            }
            out.flush();
        }
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0L, duration.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }
}
