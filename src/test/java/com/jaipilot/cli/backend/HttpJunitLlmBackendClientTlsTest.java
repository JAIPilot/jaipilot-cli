package com.jaipilot.cli.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.http.JaipilotHttpClientFactory;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.testutil.HttpsTestServer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJunitLlmBackendClientTlsTest {

    @Test
    void invokeReportsFriendlyTlsFailureMessage() throws Exception {
        try (HttpsTestServer server = HttpsTestServer.start(httpsServer -> httpsServer.createContext(
                "/functions/v1/invoke-junit-llm-cli",
                exchange -> HttpsTestServer.writeJson(exchange, "{\"jobId\":\"job-1\",\"sessionId\":\"session-1\"}")
        ))) {
            HttpJunitLlmBackendClient client = new HttpJunitLlmBackendClient(
                    new JaipilotHttpClientFactory(Map.of(), new Properties(), null, "Linux"),
                    new ObjectMapper(),
                    server.baseUrl(),
                    "token-123"
            );

            IOException exception = assertThrows(IOException.class, () -> client.invoke(sampleRequest()));

            assertTrue(exception.getMessage().contains("trusted TLS connection"));
            assertTrue(exception.getMessage().contains("JAIPILOT_TRUST_STORE"));
            assertFalse(exception.getMessage().contains("PKIX"));
        }
    }

    @Test
    void invokeSucceedsWhenCustomTrustStoreIsConfigured() throws Exception {
        try (HttpsTestServer server = HttpsTestServer.start(httpsServer -> httpsServer.createContext(
                "/functions/v1/invoke-junit-llm-cli",
                exchange -> HttpsTestServer.writeJson(exchange, "{\"jobId\":\"job-1\",\"sessionId\":\"session-1\"}")
        ))) {
            HttpJunitLlmBackendClient client = new HttpJunitLlmBackendClient(
                    new JaipilotHttpClientFactory(
                            Map.of(
                                    "JAIPILOT_TRUST_STORE", server.trustStorePath().toString(),
                                    "JAIPILOT_TRUST_STORE_PASSWORD", server.trustStorePassword()
                            ),
                            new Properties(),
                            null,
                            "Linux"
                    ),
                    new ObjectMapper(),
                    server.baseUrl(),
                    "token-123"
            );

            InvokeJunitLlmResponse response = client.invoke(sampleRequest());

            assertEquals("job-1", response.jobId());
            assertEquals("session-1", response.sessionId());
        }
    }

    private InvokeJunitLlmRequest sampleRequest() {
        return new InvokeJunitLlmRequest(
                null,
                "generate",
                "CrashController",
                "CrashControllerTest",
                "5.11.0",
                "class body",
                List.of(),
                "",
                List.of(),
                "",
                null
        );
    }
}
