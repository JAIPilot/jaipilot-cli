package com.jaipilot.cli.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class JaipilotProxySelector extends ProxySelector {

    private final ProxySettings settings;
    private final ProxySelector fallback;

    JaipilotProxySelector(ProxySettings settings, ProxySelector fallback) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.fallback = fallback == null ? new DirectProxySelector() : fallback;
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri is required");
        }
        String host = uri.getHost();
        if (host != null && settings.isExcluded(host, effectivePort(uri))) {
            return List.of(Proxy.NO_PROXY);
        }

        Proxy proxy = settings.proxyFor(uri);
        if (proxy != null) {
            return List.of(proxy);
        }
        List<Proxy> selected = fallback.select(uri);
        return selected == null || selected.isEmpty() ? List.of(Proxy.NO_PROXY) : selected;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        fallback.connectFailed(uri, sa, ioe);
    }

    static ProxySettings fromEnvironment(String httpsProxy, String httpProxy, String noProxy) {
        return new ProxySettings(parseProxy(httpsProxy), parseProxy(httpProxy), parseNoProxy(noProxy));
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static ProxyEndpoint parseProxy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.contains("://") ? value : "http://" + value;
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid proxy URL: " + value, exception);
        }
        if (uri.getHost() == null || uri.getPort() < 0) {
            throw new IllegalStateException("Invalid proxy URL: " + value);
        }

        Proxy.Type type = uri.getScheme() != null && uri.getScheme().toLowerCase(Locale.ROOT).startsWith("socks")
                ? Proxy.Type.SOCKS
                : Proxy.Type.HTTP;
        return new ProxyEndpoint(
                new Proxy(type, new InetSocketAddress(uri.getHost(), uri.getPort())),
                sanitize(uri)
        );
    }

    private static String sanitize(URI uri) {
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        return scheme + "://" + host + ":" + port;
    }

    private static List<NoProxyRule> parseNoProxy(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }

        List<NoProxyRule> rules = new ArrayList<>();
        for (String token : rawValue.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                rules.add(NoProxyRule.parse(trimmed));
            }
        }
        return List.copyOf(rules);
    }

    static final class ProxySettings {

        private final ProxyEndpoint httpsProxy;
        private final ProxyEndpoint httpProxy;
        private final List<NoProxyRule> noProxyRules;

        ProxySettings(ProxyEndpoint httpsProxy, ProxyEndpoint httpProxy, List<NoProxyRule> noProxyRules) {
            this.httpsProxy = httpsProxy;
            this.httpProxy = httpProxy;
            this.noProxyRules = noProxyRules == null ? List.of() : List.copyOf(noProxyRules);
        }

        boolean hasConfiguredProxy() {
            return httpsProxy != null || httpProxy != null;
        }

        Proxy proxyFor(URI uri) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("https".equals(scheme)) {
                return httpsProxy != null ? httpsProxy.proxy() : httpProxy != null ? httpProxy.proxy() : null;
            }
            if ("http".equals(scheme)) {
                return httpProxy != null ? httpProxy.proxy() : null;
            }
            return null;
        }

        boolean isExcluded(String host, int port) {
            for (NoProxyRule rule : noProxyRules) {
                if (rule.matches(host, port)) {
                    return true;
                }
            }
            return false;
        }

        String httpsProxyDisplay() {
            return httpsProxy == null ? null : httpsProxy.displayValue();
        }

        String httpProxyDisplay() {
            return httpProxy == null ? null : httpProxy.displayValue();
        }
    }

    private record ProxyEndpoint(Proxy proxy, String displayValue) {
    }

    private record NoProxyRule(String hostPattern, Integer port) {

        static NoProxyRule parse(String rawValue) {
            if ("*".equals(rawValue)) {
                return new NoProxyRule("*", null);
            }
            int colonIndex = rawValue.lastIndexOf(':');
            if (colonIndex > 0 && colonIndex < rawValue.length() - 1 && rawValue.indexOf(']') < colonIndex) {
                try {
                    return new NoProxyRule(rawValue.substring(0, colonIndex), Integer.parseInt(rawValue.substring(colonIndex + 1)));
                } catch (NumberFormatException ignored) {
                    // Treat the value as a host-only rule.
                }
            }
            return new NoProxyRule(rawValue, null);
        }

        boolean matches(String host, int actualPort) {
            if ("*".equals(hostPattern)) {
                return true;
            }
            if (port != null && port != actualPort) {
                return false;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String normalizedPattern = hostPattern.toLowerCase(Locale.ROOT);
            if (normalizedPattern.startsWith("*.")) {
                String suffix = normalizedPattern.substring(1);
                return normalizedHost.endsWith(suffix) || normalizedHost.equals(normalizedPattern.substring(2));
            }
            if (normalizedPattern.startsWith(".")) {
                return normalizedHost.endsWith(normalizedPattern)
                        || normalizedHost.equals(normalizedPattern.substring(1));
            }
            return normalizedHost.equals(normalizedPattern)
                    || normalizedHost.endsWith("." + normalizedPattern);
        }
    }

    private static final class DirectProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // No-op for direct connections.
        }
    }
}
