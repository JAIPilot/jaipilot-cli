package com.jaipilot.cli.backend;

import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.testutil.HttpsTestServer;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJunitLlmBackendClientTlsTest {

    @Test
    void invokeReportsCurlTlsFailureWithoutJvmTrustStoreMessage() throws Exception {
        try (HttpsTestServer server = HttpsTestServer.start(httpsServer -> httpsServer.createContext(
                "/functions/v1/invoke-junit-llm-cli",
                exchange -> HttpsTestServer.writeJson(exchange, "{\"jobId\":\"job-1\",\"sessionId\":\"session-1\"}")
        ))) {
            HttpJunitLlmBackendClient client = new HttpJunitLlmBackendClient(server.baseUrl(), "token-123");

            IOException exception = assertThrows(IOException.class, () -> client.invoke(sampleRequest()));

            assertTrue(exception.getMessage().contains("via curl"));
            assertFalse(exception.getMessage().contains("default JVM/OS trust store"));
            assertFalse(exception.getMessage().contains("PKIX"));
        }
    }

    private InvokeJunitLlmRequest sampleRequest() {
        return new InvokeJunitLlmRequest(
                null,
                "CrashController",
                "src/test/java/com/example/CrashControllerTest.java",
                "5.11.0",
                "class body",
                "",
                "",
                null
        );
    }
}
