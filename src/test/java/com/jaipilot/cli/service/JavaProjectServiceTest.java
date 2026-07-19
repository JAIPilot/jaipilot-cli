package com.jaipilot.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        Path wrapperProperties = projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(wrapperProperties.getParent());
        Files.writeString(wrapperProperties, "distributionUrl=https://repo.maven.apache.org/maven2");
        writeJava(projectRoot.resolve("src/main/java/com/example/OrderService.java"), "OrderService");
        writeJava(projectRoot.resolve("src/main/java/com/example/LegacyService.java"), "LegacyService");
        writeJava(projectRoot.resolve("src/main/java/com/example/RepositoryContract.java"), "RepositoryContract");
        Path packageInfo = projectRoot.resolve("src/main/java/com/example/package-info.java");
        Files.writeString(packageInfo, "package com.example;\n");
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
                    <class name="com/example/RepositoryContract">
                      <counter type="METHOD" missed="0" covered="0"/>
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
    }

    @Test
    void findChangedProductionClassesExcludesPackageInfo() throws Exception {
        Path projectRoot = tempDir.resolve("sample-changed");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Process gitInit = new ProcessBuilder("git", "init", "-q")
                .directory(projectRoot.toFile())
                .start();
        assertEquals(0, gitInit.waitFor());

        writeJava(projectRoot.resolve("src/main/java/com/example/OrderService.java"), "OrderService");
        Path packageInfo = projectRoot.resolve("src/main/java/com/example/package-info.java");
        Files.writeString(packageInfo, "package com.example;\n");

        JavaProjectService service = new JavaProjectService(new ProjectFileService(), new CoverageReportService());

        assertEquals(
                List.of("com.example.OrderService"),
                service.findChangedProductionClasses(projectRoot).stream()
                        .map(JavaProjectService.JavaClassDescriptor::fullyQualifiedName)
                        .toList()
        );
    }

    @Test
    void wrapperIsOptionalWhenWrapperIsMissing() throws Exception {
        Path projectRoot = tempDir.resolve("sample-no-wrapper");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path sourcePath = projectRoot.resolve("src/main/java/com/example/OrderService.java");
        writeJava(sourcePath, "OrderService");

        CoverageReportService coverageReportService = new CoverageReportService();
        JavaProjectService service = new JavaProjectService(new ProjectFileService(), coverageReportService);

        assertTrue(service.resolveBuildWrapper(projectRoot).isEmpty());
    }

    @Test
    void wrapperIsOptionalWhenWrapperMetadataIsMissing() throws Exception {
        Path projectRoot = tempDir.resolve("sample-incomplete-wrapper");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path mvnw = projectRoot.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nexit 0\n");
        mvnw.toFile().setExecutable(true, false);

        CoverageReportService coverageReportService = new CoverageReportService();
        JavaProjectService service = new JavaProjectService(new ProjectFileService(), coverageReportService);

        assertTrue(service.resolveBuildWrapper(projectRoot).isEmpty());
    }

    @Test
    void hasLikelyTestsFindsNonConventionalTestNames() throws Exception {
        Path projectRoot = tempDir.resolve("sample-nonconventional-tests");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path sourcePath = projectRoot.resolve("src/main/java/com/example/OrderService.java");
        writeJava(sourcePath, "OrderService");
        Path testPath = projectRoot.resolve("src/test/java/com/example/OrderServiceCoverageSpec.java");
        Files.createDirectories(testPath.getParent());
        Files.writeString(testPath, """
                package com.example;

                import org.junit.jupiter.api.Test;

                class OrderServiceCoverageSpec {
                    @Test
                    void smoke() {
                        new OrderService();
                    }
                }
                """);

        CoverageReportService coverageReportService = new CoverageReportService();
        JavaProjectService service = new JavaProjectService(new ProjectFileService(), coverageReportService);
        JavaProjectService.JavaClassDescriptor descriptor = service.resolveClass(projectRoot, sourcePath.toString());

        assertTrue(service.hasLikelyTests(descriptor));
        assertEquals(
                List.of("com.example.OrderServiceCoverageSpec"),
                service.findLikelyTests(descriptor).stream()
                        .map(JavaProjectService.JavaTestDescriptor::fullyQualifiedName)
                        .toList()
        );
    }

    @Test
    void likelyTestPresenceChecksManyClassesWithOneModuleScan() throws Exception {
        Path projectRoot = tempDir.resolve("sample-batch-tests");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        Path orderPath = projectRoot.resolve("src/main/java/com/example/OrderService.java");
        Path legacyPath = projectRoot.resolve("src/main/java/com/example/LegacyService.java");
        Path missingPath = projectRoot.resolve("src/main/java/com/example/MissingService.java");
        writeJava(orderPath, "OrderService");
        writeJava(legacyPath, "LegacyService");
        writeJava(missingPath, "MissingService");
        writeJava(projectRoot.resolve("src/test/java/com/example/OrderServiceTest.java"), "OrderServiceTest");
        Path legacyTest = projectRoot.resolve("src/test/java/com/example/LegacyCoverageSpec.java");
        Files.createDirectories(legacyTest.getParent());
        Files.writeString(legacyTest, """
                package com.example;

                class LegacyCoverageSpec {
                    void mentionsCut() {
                        new LegacyService();
                    }
                }
                """);

        JavaProjectService service = new JavaProjectService(new ProjectFileService(), new CoverageReportService());
        JavaProjectService.JavaClassDescriptor order = service.resolveClass(projectRoot, orderPath.toString());
        JavaProjectService.JavaClassDescriptor legacy = service.resolveClass(projectRoot, legacyPath.toString());
        JavaProjectService.JavaClassDescriptor missing = service.resolveClass(projectRoot, missingPath.toString());

        Map<JavaProjectService.JavaClassDescriptor, Boolean> testPresence = service.likelyTestPresence(
                List.of(order, legacy, missing)
        );

        assertTrue(testPresence.get(order));
        assertTrue(testPresence.get(legacy));
        assertFalse(testPresence.get(missing));
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
