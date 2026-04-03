package com.jaipilot.cli.http;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

public final class JaipilotNetworkErrors {

    private JaipilotNetworkErrors() {
    }

    public static IOException wrapIo(
            String action,
            URI uri,
            IOException exception
    ) {
        return new IOException(describe(action, uri, exception), exception);
    }

    public static IllegalStateException wrapRuntime(
            String action,
            URI uri,
            Exception exception
    ) {
        return new IllegalStateException(describe(action, uri, exception), exception);
    }

    public static String describe(
            String action,
            URI uri,
            Throwable failure
    ) {
        String host = uri == null || uri.getHost() == null ? "the configured endpoint" : uri.getHost();
        CurlHttpClient.CurlException curlException = findCause(failure, CurlHttpClient.CurlException.class);
        if (curlException != null) {
            return switch (curlException.kind()) {
                case MISSING_CURL -> "JAIPilot requires `curl` on PATH for network calls. Install curl and retry.";
                case TIMEOUT -> "JAIPilot timed out while trying to %s against %s via curl."
                        .formatted(action, host);
                case EXECUTION -> "JAIPilot could not reach %s while trying to %s via curl. %s"
                        .formatted(host, action, describeCurlFailure(curlException));
                case INVALID_RESPONSE -> "JAIPilot received an invalid HTTP response from curl while trying to %s against %s."
                        .formatted(action, host);
            };
        }

        return "JAIPilot hit a network error while trying to %s against %s via curl. %s"
                .formatted(action, host, plainMessage(failure));
    }

    private static String describeCurlFailure(CurlHttpClient.CurlException exception) {
        StringBuilder builder = new StringBuilder();
        if (exception.exitCode() != null) {
            builder.append("curl exit code ").append(exception.exitCode()).append('.');
        }
        String stderr = exception.stderr();
        if (stderr != null && !stderr.isBlank()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(stderr.trim());
        }
        if (builder.length() == 0) {
            builder.append(Objects.toString(exception.getMessage(), "curl request failed."));
        }
        return builder.toString();
    }

    private static String plainMessage(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return "Retry with --verbose for additional details.";
        }
        return failure.getMessage();
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
