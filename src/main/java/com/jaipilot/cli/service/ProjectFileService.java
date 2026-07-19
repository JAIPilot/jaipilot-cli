package com.jaipilot.cli.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class ProjectFileService {

    private static final List<String> GRADLE_BUILD_FILES = List.of(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts"
    );
    private static final Set<String> COPY_EXCLUDED_DIRECTORY_NAMES = Set.of(
            ".git",
            "target",
            "build",
            ".gradle",
            ".idea",
            ".vscode",
            "out"
    );
    private static final Set<String> COPY_EXCLUDED_FILE_NAMES = Set.of(
            ".DS_Store"
    );

    public ProjectFileService() {
    }

    public Path resolvePath(Path projectRoot, Path path) {
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return projectRoot.resolve(path).normalize();
    }

    public String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read file " + path, exception);
        }
    }

    public String readResource(String resourcePath) {
        try (InputStream inputStream = ProjectFileService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Classpath resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read classpath resource " + resourcePath, exception);
        }
    }

    public void writeFile(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write file " + path, exception);
        }
    }

    public void writeFilesTransactionally(
            Map<Path, String> contentsByPath,
            Map<Path, FileFingerprint> expectedFingerprints
    ) {
        List<Path> paths = contentsByPath.keySet().stream().sorted().toList();
        Map<Path, Path> stagedFiles = new LinkedHashMap<>();
        Map<Path, byte[]> originalContents = new LinkedHashMap<>();
        List<Path> movedPaths = new ArrayList<>();
        try {
            verifyFingerprints(paths, expectedFingerprints);
            for (Path path : paths) {
                Path parent = path.getParent();
                if (parent == null) {
                    throw new IOException("Cannot stage a file without a parent directory: " + path);
                }
                Files.createDirectories(parent);
                if (Files.isRegularFile(path)) {
                    originalContents.put(path, Files.readAllBytes(path));
                }
                Path staged = Files.createTempFile(parent, ".jaipilot-", ".tmp");
                Files.writeString(staged, contentsByPath.get(path), StandardCharsets.UTF_8);
                stagedFiles.put(path, staged);
            }
            verifyFingerprints(paths, expectedFingerprints);
            for (Path path : paths) {
                moveReplacing(stagedFiles.get(path), path);
                movedPaths.add(path);
            }
        } catch (Exception exception) {
            rollbackFiles(movedPaths, originalContents);
            throw new IllegalStateException("Failed to merge generated tests transactionally.", exception);
        } finally {
            stagedFiles.values().forEach(staged -> {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Best-effort cleanup for a failed transaction.
                }
            });
        }
    }

    private void verifyFingerprints(List<Path> paths, Map<Path, FileFingerprint> expectedFingerprints) {
        List<Path> drifted = paths.stream().filter(path -> {
            FileFingerprint current = Files.isRegularFile(path) ? fingerprint(path) : null;
            return !Objects.equals(expectedFingerprints.get(path), current);
        }).toList();
        if (!drifted.isEmpty()) {
            throw new IllegalStateException("Test files changed while batch generation was running: " + drifted);
        }
    }

    private void rollbackFiles(List<Path> movedPaths, Map<Path, byte[]> originalContents) {
        List<Path> reverseOrder = new ArrayList<>(movedPaths);
        reverseOrder.sort(Comparator.reverseOrder());
        for (Path path : reverseOrder) {
            try {
                byte[] original = originalContents.get(path);
                if (original == null) {
                    Files.deleteIfExists(path);
                    continue;
                }
                Path staged = Files.createTempFile(path.getParent(), ".jaipilot-rollback-", ".tmp");
                Files.write(staged, original);
                moveReplacing(staged, path);
            } catch (Exception ignored) {
                // Preserve the original merge exception; rollback is best effort.
            }
        }
    }

    private void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Map<Path, FileFingerprint> snapshotJavaTestFiles(Path root) {
        return snapshotFiles(root, this::isJavaTestPath);
    }

    public Map<Path, FileFingerprint> snapshotJavaSourceFiles(Path root) {
        return snapshotFiles(root, this::isJavaSourcePath);
    }

    private Map<Path, FileFingerprint> snapshotFiles(Path root, Predicate<Path> pathFilter) {
        Map<Path, FileFingerprint> snapshot = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(pathFilter)
                    .forEach(path -> snapshot.put(path.normalize(), fingerprint(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan files under " + root, exception);
        }
        return snapshot;
    }

    public void copyProjectWorkspace(Path sourceRoot, Path destinationRoot) {
        try {
            Files.createDirectories(destinationRoot);
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    String name = directory.getFileName() == null ? "" : directory.getFileName().toString();
                    if (!directory.equals(sourceRoot) && COPY_EXCLUDED_DIRECTORY_NAMES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(destinationRoot.resolve(sourceRoot.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (COPY_EXCLUDED_FILE_NAMES.contains(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path destination = destinationRoot.resolve(sourceRoot.relativize(file));
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    if (Files.isExecutable(file)) {
                        destination.toFile().setExecutable(true, false);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to create isolated workspace from " + sourceRoot + " to " + destinationRoot,
                    exception
            );
        }
    }

    public void deleteRecursively(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete directory " + root, exception);
        }
    }

    public Path findNearestBuildProjectRoot(Path path) {
        Path current = path.normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (containsBuildFile(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public String stripJavaExtension(String fileName) {
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - ".java".length());
        }
        return fileName;
    }

    private boolean containsBuildFile(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml")) || containsGradleBuildFile(directory);
    }

    private boolean containsGradleBuildFile(Path directory) {
        return GRADLE_BUILD_FILES.stream()
                .map(directory::resolve)
                .anyMatch(Files::isRegularFile);
    }

    private boolean isJavaTestPath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith(".java") && normalized.contains("/src/test/java/");
    }

    private boolean isJavaSourcePath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.endsWith(".java")
                && (normalized.contains("/src/main/java/") || normalized.contains("/src/test/java/"));
    }

    private FileFingerprint fingerprint(Path path) {
        try {
            return new FileFingerprint(Files.size(path), sha256(Files.readAllBytes(path)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint file " + path, exception);
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    public record FileFingerprint(
            long size,
            String sha256
    ) {
    }
}
