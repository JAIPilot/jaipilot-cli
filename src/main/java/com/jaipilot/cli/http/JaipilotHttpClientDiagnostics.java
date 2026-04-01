package com.jaipilot.cli.http;

import java.util.List;

public record JaipilotHttpClientDiagnostics(
        TrustMode trustMode,
        String trustStorePath,
        String trustStoreType,
        boolean systemCaRequested,
        List<String> systemCaSources,
        String systemCaStatus,
        ProxyMode proxyMode,
        String httpsProxy,
        String httpProxy,
        String noProxy
) {

    public JaipilotHttpClientDiagnostics {
        systemCaSources = systemCaSources == null ? List.of() : List.copyOf(systemCaSources);
        systemCaStatus = normalizeBlank(systemCaStatus);
        trustStorePath = normalizeBlank(trustStorePath);
        trustStoreType = normalizeBlank(trustStoreType);
        httpsProxy = normalizeBlank(httpsProxy);
        httpProxy = normalizeBlank(httpProxy);
        noProxy = normalizeBlank(noProxy);
    }

    public static JaipilotHttpClientDiagnostics defaults() {
        return new JaipilotHttpClientDiagnostics(
                TrustMode.DEFAULT_JSSE,
                null,
                null,
                false,
                List.of(),
                "Native system CA merge disabled.",
                ProxyMode.DIRECT,
                null,
                null,
                null
        );
    }

    public String trustSummary() {
        StringBuilder summary = new StringBuilder();
        if (trustMode == TrustMode.CUSTOM_TRUST_STORE) {
            summary.append("Custom trust store");
            if (trustStorePath != null) {
                summary.append(": ").append(trustStorePath);
            }
            if (trustStoreType != null) {
                summary.append(" (").append(trustStoreType).append(')');
            }
        } else {
            summary.append("Bundled/default JSSE trust roots");
        }

        if (!systemCaSources.isEmpty()) {
            summary.append(" + native CA stores ").append(String.join(", ", systemCaSources));
        } else if (systemCaRequested && systemCaStatus != null) {
            summary.append(" (native CA merge unavailable: ").append(systemCaStatus).append(')');
        }
        return summary.toString();
    }

    public String proxySummary() {
        return switch (proxyMode) {
            case ENVIRONMENT -> {
                StringBuilder summary = new StringBuilder("Environment proxies");
                if (httpsProxy != null) {
                    summary.append(" https=").append(httpsProxy);
                }
                if (httpProxy != null) {
                    summary.append(" http=").append(httpProxy);
                }
                if (noProxy != null) {
                    summary.append(" no_proxy=").append(noProxy);
                }
                yield summary.toString();
            }
            case JVM_PROPERTIES -> "JVM proxy properties";
            case SYSTEM -> "System proxy selector (java.net.useSystemProxies=true)";
            case DIRECT -> "Direct network access";
        };
    }

    public enum TrustMode {
        DEFAULT_JSSE,
        CUSTOM_TRUST_STORE
    }

    public enum ProxyMode {
        ENVIRONMENT,
        JVM_PROPERTIES,
        SYSTEM,
        DIRECT
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
