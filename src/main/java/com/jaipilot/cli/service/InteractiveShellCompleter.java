package com.jaipilot.cli.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

public final class InteractiveShellCompleter implements Completer {

    private static final Duration CLASS_CACHE_TTL = Duration.ofSeconds(3);
    private static final List<String> ROOT_COMMANDS = List.of(
            "/generate",
            "/status",
            "/doctor",
            "/help",
            "/exit"
    );
    private static final List<String> GENERATE_OPTIONS = List.of(
            "--changed",
            "--coverage-below",
            "--agent",
            "--model",
            "--show-logs"
    );
    private static final List<String> STATUS_OPTIONS = List.of("--threshold");
    private static final List<String> THRESHOLD_VALUES = List.of("70", "75", "80", "85", "90", "95");
    private static final List<String> GENERATE_ALL_VALUES = List.of("changed", "uncommitted", "coverage", "for");
    private static final List<String> FOR_VALUES = List.of("uncommitted", "80", "85", "90");
    private static final List<String> AGENT_VALUES = List.of("codex");

    private final JavaProjectService projectService;
    private final Path projectRoot;

    private long classCacheLoadedAtNanos;
    private List<Candidate> simpleClassCandidates = List.of();
    private List<Candidate> fullyQualifiedCandidates = List.of();
    private List<Candidate> pathCandidates = List.of();

    public InteractiveShellCompleter(JavaProjectService projectService, Path projectRoot) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        candidates.addAll(suggest(line.line(), line.cursor()));
    }

    List<Candidate> suggest(String line, int cursor) {
        CompletionContext context = CompletionContext.parse(line, cursor);
        if (context.tokens().isEmpty() || context.activeIndex() == 0) {
            return commandCandidates(ROOT_COMMANDS, "commands", "Top-level shell command");
        }

        String command = context.tokens().get(0);
        if (!command.startsWith("/")) {
            return List.of();
        }
        return switch (command) {
            case "/generate" -> suggestGenerate(context);
            case "/status" -> suggestStatus(context);
            case "/doctor", "/help", "/exit", "/quit" -> List.of();
            default -> commandCandidates(ROOT_COMMANDS, "commands", "Top-level shell command");
        };
    }

    private List<Candidate> suggestGenerate(CompletionContext context) {
        if (expectsValue(context, "--coverage-below")) {
            return valueCandidates(THRESHOLD_VALUES, "threshold", "Coverage threshold");
        }
        if (expectsValue(context, "--agent")) {
            return valueCandidates(AGENT_VALUES, "agent", "Agent provider");
        }
        if (expectsValue(context, "--model")) {
            return List.of();
        }

        if (isAllAliasFlow(context)) {
            return suggestGenerateAll(context);
        }

        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(generateOptionCandidates(context));
        if (selectorStillRequired(context) && !context.currentPrefix().startsWith("-")) {
            candidates.addAll(classSelectorCandidates(context.currentPrefix()));
        }
        return candidates;
    }

    private List<Candidate> suggestGenerateAll(CompletionContext context) {
        if (context.activeIndex() == 2) {
            return valueCandidates(GENERATE_ALL_VALUES, "generate-all", "Batch generation mode");
        }
        if (context.activeIndex() == 3 && "coverage".equals(tokenAt(context, 2))) {
            return valueCandidates(THRESHOLD_VALUES, "threshold", "Coverage threshold");
        }
        if (context.activeIndex() == 3 && "for".equals(tokenAt(context, 2))) {
            return valueCandidates(FOR_VALUES, "generate-all", "Batch generation target");
        }
        if (context.activeIndex() == 4 && "for".equals(tokenAt(context, 2)) && looksNumeric(tokenAt(context, 3))) {
            return valueCandidates(List.of("coverage"), "generate-all", "Complete the coverage phrase");
        }
        return List.of();
    }

    private List<Candidate> suggestStatus(CompletionContext context) {
        if (expectsValue(context, "--threshold")) {
            return valueCandidates(THRESHOLD_VALUES, "threshold", "Coverage threshold");
        }
        if (context.activeIndex() == 1) {
            return commandCandidates(STATUS_OPTIONS, "status", "Status command option");
        }
        return List.of();
    }

    private List<Candidate> generateOptionCandidates(CompletionContext context) {
        List<Candidate> candidates = new ArrayList<>();
        for (String option : GENERATE_OPTIONS) {
            if (context.tokens().contains(option)) {
                continue;
            }
            candidates.add(optionCandidate(option));
        }
        if (context.activeIndex() == 1 && !context.currentPrefix().startsWith("-")) {
            candidates.add(simpleValueCandidate("all", "generate", "Natural-language batch mode"));
        }
        return candidates;
    }

    private List<Candidate> classSelectorCandidates(String prefix) {
        refreshClassCacheIfNeeded();
        if (prefix.contains("/") || prefix.contains("\\") || prefix.endsWith(".java")) {
            return pathCandidates;
        }
        if (prefix.contains(".")) {
            return fullyQualifiedCandidates;
        }
        return simpleClassCandidates;
    }

    private synchronized void refreshClassCacheIfNeeded() {
        long now = System.nanoTime();
        if (!simpleClassCandidates.isEmpty()
                && now - classCacheLoadedAtNanos <= CLASS_CACHE_TTL.toNanos()) {
            return;
        }

        List<JavaProjectService.JavaClassDescriptor> classes = projectService.findProductionClasses(projectRoot);
        Map<String, Integer> simpleNameCounts = new HashMap<>();
        for (JavaProjectService.JavaClassDescriptor descriptor : classes) {
            simpleNameCounts.merge(descriptor.className(), 1, Integer::sum);
        }

        List<Candidate> simpleCandidates = new ArrayList<>();
        List<Candidate> fqcnCandidates = new ArrayList<>();
        List<Candidate> sourcePathCandidates = new ArrayList<>();
        for (JavaProjectService.JavaClassDescriptor descriptor : classes) {
            String relativePath = normalize(projectRoot.relativize(descriptor.cutPath()));
            fqcnCandidates.add(new Candidate(
                    descriptor.fullyQualifiedName(),
                    descriptor.fullyQualifiedName(),
                    "fully-qualified",
                    relativePath,
                    null,
                    null,
                    true
            ));
            sourcePathCandidates.add(new Candidate(
                    relativePath,
                    relativePath,
                    "path",
                    descriptor.fullyQualifiedName(),
                    null,
                    null,
                    true
            ));
            if (simpleNameCounts.getOrDefault(descriptor.className(), 0) == 1) {
                simpleCandidates.add(new Candidate(
                        descriptor.className(),
                        descriptor.className(),
                        "class",
                        descriptor.fullyQualifiedName(),
                        null,
                        null,
                        true
                ));
            }
        }

        this.simpleClassCandidates = dedupe(simpleCandidates);
        this.fullyQualifiedCandidates = dedupe(fqcnCandidates);
        this.pathCandidates = dedupe(sourcePathCandidates);
        this.classCacheLoadedAtNanos = now;
    }

    private List<Candidate> dedupe(List<Candidate> values) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Candidate> deduped = new ArrayList<>();
        for (Candidate candidate : values) {
            if (seen.add(candidate.value())) {
                deduped.add(candidate);
            }
        }
        return deduped;
    }

    private boolean selectorStillRequired(CompletionContext context) {
        List<String> tokens = context.tokens();
        for (int index = 1; index < tokens.size(); index++) {
            if (index == context.activeIndex() && !context.endsWithSpace()) {
                break;
            }
            String token = tokens.get(index);
            if ("all".equals(token)) {
                return false;
            }
            if (isOptionWithValue(token)) {
                index++;
                continue;
            }
            if (GENERATE_OPTIONS.contains(token)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private boolean expectsValue(CompletionContext context, String option) {
        return context.activeIndex() > 0 && option.equals(tokenAt(context, context.activeIndex() - 1));
    }

    private boolean isAllAliasFlow(CompletionContext context) {
        return "all".equals(tokenAt(context, 1));
    }

    private boolean isOptionWithValue(String token) {
        return "--coverage-below".equals(token) || "--agent".equals(token) || "--model".equals(token)
                || "--threshold".equals(token);
    }

    private String tokenAt(CompletionContext context, int index) {
        if (index < 0 || index >= context.tokens().size()) {
            return "";
        }
        return context.tokens().get(index);
    }

    private boolean looksNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(value.replace("%", ""));
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private List<Candidate> commandCandidates(List<String> values, String group, String description) {
        return values.stream()
                .map(value -> new Candidate(value, value, group, description, null, null, true))
                .toList();
    }

    private List<Candidate> valueCandidates(List<String> values, String group, String description) {
        return values.stream()
                .map(value -> new Candidate(value, value, group, description, null, null, true))
                .toList();
    }

    private Candidate optionCandidate(String option) {
        String description = switch (option) {
            case "--changed" -> "Generate tests for changed or uncommitted production classes";
            case "--coverage-below" -> "Generate tests for classes below a coverage threshold";
            case "--agent" -> "Override the agent provider";
            case "--model" -> "Override the Codex model";
            case "--show-logs" -> "Stream live Codex, validation, and JaCoCo logs";
            default -> "Command option";
        };
        return new Candidate(option, option, "option", description, null, null, true);
    }

    private Candidate simpleValueCandidate(String value, String group, String description) {
        return new Candidate(value, value, group, description, null, null, true);
    }

    static final class CompletionContext {

        private final List<String> tokens;
        private final int activeIndex;
        private final String currentPrefix;
        private final boolean endsWithSpace;

        private CompletionContext(List<String> tokens, int activeIndex, String currentPrefix, boolean endsWithSpace) {
            this.tokens = tokens;
            this.activeIndex = activeIndex;
            this.currentPrefix = currentPrefix;
            this.endsWithSpace = endsWithSpace;
        }

        static CompletionContext parse(String line, int cursor) {
            String partialLine = line == null ? "" : line.substring(0, Math.min(cursor, line.length()));
            List<String> tokens = tokenize(partialLine);
            boolean endsWithSpace = !partialLine.isEmpty()
                    && Character.isWhitespace(partialLine.charAt(partialLine.length() - 1));
            int activeIndex = tokens.isEmpty() ? 0 : endsWithSpace ? tokens.size() : tokens.size() - 1;
            String currentPrefix = tokens.isEmpty() || endsWithSpace ? "" : tokens.get(tokens.size() - 1);
            return new CompletionContext(tokens, activeIndex, currentPrefix, endsWithSpace);
        }

        private static List<String> tokenize(String value) {
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
            return tokens;
        }

        List<String> tokens() {
            return tokens;
        }

        int activeIndex() {
            return activeIndex;
        }

        String currentPrefix() {
            return currentPrefix;
        }

        boolean endsWithSpace() {
            return endsWithSpace;
        }
    }
}
