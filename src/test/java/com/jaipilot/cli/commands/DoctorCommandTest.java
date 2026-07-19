package com.jaipilot.cli.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaipilot.cli.service.CoverageReportService;
import com.jaipilot.cli.service.JavaProjectService;
import com.jaipilot.cli.service.ProcessExecutor;
import com.jaipilot.cli.service.ProjectFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsUsableWrapperAsReady() throws Exception {
        Path projectRoot = createMavenProject("ready-wrapper", 0);
        DoctorCommand command = command();

        DoctorCommand.BuildCommandStatus status = command.checkBuildCommand(
                projectRoot,
                Optional.of(JavaProjectService.BuildTool.MAVEN)
        );

        assertTrue(status.available());
        assertTrue(status.details().contains("./mvnw"));
        assertTrue(status.details().contains("Apache Maven fixture"));
    }

    @Test
    void reportsBrokenWrapperAsMissing() throws Exception {
        Path projectRoot = createMavenProject("broken-wrapper", 4);
        DoctorCommand command = command();

        DoctorCommand.BuildCommandStatus status = command.checkBuildCommand(
                projectRoot,
                Optional.of(JavaProjectService.BuildTool.MAVEN)
        );

        assertFalse(status.available());
        assertTrue(status.details().contains("exited 4"));
    }

    @Test
    void reportsMissingBuildDescriptorClearly() {
        DoctorCommand.BuildCommandStatus status = command().checkBuildCommand(tempDir, Optional.empty());

        assertFalse(status.available());
        assertTrue(status.details().contains("no Maven or Gradle build file"));
    }

    @Test
    void reportsMalformedCoverageXmlAsInvalid() throws Exception {
        Path report = tempDir.resolve("malformed/target/site/jacoco/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "<report>");

        DoctorCommand.CoverageReportStatus status = command().inspectCoverageReport(tempDir.resolve("malformed"));

        assertTrue(status.state() == DoctorCommand.CoverageReportState.INVALID);
        assertTrue(status.details().contains("Failed to parse JaCoCo report"));
    }

    @Test
    void reportsAmbiguousModuleCoverageAsInvalid() throws Exception {
        Path projectRoot = tempDir.resolve("ambiguous");
        Path first = projectRoot.resolve("one/target/site/jacoco/jacoco.xml");
        Path second = projectRoot.resolve("two/target/site/jacoco/jacoco.xml");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, "<report/>");
        Files.writeString(second, "<report/>");

        DoctorCommand.CoverageReportStatus status = command().inspectCoverageReport(projectRoot);

        assertTrue(status.state() == DoctorCommand.CoverageReportState.INVALID);
        assertTrue(status.details().contains("Multiple JaCoCo XML reports"));
    }

    private Path createMavenProject(String name, int exitCode) throws Exception {
        Path projectRoot = tempDir.resolve(name);
        Files.createDirectories(projectRoot.resolve(".mvn/wrapper"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n");
        Files.writeString(
                projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://repo.maven.apache.org/maven2\n"
        );
        Path wrapper = projectRoot.resolve("mvnw");
        Files.writeString(wrapper, """
                #!/bin/sh
                printf 'Apache Maven fixture\n'
                exit %d
                """.formatted(exitCode));
        assertTrue(wrapper.toFile().setExecutable(true, false));
        return projectRoot;
    }

    private DoctorCommand command() {
        return new DoctorCommand(new ProjectFileService(), new CoverageReportService(), new ProcessExecutor());
    }
}
