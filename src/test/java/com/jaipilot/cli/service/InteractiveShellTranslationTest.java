package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class InteractiveShellTranslationTest {

    private final InteractiveShell shell = new InteractiveShell(
            new JavaProjectService(new ProjectFileService(), new CoverageReportService())
    );

    @Test
    void coverageModePreservesThresholdAndTrailingOptions() {
        assertArrayEquals(
                new String[] {"generate", "--coverage-below", "85", "--show-logs", "--model", "gpt-5"},
                shell.translate("/generate all coverage 85% --show-logs --model gpt-5")
        );
        assertArrayEquals(
                new String[] {"generate", "--coverage-below", "85", "--show-logs"},
                shell.translate("/generate all for 85 coverage --show-logs")
        );
    }

    @Test
    void coverageModeUsesDefaultThresholdWithoutConsumingTrailingOptions() {
        assertArrayEquals(
                new String[] {"generate", "--coverage-below", "80.0", "--show-logs", "--model", "gpt-5"},
                shell.translate("/generate all coverage --show-logs --model gpt-5")
        );
    }

    @Test
    void changedModeAliasesPreserveTrailingOptions() {
        assertArrayEquals(
                new String[] {"generate", "--changed", "--show-logs", "--model", "gpt-5"},
                shell.translate("/generate all changed --show-logs --model gpt-5")
        );
        assertArrayEquals(
                new String[] {"generate", "--changed", "--show-logs"},
                shell.translate("/generate all uncommitted --show-logs")
        );
        assertArrayEquals(
                new String[] {"generate", "--changed", "--model", "gpt 5"},
                shell.translate("/generate all for uncommitted --model 'gpt 5'")
        );
    }
}
