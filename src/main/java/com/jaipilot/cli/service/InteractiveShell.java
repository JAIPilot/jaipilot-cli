package com.jaipilot.cli.service;

import com.jaipilot.cli.JaiPilotCli;
import com.jaipilot.cli.ui.TerminalUi;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;
import org.jline.widget.AutosuggestionWidgets;
import picocli.CommandLine;

public final class InteractiveShell {

    private final JavaProjectService projectService;

    public InteractiveShell(JavaProjectService projectService) {
        this.projectService = projectService;
    }

    public int run(PrintWriter out, PrintWriter err) {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path projectRoot = projectService.resolveProjectRoot(workingDirectory);
        TerminalUi ui = new TerminalUi(out);
        ui.printShellWelcome(
                projectRoot.toString(),
                projectService.detectBuildToolIfPresent(projectRoot)
                        .map(JavaProjectService.BuildTool::displayName)
                        .orElse("none"),
                "codex"
        );

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .jna(false)
                .jni(false)
                .jansi(false)
                .build()) {
            LineReader reader = buildReader(terminal, projectRoot);
            while (true) {
                String line;
                try {
                    line = reader.readLine(ui.prompt());
                } catch (UserInterruptException interrupted) {
                    continue;
                } catch (EndOfFileException endOfFile) {
                    out.println();
                    out.flush();
                    return CommandLine.ExitCode.OK;
                }

                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if ("/exit".equals(trimmed) || "/quit".equals(trimmed)) {
                    return CommandLine.ExitCode.OK;
                }
                if ("/help".equals(trimmed)) {
                    ui.printShellHelp();
                    continue;
                }

                String[] args = translate(trimmed);
                if (args.length == 0) {
                    ui.warn("Unsupported command. Use /help.");
                    continue;
                }

                int exitCode = JaiPilotCli.createCommandLine()
                        .setOut(out)
                        .setErr(err)
                        .execute(args);
                if (exitCode != CommandLine.ExitCode.OK) {
                    ui.warn("Command exited with code " + exitCode + ".");
                }
                ui.blankLine();
            }
        } catch (IOException exception) {
            err.println("Interactive shell failed: " + exception.getMessage());
            err.flush();
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private LineReader buildReader(Terminal terminal, Path projectRoot) throws IOException {
        Path historyPath = Path.of(System.getProperty("user.home"), ".jaipilot", "history");
        Files.createDirectories(historyPath.getParent());
        LineReader reader = LineReaderBuilder.builder()
                .appName("jaipilot")
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, historyPath)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.CASE_INSENSITIVE, true)
                .option(LineReader.Option.COMPLETE_MATCHER_CAMELCASE, true)
                .option(LineReader.Option.AUTO_LIST, true)
                .option(LineReader.Option.LIST_AMBIGUOUS, true)
                .option(LineReader.Option.AUTO_MENU, true)
                .option(LineReader.Option.AUTO_MENU_LIST, true)
                .option(LineReader.Option.MENU_COMPLETE, true)
                .option(LineReader.Option.LIST_PACKED, true)
                .option(LineReader.Option.AUTO_GROUP, true)
                .option(LineReader.Option.GROUP_PERSIST, true)
                .completer(new InteractiveShellCompleter(projectService, projectRoot))
                .build();
        reader.setVariable(LineReader.LIST_MAX, 10_000);
        reader.setVariable(LineReader.MENU_LIST_MAX, 10_000);
        applyCompletionTheme(reader);
        bindCompletionKeys(reader, terminal);
        new AutosuggestionWidgets(reader).enable();
        return reader;
    }

    static void applyCompletionTheme(LineReader reader) {
        Objects.requireNonNull(reader, "reader");
        for (Map.Entry<String, String> entry : completionTheme().entrySet()) {
            reader.setVariable(entry.getKey(), entry.getValue());
        }
    }

    static Map<String, String> completionTheme() {
        LinkedHashMap<String, String> theme = new LinkedHashMap<>();
        theme.put(LineReader.COMPLETION_STYLE_STARTING, "fg:cyan,bold");
        theme.put(LineReader.COMPLETION_STYLE_DESCRIPTION, "fg:bright-black");
        theme.put(LineReader.COMPLETION_STYLE_GROUP, "fg:cyan,bold");
        theme.put(LineReader.COMPLETION_STYLE_SELECTION, "fg:black,bg:cyan,bold");
        theme.put(LineReader.COMPLETION_STYLE_BACKGROUND, "bg:default");
        theme.put(LineReader.COMPLETION_STYLE_LIST_STARTING, "fg:cyan,bold");
        theme.put(LineReader.COMPLETION_STYLE_LIST_DESCRIPTION, "fg:bright-black");
        theme.put(LineReader.COMPLETION_STYLE_LIST_GROUP, "fg:cyan,bold");
        theme.put(LineReader.COMPLETION_STYLE_LIST_SELECTION, "fg:black,bg:cyan,bold");
        theme.put(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:default");
        return theme;
    }

    private void bindCompletionKeys(LineReader reader, Terminal terminal) {
        KeyMap<Binding> mainKeyMap = reader.getKeyMaps().get(LineReader.MAIN);
        if (mainKeyMap == null) {
            return;
        }
        mainKeyMap.bind(new Reference(LineReader.MENU_COMPLETE), "\t");
        String reverseMenuKey = KeyMap.key(terminal, Capability.key_btab);
        if (reverseMenuKey != null && !reverseMenuKey.isBlank()) {
            mainKeyMap.bind(new Reference(LineReader.REVERSE_MENU_COMPLETE), reverseMenuKey);
        }
    }

    private String[] translate(String input) {
        if (!input.startsWith("/")) {
            return new String[0];
        }

        String[] tokens = tokenize(input.substring(1));
        if (tokens.length == 0) {
            return new String[0];
        }
        if ("doctor".equals(tokens[0])) {
            return Arrays.copyOf(tokens, tokens.length);
        }
        if ("status".equals(tokens[0])) {
            if (tokens.length == 2 && !tokens[1].startsWith("-")) {
                return new String[] {"status", "--threshold", stripPercent(tokens[1])};
            }
            return Arrays.copyOf(tokens, tokens.length);
        }
        if (!"generate".equals(tokens[0])) {
            return new String[0];
        }

        if (tokens.length >= 3 && "generate".equals(tokens[0]) && "all".equals(tokens[1]) && "changed".equals(tokens[2])) {
            return new String[] {"generate", "--changed"};
        }
        if (tokens.length >= 3 && "generate".equals(tokens[0]) && "all".equals(tokens[1]) && "uncommitted".equals(tokens[2])) {
            return new String[] {"generate", "--changed"};
        }
        if (tokens.length >= 4 && "generate".equals(tokens[0]) && "all".equals(tokens[1]) && "for".equals(tokens[2])
                && "uncommitted".equals(tokens[3])) {
            return new String[] {"generate", "--changed"};
        }
        if (tokens.length >= 4 && "generate".equals(tokens[0]) && "all".equals(tokens[1]) && "coverage".equals(tokens[2])) {
            return new String[] {"generate", "--coverage-below", stripPercent(tokens[3])};
        }
        if (tokens.length == 3 && "generate".equals(tokens[0]) && "all".equals(tokens[1]) && "coverage".equals(tokens[2])) {
            return new String[] {"generate", "--coverage-below", String.valueOf(com.jaipilot.cli.commands.StatusCommand.DEFAULT_COVERAGE_THRESHOLD)};
        }
        if (tokens.length >= 5 && "generate".equals(tokens[0]) && "all".equals(tokens[1]) && "for".equals(tokens[2])
                && "coverage".equals(tokens[4])) {
            return new String[] {"generate", "--coverage-below", stripPercent(tokens[3])};
        }
        if (tokens.length >= 1 && "generate".equals(tokens[0])) {
            return Arrays.copyOf(tokens, tokens.length);
        }
        return new String[0];
    }

    private String[] tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);
            if (inQuotes) {
                if (currentChar == quoteChar) {
                    inQuotes = false;
                    quoteChar = 0;
                } else {
                    current.append(currentChar);
                }
                continue;
            }
            if (currentChar == '\'' || currentChar == '"') {
                inQuotes = true;
                quoteChar = currentChar;
                continue;
            }
            if (Character.isWhitespace(currentChar)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(currentChar);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens.toArray(String[]::new);
    }

    private String stripPercent(String value) {
        if (value.endsWith("%")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
