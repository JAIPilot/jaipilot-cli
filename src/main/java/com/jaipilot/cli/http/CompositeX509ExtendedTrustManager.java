package com.jaipilot.cli.http;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

final class CompositeX509ExtendedTrustManager extends X509ExtendedTrustManager {

    private final List<X509ExtendedTrustManager> delegates;

    CompositeX509ExtendedTrustManager(List<X509ExtendedTrustManager> delegates) {
        this.delegates = List.copyOf(Objects.requireNonNull(delegates, "delegates"));
        if (this.delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one trust manager is required.");
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        runCheck(trustManager -> trustManager.checkClientTrusted(chain, authType));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        runCheck(trustManager -> trustManager.checkServerTrusted(chain, authType));
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        List<X509Certificate> acceptedIssuers = new ArrayList<>();
        for (X509ExtendedTrustManager delegate : delegates) {
            acceptedIssuers.addAll(List.of(delegate.getAcceptedIssuers()));
        }
        return acceptedIssuers.toArray(X509Certificate[]::new);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        runCheck(trustManager -> trustManager.checkClientTrusted(chain, authType, socket));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        runCheck(trustManager -> trustManager.checkServerTrusted(chain, authType, socket));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        runCheck(trustManager -> trustManager.checkClientTrusted(chain, authType, engine));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        runCheck(trustManager -> trustManager.checkServerTrusted(chain, authType, engine));
    }

    private void runCheck(TrustCheck trustCheck) throws CertificateException {
        CertificateException lastFailure = null;
        for (X509ExtendedTrustManager delegate : delegates) {
            try {
                trustCheck.run(delegate);
                return;
            } catch (CertificateException exception) {
                lastFailure = exception;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new CertificateException("No trust manager accepted the certificate chain.");
    }

    @FunctionalInterface
    private interface TrustCheck {

        void run(X509ExtendedTrustManager trustManager) throws CertificateException;
    }
}
