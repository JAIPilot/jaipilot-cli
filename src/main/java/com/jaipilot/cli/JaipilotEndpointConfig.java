package com.jaipilot.cli;

public final class JaipilotEndpointConfig {

    public static final String DEFAULT_BACKEND_URL = "https://otxfylhjrlaesjagfhfi.supabase.co";

    private JaipilotEndpointConfig() {
    }

    public static String resolveBackendUrl() {
        return DEFAULT_BACKEND_URL;
    }
}
