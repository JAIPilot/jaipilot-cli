package com.jaipilot.cli.service;

import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.JunitLlmOperation;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import com.jaipilot.cli.process.ExecutionResult;
import com.jaipilot.cli.process.MavenCommandBuilder;
import com.jaipilot.cli.process.ProcessExecutor;
import com.jaipilot.cli.util.SensitiveDataRedactor;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class JunitLlmWorkflowRunner {

    private static final PrintWriter QUIET_WRITER = new PrintWriter(Writer.nullWriter());

    private final JunitLlmSessionRunner sessionRunner;
    private final MavenCommandBuilder commandBuilder;
    private final ProcessExecutor processExecutor;
    private final ProjectFileService fileService;

    public JunitLlmWorkflowRunner(
            JunitLlmSessionRunner sessionRunner,
            MavenCommandBuilder commandBuilder,
            ProcessExecutor processExecutor,
            ProjectFileService fileService
    ) {
        this.sessionRunner = sessionRunner;
        this.commandBuilder = commandBuilder;
        this.processExecutor = processExecutor;
        this.fileService = fileService;
    }

    public JunitLlmSessionResult run(
            JunitLlmSessionRequest initialRequest,
            Path mavenExecutable,
            List<String> additionalMavenArgs,
            Duration timeout,
            int maxFixAttempts
    ) throws Exception {
        if (initialRequest.operation() == JunitLlmOperation.FIX) {
            return runFixWorkflow(initialRequest, mavenExecutable, additionalMavenArgs, timeout, maxFixAttempts);
        }
        return runGenerateWorkflow(initialRequest, mavenExecutable, additionalMavenArgs, timeout, maxFixAttempts);
    }

    private JunitLlmSessionResult runGenerateWorkflow(
            JunitLlmSessionRequest initialRequest,
            Path mavenExecutable,
            List<String> additionalMavenArgs,
            Duration timeout,
            int maxFixAttempts
    ) throws Exception {
        JunitLlmSessionResult currentResult = sessionRunner.run(initialRequest);
        return continueFixLoop(initialRequest, currentResult, mavenExecutable, additionalMavenArgs, timeout, maxFixAttempts, 0);
    }

    private JunitLlmSessionResult runFixWorkflow(
            JunitLlmSessionRequest initialRequest,
            Path mavenExecutable,
            List<String> additionalMavenArgs,
            Duration timeout,
            int maxFixAttempts
    ) throws Exception {
        ValidationFailure initialFailure = validateLocalBuild(
                initialRequest.projectRoot(),
                initialRequest.outputPath(),
                mavenExecutable,
                additionalMavenArgs,
                timeout
        );
        if (initialFailure == null) {
            return new JunitLlmSessionResult(null, initialRequest.outputPath(), List.of());
        }

        JunitLlmSessionRequest fixRequest = new JunitLlmSessionRequest(
                initialRequest.projectRoot(),
                initialRequest.cutPath(),
                initialRequest.outputPath(),
                JunitLlmOperation.FIX,
                initialRequest.sessionId(),
                initialRequest.initialTestClassCode(),
                "",
                formatClientLogs(initialFailure)
        );

        JunitLlmSessionResult currentResult = sessionRunner.run(fixRequest);
        return continueFixLoop(initialRequest, currentResult, mavenExecutable, additionalMavenArgs, timeout, maxFixAttempts, 1);
    }

    private JunitLlmSessionResult continueFixLoop(
            JunitLlmSessionRequest initialRequest,
            JunitLlmSessionResult currentResult,
            Path mavenExecutable,
            List<String> additionalMavenArgs,
            Duration timeout,
            int maxFixAttempts,
            int fixAttemptsUsed
    ) throws Exception {
        String currentSessionId = currentResult.sessionId();

        for (int fixAttempt = fixAttemptsUsed; ; fixAttempt++) {
            ValidationFailure validationFailure = validateLocalBuild(
                    initialRequest.projectRoot(),
                    currentResult.outputPath(),
                    mavenExecutable,
                    additionalMavenArgs,
                    timeout
            );
            if (validationFailure == null) {
                return currentResult;
            }

            if (fixAttempt >= maxFixAttempts) {
                throw new IllegalStateException(
                        "Generated test did not pass after " + maxFixAttempts
                                + " fix attempts. Last failed phase: " + validationFailure.phase() + "."
                );
            }

            String currentTestCode = fileService.readFile(currentResult.outputPath());
            JunitLlmSessionRequest fixRequest = new JunitLlmSessionRequest(
                    initialRequest.projectRoot(),
                    initialRequest.cutPath(),
                    currentResult.outputPath(),
                    JunitLlmOperation.FIX,
                    currentSessionId,
                    currentTestCode,
                    "",
                    formatClientLogs(validationFailure)
            );

            currentResult = sessionRunner.run(fixRequest);
            currentSessionId = currentResult.sessionId();
        }
    }

    private ValidationFailure validateLocalBuild(
            Path projectRoot,
            Path outputPath,
            Path mavenExecutable,
            List<String> additionalMavenArgs,
            Duration timeout
    ) throws Exception {
        String testSelector = fileService.deriveTestSelector(outputPath);

        ExecutionResult compileResult = processExecutor.execute(
                commandBuilder.buildTestCompile(projectRoot, mavenExecutable, additionalMavenArgs),
                projectRoot,
                timeout,
                false,
                QUIET_WRITER
        );
        if (!isSuccessful(compileResult)) {
            return new ValidationFailure("test-compile", compileResult);
        }

        ExecutionResult testResult = processExecutor.execute(
                commandBuilder.buildSingleTestExecution(projectRoot, mavenExecutable, additionalMavenArgs, testSelector),
                projectRoot,
                timeout,
                false,
                QUIET_WRITER
        );
        if (!isSuccessful(testResult)) {
            return new ValidationFailure("test", testResult);
        }

        return null;
    }

    private boolean isSuccessful(ExecutionResult result) {
        return !result.timedOut() && result.exitCode() == 0;
    }

    private String formatClientLogs(ValidationFailure failure) {
        StringBuilder builder = new StringBuilder();
        builder.append("Phase: ").append(failure.phase()).append(System.lineSeparator());
        builder.append("Command: ")
                .append(SensitiveDataRedactor.redactCommand(failure.result().command()))
                .append(System.lineSeparator());
        builder.append("Exit code: ").append(failure.result().exitCode()).append(System.lineSeparator());
        builder.append("Timed out: ").append(failure.result().timedOut()).append(System.lineSeparator());
        builder.append("Output:").append(System.lineSeparator());
        builder.append(SensitiveDataRedactor.redact(failure.result().output()));
        return builder.toString();
    }

    private record ValidationFailure(String phase, ExecutionResult result) {
    }
}
