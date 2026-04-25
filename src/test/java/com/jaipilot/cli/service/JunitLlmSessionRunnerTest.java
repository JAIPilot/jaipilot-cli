package com.jaipilot.cli.service;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunitLlmSessionRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runExecutesPendingBashCommandsAndResubmitsClientLogs() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/CrashController.java",
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = tempDir.resolve("src/test/java/com/example/CrashControllerTest.java");

        StubBackendClient backendClient = new StubBackendClient(
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/CrashControllerTest.java",
                        "",
                        List.of(),
                        List.of("echo hello-from-backend")
                ),
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/CrashControllerTest.java",
                        "package com.example;\n\nclass CrashControllerTest {\n}\n",
                        List.of(),
                        List.of()
                )
        );

        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                new JunitLlmSessionRunner.ConsoleLogger(new PrintWriter(new StringWriter(), true))
        );

        JunitLlmSessionResult result = runner.run(new JunitLlmSessionRequest(
                tempDir,
                cutPath,
                null,
                null,
                "",
                "",
                null
        ));

        assertEquals(2, backendClient.requests.size());
        assertEquals("session-1", result.sessionId());
        assertEquals(outputPath, result.outputPath());
        assertEquals(
                "package com.example;\n\nclass CrashControllerTest {\n}\n",
                Files.readString(outputPath)
        );

        String secondRequestLogs = backendClient.requests.get(1).clientLogs();
        assertTrue(secondRequestLogs.contains("$ echo hello-from-backend"));
        assertTrue(secondRequestLogs.contains("hello-from-backend"));
        assertTrue(secondRequestLogs.contains("[exitCode=0"));
    }

    @Test
    void consoleLoggerAnnouncesColoredDiffOnce() {
        StringWriter output = new StringWriter();
        JunitLlmSessionRunner.ConsoleLogger logger = new JunitLlmSessionRunner.ConsoleLogger(
                new PrintWriter(output, true)
        );

        logger.announceTestFileDiff("class OldTest {\n}\n", "class NewTest {\n}\n");

        String text = output.toString();
        assertTrue(text.contains("Source diff:"));
        assertTrue(text.contains("\u001B[31m"));
        assertTrue(text.contains("- class OldTest {"));
        assertTrue(text.contains("\u001B[32m"));
        assertTrue(text.contains("+ class NewTest {"));
        assertFalse(text.contains("No file changes."));
    }

    private Path write(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static FetchJobResponse doneOutput(
            String sessionId,
            String finalTestFilePath,
            String finalTestFile,
            List<String> requiredContextClassPaths,
            List<String> pendingBashCommands
    ) {
        return new FetchJobResponse(
                "done",
                new FetchJobResponse.FetchJobOutput(
                        sessionId,
                        finalTestFilePath,
                        finalTestFile,
                        requiredContextClassPaths,
                        pendingBashCommands
                ),
                null,
                null
        );
    }

    private static final class StubBackendClient implements JunitLlmBackendClient {

        private final List<InvokeJunitLlmRequest> requests = new ArrayList<>();
        private final List<FetchJobResponse> fetchResponses;

        private StubBackendClient(FetchJobResponse... fetchResponses) {
            this.fetchResponses = List.of(fetchResponses);
        }

        @Override
        public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) {
            requests.add(request);
            String jobId = "job-" + requests.size();
            return new InvokeJunitLlmResponse(jobId, "session-1");
        }

        @Override
        public FetchJobResponse fetchJob(String jobId) {
            int index = Integer.parseInt(jobId.substring("job-".length())) - 1;
            return fetchResponses.get(Math.min(index, fetchResponses.size() - 1));
        }
    }
}
