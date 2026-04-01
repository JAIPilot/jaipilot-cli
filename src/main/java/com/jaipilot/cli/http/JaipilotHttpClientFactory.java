package com.jaipilot.cli.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

public final class JaipilotHttpClientFactory {

    private final Map<String, String> environment;
    private final Properties systemProperties;
    private final ProxySelector defaultProxySelector;
    private final String osName;

    public JaipilotHttpClientFactory() {
        this(System.getenv(), System.getProperties(), ProxySelector.getDefault(), System.getProperty("os.name", ""));
    }

    public JaipilotHttpClientFactory(
            Map<String, String> environment,
            Properties systemProperties,
            ProxySelector defaultProxySelector,
            String osName
    ) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.systemProperties = copyOf(Objects.requireNonNull(systemProperties, "systemProperties"));
        this.defaultProxySelector = defaultProxySelector;
        this.osName = osName == null ? "" : osName;
    }

    public HttpClient create(Duration connectTimeout) {
        ClientConfiguration configuration = resolveConfiguration();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .sslContext(configuration.sslContext());
        if (configuration.proxySelector() != null) {
            builder.proxy(configuration.proxySelector());
        }
        return builder.build();
    }

    public JaipilotHttpClientDiagnostics diagnostics() {
        return resolveConfiguration().diagnostics();
    }

    ProxySelector proxySelector() {
        return resolveConfiguration().proxySelector();
    }

    SSLContext sslContext() {
        return resolveConfiguration().sslContext();
    }

    private ClientConfiguration resolveConfiguration() {
        TrustConfiguration trustConfiguration = resolveTrustConfiguration();
        ProxyConfiguration proxyConfiguration = resolveProxyConfiguration();
        return new ClientConfiguration(
                buildSslContext(trustConfiguration.trustManagers()),
                proxyConfiguration.proxySelector(),
                trustConfiguration.diagnostics(proxyConfiguration.diagnostics())
        );
    }

    private TrustConfiguration resolveTrustConfiguration() {
        String trustStoreValue = firstNonBlank(environmentValue("JAIPILOT_TRUST_STORE"));
        String trustStoreType = null;
        List<X509ExtendedTrustManager> trustManagers = new ArrayList<>();
        JaipilotHttpClientDiagnostics.TrustMode trustMode;

        if (trustStoreValue != null) {
            Path trustStorePath = Path.of(trustStoreValue).toAbsolutePath().normalize();
            trustStoreType = resolveTrustStoreType(trustStorePath, environmentValue("JAIPILOT_TRUST_STORE_TYPE"));
            trustManagers.add(loadTrustManager(trustStorePath, trustStoreType, environmentValue("JAIPILOT_TRUST_STORE_PASSWORD")));
            trustMode = JaipilotHttpClientDiagnostics.TrustMode.CUSTOM_TRUST_STORE;
            trustStoreValue = trustStorePath.toString();
        } else {
            trustManagers.add(defaultTrustManager());
            trustMode = JaipilotHttpClientDiagnostics.TrustMode.DEFAULT_JSSE;
        }

        boolean systemCaRequested = parseBoolean(environmentValue("JAIPILOT_USE_SYSTEM_CA"));
        SystemCaLoadResult systemCaLoadResult = loadSystemCaTrustManagers(systemCaRequested);
        trustManagers.addAll(systemCaLoadResult.trustManagers());

        return new TrustConfiguration(
                trustManagers,
                trustMode,
                trustStoreValue,
                trustStoreType,
                systemCaRequested,
                systemCaLoadResult.sources(),
                systemCaLoadResult.status()
        );
    }

    private ProxyConfiguration resolveProxyConfiguration() {
        JaipilotProxySelector.ProxySettings settings = JaipilotProxySelector.fromEnvironment(
                environmentValue("HTTPS_PROXY", "https_proxy"),
                environmentValue("HTTP_PROXY", "http_proxy"),
                environmentValue("NO_PROXY", "no_proxy")
        );
        ProxySelector proxySelector = settings.hasConfiguredProxy()
                ? new JaipilotProxySelector(settings, defaultProxySelector)
                : defaultProxySelector;

        JaipilotHttpClientDiagnostics.ProxyMode proxyMode;
        if (settings.hasConfiguredProxy()) {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.ENVIRONMENT;
        } else if (hasJvmProxyProperties()) {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.JVM_PROPERTIES;
        } else if (parseBoolean(systemProperties.getProperty("java.net.useSystemProxies"))) {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.SYSTEM;
        } else {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.DIRECT;
        }

        return new ProxyConfiguration(
                proxySelector,
                new JaipilotHttpClientDiagnostics(
                        JaipilotHttpClientDiagnostics.TrustMode.DEFAULT_JSSE,
                        null,
                        null,
                        false,
                        List.of(),
                        null,
                        proxyMode,
                        settings.httpsProxyDisplay(),
                        settings.httpProxyDisplay(),
                        firstNonBlank(environmentValue("NO_PROXY", "no_proxy"))
                )
        );
    }

    private X509ExtendedTrustManager defaultTrustManager() {
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            return extractTrustManager(trustManagerFactory);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to initialize the default Java trust store.", exception);
        }
    }

    private X509ExtendedTrustManager loadTrustManager(Path trustStorePath, String trustStoreType, String password) {
        if (!Files.isRegularFile(trustStorePath)) {
            throw new IllegalStateException("JAIPILOT_TRUST_STORE does not point to a readable file: " + trustStorePath);
        }

        try (InputStream inputStream = Files.newInputStream(trustStorePath)) {
            KeyStore trustStore = KeyStore.getInstance(trustStoreType);
            trustStore.load(inputStream, password == null ? null : password.toCharArray());
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            return extractTrustManager(trustManagerFactory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read JAIPILOT_TRUST_STORE: " + trustStorePath, exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Failed to load JAIPILOT_TRUST_STORE as " + trustStoreType + ": " + trustStorePath,
                    exception
            );
        }
    }

    private SystemCaLoadResult loadSystemCaTrustManagers(boolean requested) {
        if (!requested) {
            return new SystemCaLoadResult(List.of(), List.of(), "Native system CA merge disabled.");
        }

        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("win")) {
            return loadNativeStores("Windows native root stores", "Windows-ROOT", "Windows-ROOT-LOCALMACHINE");
        }
        if (normalizedOs.contains("mac")) {
            return loadNativeStores(
                    "macOS Keychain root store",
                    "KeychainStore-ROOT"
            );
        }
        return new SystemCaLoadResult(
                List.of(),
                List.of(),
                "Native system CA merge is not supported on " + osName + "."
        );
    }

    private SystemCaLoadResult loadNativeStores(String unavailableMessage, String... storeTypes) {
        List<X509ExtendedTrustManager> trustManagers = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (String storeType : storeTypes) {
            try {
                KeyStore keyStore = KeyStore.getInstance(storeType);
                keyStore.load(null, null);
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);
                trustManagers.add(extractTrustManager(trustManagerFactory));
                sources.add(storeType);
            } catch (Exception exception) {
                failures.add(storeType);
            }
        }

        if (!sources.isEmpty()) {
            return new SystemCaLoadResult(trustManagers, sources, "Loaded native CA stores: " + String.join(", ", sources));
        }
        return new SystemCaLoadResult(List.of(), List.of(), unavailableMessage + " Tried: " + String.join(", ", failures));
    }

    private SSLContext buildSslContext(List<X509ExtendedTrustManager> trustManagers) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            X509ExtendedTrustManager trustManager = trustManagers.size() == 1
                    ? trustManagers.get(0)
                    : new CompositeX509ExtendedTrustManager(trustManagers);
            sslContext.init(null, new TrustManager[] {trustManager}, null);
            return sslContext;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to initialize the JAIPilot TLS context.", exception);
        }
    }

    private X509ExtendedTrustManager extractTrustManager(TrustManagerFactory trustManagerFactory)
            throws KeyStoreException {
        for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
            if (trustManager instanceof X509ExtendedTrustManager x509TrustManager) {
                return x509TrustManager;
            }
        }
        throw new KeyStoreException("No X509ExtendedTrustManager was available.");
    }

    private String resolveTrustStoreType(Path trustStorePath, String configuredType) {
        if (configuredType != null && !configuredType.isBlank()) {
            return configuredType;
        }
        String fileName = trustStorePath.getFileName() == null ? "" : trustStorePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".jks")) {
            return "JKS";
        }
        if (fileName.endsWith(".p12") || fileName.endsWith(".pfx") || fileName.endsWith(".pkcs12")) {
            return "PKCS12";
        }
        return KeyStore.getDefaultType();
    }

    private boolean hasJvmProxyProperties() {
        return firstNonBlank(
                systemProperties.getProperty("https.proxyHost"),
                systemProperties.getProperty("http.proxyHost"),
                systemProperties.getProperty("socksProxyHost")
        ) != null;
    }

    private String environmentValue(String... names) {
        for (String name : names) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Properties copyOf(Properties properties) {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    private record ClientConfiguration(
            SSLContext sslContext,
            ProxySelector proxySelector,
            JaipilotHttpClientDiagnostics diagnostics
    ) {
    }

    private record ProxyConfiguration(
            ProxySelector proxySelector,
            JaipilotHttpClientDiagnostics diagnostics
    ) {
    }

    private record TrustConfiguration(
            List<X509ExtendedTrustManager> trustManagers,
            JaipilotHttpClientDiagnostics.TrustMode trustMode,
            String trustStorePath,
            String trustStoreType,
            boolean systemCaRequested,
            List<String> systemCaSources,
            String systemCaStatus
    ) {

        JaipilotHttpClientDiagnostics diagnostics(JaipilotHttpClientDiagnostics proxyDiagnostics) {
            return new JaipilotHttpClientDiagnostics(
                    trustMode,
                    trustStorePath,
                    trustStoreType,
                    systemCaRequested,
                    new ArrayList<>(new LinkedHashSet<>(systemCaSources)),
                    systemCaStatus,
                    proxyDiagnostics.proxyMode(),
                    proxyDiagnostics.httpsProxy(),
                    proxyDiagnostics.httpProxy(),
                    proxyDiagnostics.noProxy()
            );
        }
    }

    private record SystemCaLoadResult(
            List<X509ExtendedTrustManager> trustManagers,
            List<String> sources,
            String status
    ) {
    }
}
