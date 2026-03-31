package com.jaipilot.cli.service;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UsedContextClassPathCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void writeOverwritesExistingEntryWithoutMerging() {
        UsedContextClassPathCache cache = new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json"));
        Path classPath = tempDir.resolve("src/main/java/com/example/CrashController.java");

        cache.write(classPath, List.of("com/example/Helper.java", "com/example/Owner.java"));
        cache.write(classPath, List.of("com/example/Pet.java"));

        assertEquals(List.of("com/example/Pet.java"), cache.read(classPath));
    }
}
