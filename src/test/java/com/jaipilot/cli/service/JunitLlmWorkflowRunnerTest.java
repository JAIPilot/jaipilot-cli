package com.jaipilot.cli.service;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmOperation;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import java.io.PrintWriter;
import java.io.StringWriter;
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

class JunitLlmWorkflowRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runBuildsExecutesAndFixesUntilTargetTestPasses() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path cutPath = write(
                projectRoot.resolve("src/main/java/com/example/CrashController.java"),
                """
                package com.example;

                public class CrashController {
                }
                """
        );
        Path outputPath = projectRoot.resolve("src/test/java/com/example/CrashControllerTest.java");
        Path fakeMaven = writeFakeMaven(projectRoot);

        StubBackendClient backendClient = new StubBackendClient();
        ProjectFileService fileService = new ProjectFileService();
        JunitLlmSessionRunner sessionRunner = new JunitLlmSessionRunner(
                backendClient,
                fileService,
                new UsedContextClassPathCache(tempDir.resolve("used-context-cache.json")),
                new JunitLlmConsoleLogger(new PrintWriter(new StringWriter()))
        );
        JunitLlmWorkflowRunner workflowRunner = new JunitLlmWorkflowRunner(
                sessionRunner,
                new MavenCommandBuilder(),
                new ProcessExecutor(),
                fileService
        );

        JunitLlmSessionResult result = workflowRunner.run(new JunitLlmSessionRequest(
                projectRoot,
                cutPath,
                outputPath,
                JunitLlmOperation.GENERATE,
                null,
                "",
                "",
                null
        ), fakeMaven, List.of("-Drepo.password=hunter2"), Duration.ofSeconds(10), 5);

        assertEquals("session-1", result.sessionId());
        assertTrue(Files.readString(outputPath).contains("PASS"));
        assertEquals(3, backendClient.requests.size());
        InvokeJunitLlmRequest firstFixRequest = backendClient.requests.get(1);
        InvokeJunitLlmRequest secondFixRequest = backendClient.requests.get(2);
        assertEquals("generate", backendClient.requests.get(0).type());
        assertEquals("fix", firstFixRequest.type());
        assertEquals("fix", secondFixRequest.type());
        assertEquals("", firstFixRequest.cutName());
        assertEquals("", firstFixRequest.cutCode());
        assertTrue(firstFixRequest.clientLogs().contains("Phase: test-compile"));
        assertTrue(firstFixRequest.initialTestClassCode().contains("BUILD_FAIL"));
        assertEquals("", firstFixRequest.newTestClassCode());
        assertTrue(firstFixRequest.clientLogs().contains("-Drepo.password=[REDACTED]"));
        assertTrue(firstFixRequest.clientLogs().contains("Authorization: Bearer [REDACTED]"));
        assertTrue(firstFixRequest.clientLogs().contains("refresh_token=[REDACTED]"));
        assertTrue(firstFixRequest.clientLogs().contains("JAIPILOT_JWT_TOKEN=[REDACTED]"));
        assertFalse(firstFixRequest.clientLogs().contains("hunter2"));
        assertFalse(firstFixRequest.clientLogs().contains("compile-secret-token"));
        assertEquals("", secondFixRequest.cutName());
        assertEquals("", secondFixRequest.cutCode());
        assertTrue(secondFixRequest.clientLogs().contains("Phase: test"));
        assertTrue(secondFixRequest.initialTestClassCode().contains("TEST_FAIL"));
        assertEquals("", secondFixRequest.newTestClassCode());
        assertTrue(secondFixRequest.clientLogs().contains("Authorization: Bearer [REDACTED]"));
        assertTrue(secondFixRequest.clientLogs().contains("refresh_token=[REDACTED]"));
        assertTrue(secondFixRequest.clientLogs().contains("JAIPILOT_JWT_TOKEN=[REDACTED]"));

        List<String> commandLog = Files.readAllLines(projectRoot.resolve("maven-commands.log"));
        assertEquals(5, commandLog.size());
        assertTrue(commandLog.get(0).contains("test-compile"));
        assertTrue(commandLog.get(1).contains("test-compile"));
        assertTrue(commandLog.get(2).contains("-Dtest=com.example.CrashControllerTest"));
        assertTrue(commandLog.get(3).contains("test-compile"));
        assertTrue(commandLog.get(4).contains("-Dtest=com.example.CrashControllerTest"));
    }

    private Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private Path writeFakeMaven(Path projectRoot) throws Exception {
        Path fakeMaven = projectRoot.resolve("fake-mvn");
        Files.createDirectories(projectRoot);
        Files.writeString(fakeMaven, """
                #!/usr/bin/env sh
                set -eu

                TEST_FILE="$PWD/src/test/java/com/example/CrashControllerTest.java"
                printf '%s\\n' "$*" >> "$PWD/maven-commands.log"

                case "$*" in
                  *test-compile*)
                    if grep -q BUILD_FAIL "$TEST_FILE"; then
                      echo "compile failed: missing symbol"
                      echo "Authorization: Bearer compile-secret-token"
                      echo "refresh_token=refresh-compile-token"
                      echo "JAIPILOT_JWT_TOKEN=jwt-compile-token"
                      exit 1
                    fi
                    echo "compile ok"
                    exit 0
                    ;;
                  *-Dtest=com.example.CrashControllerTest*test)
                    if grep -q TEST_FAIL "$TEST_FILE"; then
                      echo "Tests run: 1, Failures: 1"
                      echo "Authorization: Bearer test-secret-token"
                      echo "refresh_token=refresh-test-token"
                      echo "JAIPILOT_JWT_TOKEN=jwt-test-token"
                      exit 1
                    fi
                    echo "Tests run: 1, Failures: 0"
                    exit 0
                    ;;
                  *)
                    echo "unexpected command: $*"
                    exit 2
                    ;;
                esac
                """);
        boolean executable = fakeMaven.toFile().setExecutable(true);
        assertTrue(executable, "fake Maven script must be executable");
        return fakeMaven;
    }

    private static final class StubBackendClient implements JunitLlmBackendClient {

        private final List<InvokeJunitLlmRequest> requests = new ArrayList<>();

        @Override
        public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) {
            requests.add(request);
            return new InvokeJunitLlmResponse("job-" + requests.size(), "session-1");
        }

        @Override
        public FetchJobResponse fetchJob(String jobId) {
            return switch (jobId) {
                case "job-1" -> doneResponse("""
                        package com.example;

                        class CrashControllerTest {
                            // BUILD_FAIL
                        }
                        """);
                case "job-2" -> doneResponse("""
                        package com.example;

                        class CrashControllerTest {
                            // TEST_FAIL
                        }
                        """);
                case "job-3" -> doneResponse("""
                        package com.example;

                        class CrashControllerTest {
                            // PASS
                        }
                        """);
                default -> throw new IllegalStateException("Unexpected job id " + jobId);
            };
        }

        private FetchJobResponse doneResponse(String finalTestFile) {
            return new FetchJobResponse(
                    "done",
                    new FetchJobResponse.FetchJobOutput(
                            "session-1",
                            finalTestFile,
                            List.of(),
                            List.of()
                    ),
                    null,
                    null
            );
        }
    }
}
