package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaProjectServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void findClassesBelowCoverageReturnsOnlyClassesUnderThreshold() throws Exception {
        Path projectRoot = tempDir.resolve("sample");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jacoco</groupId>
                        <artifactId>jacoco-maven-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        Path mvnw = projectRoot.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nexit 0\n");
        mvnw.toFile().setExecutable(true, false);
        writeJava(projectRoot.resolve("src/main/java/com/example/OrderService.java"), "OrderService");
        writeJava(projectRoot.resolve("src/main/java/com/example/LegacyService.java"), "LegacyService");
        writeJava(projectRoot.resolve("src/test/java/com/example/OrderServiceTest.java"), "OrderServiceTest");

        Path reportPath = projectRoot.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, """
                <report name="sample">
                  <package name="com/example">
                    <class name="com/example/OrderService">
                      <counter type="LINE" missed="1" covered="9"/>
                      <counter type="BRANCH" missed="1" covered="3"/>
                    </class>
                    <class name="com/example/LegacyService">
                      <counter type="LINE" missed="7" covered="3"/>
                      <counter type="BRANCH" missed="4" covered="0"/>
                    </class>
                  </package>
                  <counter type="LINE" missed="8" covered="12"/>
                  <counter type="BRANCH" missed="5" covered="3"/>
                </report>
                """);

        CoverageReportService coverageReportService = new CoverageReportService();
        JavaProjectService service = new JavaProjectService(new ProjectFileService(), coverageReportService);

        List<JavaProjectService.JavaClassDescriptor> belowThreshold = service.findClassesBelowCoverage(projectRoot, 80.0d);

        assertEquals(1, belowThreshold.size());
        assertEquals("com.example.LegacyService", belowThreshold.get(0).fullyQualifiedName());
        assertTrue(service.supportsCoverage(projectRoot));
        assertEquals("./mvnw", service.resolveBuildWrapper(projectRoot).orElseThrow());
        assertEquals(
                List.of("./mvnw", "test", "jacoco:report"),
                service.buildProjectCoverageCommand(projectRoot).orElseThrow()
        );
    }

    @Test
    void buildCommandsAreOptionalWhenWrapperIsMissing() throws Exception {
        Path projectRoot = tempDir.resolve("sample-no-wrapper");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path sourcePath = projectRoot.resolve("src/main/java/com/example/OrderService.java");
        writeJava(sourcePath, "OrderService");

        CoverageReportService coverageReportService = new CoverageReportService();
        JavaProjectService service = new JavaProjectService(new ProjectFileService(), coverageReportService);
        JavaProjectService.JavaClassDescriptor descriptor = service.resolveClass(projectRoot, sourcePath.toString());

        assertTrue(service.resolveBuildWrapper(projectRoot).isEmpty());
        assertTrue(service.buildValidationCommand(descriptor).isEmpty());
        assertTrue(service.buildProjectCoverageCommand(projectRoot).isEmpty());
    }

    private void writeJava(Path path, String className) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                package com.example;

                class %s {
                }
                """.formatted(className));
    }
}
