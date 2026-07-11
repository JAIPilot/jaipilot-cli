package com.jaipilot.cli.service;

import com.jaipilot.cli.JaiPilotCli;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import picocli.CommandLine;

public final class InteractiveShell {

    private final JavaProjectService projectService;

    public InteractiveShell(JavaProjectService projectService) {
        this.projectService = projectService;
    }

    public int run(PrintWriter out, PrintWriter err) {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path projectRoot = projectService.resolveProjectRoot(workingDirectory);
        out.println("JAIPilot");
        out.printf("Project: %s%n", projectRoot);
        out.printf("Build: %s%n", projectService.detectBuildTool(projectRoot).displayName());
        out.println("Agent: codex");
        out.println();
        out.println("Commands:");
        out.println("  /generate <class>");
        out.println("  /generate all changed");
        out.println("  /generate all uncommitted");
        out.println("  /generate all coverage 80");
        out.println("  /generate all for 80% coverage");
        out.println("  /status");
        out.println("  /doctor");
        out.println("  /help");
        out.println("  /exit");
        out.println();
        out.flush();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                out.print("jaipilot> ");
                out.flush();
                String line = reader.readLine();
                if (line == null) {
                    out.println();
                    return CommandLine.ExitCode.OK;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if ("/exit".equals(trimmed) || "/quit".equals(trimmed)) {
                    return CommandLine.ExitCode.OK;
                }

                String[] args = translate(trimmed);
                if (args.length == 0) {
                    out.println("Unsupported command. Use /help.");
                    out.flush();
                    continue;
                }

                int exitCode = new CommandLine(new JaiPilotCli())
                        .setOut(out)
                        .setErr(err)
                        .execute(args);
                if (exitCode != CommandLine.ExitCode.OK) {
                    out.printf("Command exited with code %d.%n", exitCode);
                }
                out.flush();
            }
        } catch (IOException exception) {
            err.println("Interactive shell failed: " + exception.getMessage());
            err.flush();
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private String[] translate(String input) {
        if ("/help".equals(input)) {
            return new String[] {"--help"};
        }
        if ("/doctor".equals(input)) {
            return new String[] {"doctor"};
        }
        if ("/status".equals(input)) {
            return new String[] {"status"};
        }
        if (!input.startsWith("/generate")) {
            return new String[0];
        }

        String[] tokens = tokenize(input.substring(1));
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
        if (tokens.length >= 2 && "generate".equals(tokens[0])) {
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
