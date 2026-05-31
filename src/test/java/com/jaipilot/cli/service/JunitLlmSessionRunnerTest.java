package com.jaipilot.cli.service;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                        List.of("echo hello-from-backend")
                ),
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/CrashControllerTest.java",
                        "package com.example;\n\nclass CrashControllerTest {\n}\n",
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
                "src/main/java/com/example/CrashController.java",
                backendClient.requests.get(0).cutName()
        );
        assertEquals(
                tempDir.toAbsolutePath().normalize().toString(),
                backendClient.requests.get(0).localRepositoryPath()
        );
        assertEquals(null, backendClient.requests.get(0).testFilePath());
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
    void runWritesReturnedTestFileBeforePendingBashCommandsExecute() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/VetController.java",
                """
                package com.example;

                public class VetController {
                }
                """
        );
        Path outputPath = tempDir.resolve("src/test/java/com/example/VetControllerTest.java");
        String testSource = "package com.example;\n\nclass VetControllerTest {\n}\n";

        StubBackendClient backendClient = new StubBackendClient(
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/VetControllerTest.java",
                        testSource,
                        List.of("cat src/test/java/com/example/VetControllerTest.java")
                ),
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/VetControllerTest.java",
                        "",
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

        assertEquals(outputPath, result.outputPath());
        assertEquals(testSource, Files.readString(outputPath));
        assertEquals(2, backendClient.requests.size());
        String secondRequestLogs = backendClient.requests.get(1).clientLogs();
        assertTrue(secondRequestLogs.contains("$ cat src/test/java/com/example/VetControllerTest.java"));
        assertTrue(secondRequestLogs.contains("class VetControllerTest {"));
    }

    @Test
    void runAnnouncesCoverageSummaryFromBackend() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/OrderController.java",
                """
                package com.example;

                public class OrderController {
                }
                """
        );
        String testSource = "package com.example;\n\nclass OrderControllerTest {\n}\n";
        String coverageSummary = "Before: 62.5%\nAfter: 71.25%\nDelta: +8.75";
        StringWriter output = new StringWriter();

        StubBackendClient backendClient = new StubBackendClient(
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/OrderControllerTest.java",
                        testSource,
                        List.of(),
                        coverageSummary
                )
        );

        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                new JunitLlmSessionRunner.ConsoleLogger(new PrintWriter(output, true))
        );

        runner.run(new JunitLlmSessionRequest(
                tempDir,
                cutPath,
                null,
                null,
                "",
                "",
                null
        ));

        String logs = output.toString();
        assertFalse(logs.contains("Coverage summary:"));
    }

    @Test
    void runFiltersRawHtmlOutOfCoverageSummaryLogs() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/OrderController.java",
                """
                package com.example;

                public class OrderController {
                }
                """
        );
        String testSource = "package com.example;\n\nclass OrderControllerTest {\n}\n";
        String coverageSummary = """
                Coverage Metrics
                Before: 49.00% (line coverage)
                Before command: grep -A5 "VetController" target/site/jacoco/index.html
                After: 49.00% (line coverage)
                After command: grep -A5 "VetController" target/site/jacoco/index.html
                Delta: 0.00 pp
                Before metrics:
                <?xml version="1.0" encoding="UTF-8"?><html><body>huge html</body></html>
                After metrics:
                <?xml version="1.0" encoding="UTF-8"?><html><body>huge html</body></html>
                """;
        StringWriter output = new StringWriter();

        StubBackendClient backendClient = new StubBackendClient(
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/OrderControllerTest.java",
                        testSource,
                        List.of(),
                        coverageSummary
                )
        );

        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                new JunitLlmSessionRunner.ConsoleLogger(new PrintWriter(output, true))
        );

        runner.run(new JunitLlmSessionRequest(
                tempDir,
                cutPath,
                null,
                null,
                "",
                "",
                null
        ));

        String logs = output.toString();
        assertFalse(logs.contains("Coverage summary:"));
        assertFalse(logs.contains("Before command:"));
        assertFalse(logs.contains("After command:"));
        assertFalse(logs.contains("<?xml version=\"1.0\""));
    }

    @Test
    void runBuildsCoverageSummaryFromStructuredNumbers() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/OrderController.java",
                """
                package com.example;

                public class OrderController {
                }
                """
        );
        String testSource = "package com.example;\n\nclass OrderControllerTest {\n}\n";
        StringWriter output = new StringWriter();

        StubBackendClient backendClient = new StubBackendClient(
                doneOutputWithCoverageSummary(
                        "session-1",
                        "src/test/java/com/example/OrderControllerTest.java",
                        testSource,
                        List.of(),
                        new FetchJobResponse.CoverageSummary(
                                new FetchJobResponse.CoverageSnapshot("cmd-1", 49.0, "line", List.of("49%")),
                                new FetchJobResponse.CoverageSnapshot("cmd-2", 57.25, "line", List.of("57.25%")),
                                8.25,
                                2,
                                null
                        )
                )
        );

        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                new JunitLlmSessionRunner.ConsoleLogger(new PrintWriter(output, true))
        );

        runner.run(new JunitLlmSessionRequest(
                tempDir,
                cutPath,
                null,
                null,
                "",
                "",
                null
        ));

        String logs = output.toString();
        assertFalse(logs.contains("Coverage summary:"));
    }

    @Test
    void runDoesNotRewritePathWhenResponseOmitsFinalTestFile() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/VetController.java",
                """
                package com.example;

                public class VetController {
                }
                """
        );
        Path primaryOutputPath = tempDir.resolve("src/test/java/com/example/VetControllerTest.java");
        Path staleOutputPath = tempDir.resolve("src/test/java/com/example/StaleControllerTest.java");
        String testSource = "package com.example;\n\nclass VetControllerTest {\n}\n";

        StubBackendClient backendClient = new StubBackendClient(
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/VetControllerTest.java",
                        testSource,
                        List.of("echo ready")
                ),
                doneOutput(
                        "session-1",
                        "src/test/java/com/example/StaleControllerTest.java",
                        "",
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

        assertEquals(primaryOutputPath, result.outputPath());
        assertEquals(testSource, Files.readString(primaryOutputPath));
        assertFalse(Files.exists(staleOutputPath));
    }

    @Test
    void runRetriesWhenPolledJobStatusIsError() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/RetryController.java",
                """
                package com.example;

                public class RetryController {
                }
                """
        );
        Path outputPath = tempDir.resolve("src/test/java/com/example/RetryControllerTest.java");
        String testSource = "package com.example;\n\nclass RetryControllerTest {\n}\n";

        StubBackendClient backendClient = new StubBackendClient(
                new FetchJobResponse("error", null, "temporary backend error", null),
                doneOutput(
                        "session-2",
                        "src/test/java/com/example/RetryControllerTest.java",
                        testSource,
                        List.of()
                )
        );

        JunitLlmSessionRunner runner = retryRunner(backendClient, 2, 10);
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
        assertEquals(outputPath, result.outputPath());
        assertEquals(testSource, Files.readString(outputPath));
    }

    @Test
    void runRetriesWhenPollingTimesOut() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/TimeoutController.java",
                """
                package com.example;

                public class TimeoutController {
                }
                """
        );
        String testSource = "package com.example;\n\nclass TimeoutControllerTest {\n}\n";

        StubBackendClient backendClient = new StubBackendClient(
                new FetchJobResponse("processing", null, null, null),
                doneOutput(
                        "session-2",
                        "src/test/java/com/example/TimeoutControllerTest.java",
                        testSource,
                        List.of()
                )
        );

        JunitLlmSessionRunner runner = retryRunner(backendClient, 1, 2);
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
        assertEquals(
                tempDir.resolve("src/test/java/com/example/TimeoutControllerTest.java"),
                result.outputPath()
        );
        assertEquals(testSource, Files.readString(result.outputPath()));
    }

    @Test
    void runRetriesWhenPollingHitsIntermittentNetworkFailure() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/NetworkController.java",
                """
                package com.example;

                public class NetworkController {
                }
                """
        );
        String testSource = "package com.example;\n\nclass NetworkControllerTest {\n}\n";

        StubBackendClient backendClient = new StubBackendClient(
                new IOException("temporary network failure"),
                doneOutput(
                        "session-2",
                        "src/test/java/com/example/NetworkControllerTest.java",
                        testSource,
                        List.of()
                )
        );

        JunitLlmSessionRunner runner = retryRunner(backendClient, 1, 10);
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
        assertEquals(
                tempDir.resolve("src/test/java/com/example/NetworkControllerTest.java"),
                result.outputPath()
        );
        assertEquals(testSource, Files.readString(result.outputPath()));
    }

    @Test
    void runFailsAfterPollingRetryLimitForErrorStatus() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/FailController.java",
                """
                package com.example;

                public class FailController {
                }
                """
        );

        StubBackendClient backendClient = new StubBackendClient(
                new FetchJobResponse("error", null, "still failing", null),
                new FetchJobResponse("error", null, "still failing", null)
        );

        JunitLlmSessionRunner runner = retryRunner(backendClient, 1, 10);
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> runner.run(new JunitLlmSessionRequest(
                        tempDir,
                        cutPath,
                        null,
                        null,
                        "",
                        "",
                        null
                ))
        );
        assertTrue(exception.getMessage().contains("still failing"));
        assertEquals(2, backendClient.requests.size());
    }

    @Test
    void runSurfacesBackendStatusMessageWhenNoFinalTestFileIsReturned() throws Exception {
        Path cutPath = write(
                "src/main/java/com/example/PrecheckController.java",
                """
                package com.example;

                public class PrecheckController {
                }
                """
        );

        String statusMessage = "Mandatory precheck failed: initial full build failed. Please fix build errors and retry test generation.";
        StubBackendClient backendClient = new StubBackendClient(
                new FetchJobResponse(
                        "done",
                        new FetchJobResponse.FetchJobOutput(
                                "session-1",
                                null,
                                "",
                                statusMessage,
                                List.of(),
                                null
                        ),
                        null,
                        null
                )
        );

        JunitLlmSessionRunner runner = new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                new JunitLlmSessionRunner.ConsoleLogger(new PrintWriter(new StringWriter(), true))
        );
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> runner.run(new JunitLlmSessionRequest(
                        tempDir,
                        cutPath,
                        null,
                        null,
                        "",
                        "",
                        null
                ))
        );
        assertEquals(statusMessage, exception.getMessage());
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

    @Test
    void consoleLoggerFormatsLongDurationAsHumanReadableTime() {
        StringWriter output = new StringWriter();
        JunitLlmSessionRunner.ConsoleLogger logger = new JunitLlmSessionRunner.ConsoleLogger(
                new PrintWriter(output, true)
        );

        logger.announceTotalTime(Duration.ofMillis(1_471_500));

        String text = output.toString();
        assertTrue(text.contains("Total time: 24m 32s"));
    }

    @Test
    void consoleLoggerFormatsDurationWithHoursAndDaysWhenNeeded() {
        StringWriter output = new StringWriter();
        JunitLlmSessionRunner.ConsoleLogger logger = new JunitLlmSessionRunner.ConsoleLogger(
                new PrintWriter(output, true)
        );

        logger.announceTotalTime(Duration.ofHours(27).plusMinutes(1).plusSeconds(5));

        String text = output.toString();
        assertTrue(text.contains("Total time: 1d 3h 1m 5s"));
    }

    private JunitLlmSessionRunner retryRunner(
            StubBackendClient backendClient,
            int maxPollRecoveryRetries,
            int maxFetchAttempts
    ) {
        return new JunitLlmSessionRunner(
                backendClient,
                new ProjectFileService(),
                new JunitLlmSessionRunner.ConsoleLogger(new PrintWriter(new StringWriter(), true)),
                new ProcessExecutor(),
                millis -> { },
                maxFetchAttempts,
                0L,
                maxPollRecoveryRetries,
                1L,
                1L,
                0L
        );
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
            List<String> pendingBashCommands
    ) {
        return doneOutput(sessionId, finalTestFilePath, finalTestFile, pendingBashCommands, null);
    }

    private static FetchJobResponse doneOutput(
            String sessionId,
            String finalTestFilePath,
            String finalTestFile,
            List<String> pendingBashCommands,
            String coverageSummaryText
    ) {
        return doneOutputWithCoverageSummary(
                sessionId,
                finalTestFilePath,
                finalTestFile,
                pendingBashCommands,
                coverageSummaryText == null
                        ? null
                        : new FetchJobResponse.CoverageSummary(
                                null,
                                null,
                                null,
                                null,
                                coverageSummaryText
                        )
        );
    }

    private static FetchJobResponse doneOutputWithCoverageSummary(
            String sessionId,
            String finalTestFilePath,
            String finalTestFile,
            List<String> pendingBashCommands,
            FetchJobResponse.CoverageSummary coverageSummary
    ) {
        return new FetchJobResponse(
                "done",
                new FetchJobResponse.FetchJobOutput(
                        sessionId,
                        finalTestFilePath,
                        finalTestFile,
                        null,
                        pendingBashCommands,
                        coverageSummary
                ),
                null,
                null
        );
    }

    private static final class StubBackendClient implements JunitLlmBackendClient {

        private final List<InvokeJunitLlmRequest> requests = new ArrayList<>();
        private final List<Object> fetchResponses;

        private StubBackendClient(Object... fetchResponses) {
            this.fetchResponses = List.of(fetchResponses);
        }

        @Override
        public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) {
            requests.add(request);
            String jobId = "job-" + requests.size();
            return new InvokeJunitLlmResponse(jobId, "session-1");
        }

        @Override
        public FetchJobResponse fetchJob(String jobId) throws Exception {
            int index = Integer.parseInt(jobId.substring("job-".length())) - 1;
            Object response = fetchResponses.get(Math.min(index, fetchResponses.size() - 1));
            if (response instanceof FetchJobResponse fetchJobResponse) {
                return fetchJobResponse;
            }
            if (response instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("Unsupported stub response type: " + response);
        }
    }
}
