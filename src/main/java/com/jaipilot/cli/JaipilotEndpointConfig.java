package com.jaipilot.cli;

public final class JaipilotEndpointConfig {

    public static final String DEFAULT_WEBSITE_BASE = "https://www.jaipilot.com";
    public static final String DEFAULT_BACKEND_URL = "https://otxfylhjrlaesjagfhfi.supabase.co";

    private JaipilotEndpointConfig() {
    }

    public static String resolveWebsiteBase() {
        return trimTrailingSlash(firstNonBlank(
                System.getenv("JAIPILOT_WEBSITE_BASE"),
                System.getProperty("jaipilot.website.base"),
                DEFAULT_WEBSITE_BASE
        ));
    }

    public static String resolveBackendUrl() {
        return trimTrailingSlash(firstNonBlank(
                System.getenv("JAIPILOT_BACKEND_URL"),
                System.getProperty("jaipilot.backend.url"),
                DEFAULT_BACKEND_URL
        ));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
