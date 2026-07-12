package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;

class InteractiveShellThemeTest {

    @Test
    void completionThemeUsesNeutralMenuBackground() {
        Map<String, String> theme = InteractiveShell.completionTheme();

        assertEquals("bg:default", theme.get(LineReader.COMPLETION_STYLE_BACKGROUND));
        assertEquals("bg:default", theme.get(LineReader.COMPLETION_STYLE_LIST_BACKGROUND));
        assertEquals("fg:black,bg:cyan,bold", theme.get(LineReader.COMPLETION_STYLE_SELECTION));
        assertEquals("fg:black,bg:cyan,bold", theme.get(LineReader.COMPLETION_STYLE_LIST_SELECTION));
        assertFalse(theme.containsValue("bg:bright-magenta"));
    }
}
