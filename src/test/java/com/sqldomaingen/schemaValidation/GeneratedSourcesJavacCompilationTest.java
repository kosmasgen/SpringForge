package com.sqldomaingen.schemaValidation;

import com.sqldomaingen.util.GeneratedProjectPathResolver;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates that the already generated main and test Java source files compile with javac.
 * Produces separate success and failure log messages for main and test sources.
 */
class GeneratedSourcesJavacCompilationTest {

    private static final Logger log = LoggerFactory.getLogger(GeneratedSourcesJavacCompilationTest.class);

    /**
     * Compiles generated main and test sources and reports results separately.
     *
     * @throws Exception if file traversal or process execution fails
     */
    @Test
    void shouldCompileGeneratedSourcesWithSeparateResults() throws Exception {
        List<String> violations = new ArrayList<>();

        Path projectRoot = resolveGeneratedProjectRoot();
        Path mainJavaRoot = projectRoot.resolve("src/main/java");
        Path testJavaRoot = projectRoot.resolve("src/test/java");

        if (!Files.exists(mainJavaRoot)) {
            fail("Missing generated main Java root: " + mainJavaRoot.toAbsolutePath());
        }

        List<String> mainFiles = collectJavaFiles(mainJavaRoot);
        List<String> testFiles = collectJavaFiles(testJavaRoot);

        if (mainFiles.isEmpty() && testFiles.isEmpty()) {
            fail("No generated Java sources found.");
        }

        Path mainOutputDirectory = Files.createTempDirectory("javac-main");
        Path testOutputDirectory = Files.createTempDirectory("javac-test");

        String classpath = buildClasspath(projectRoot);

        CompilationResult mainResult = compileSourceSet(
                mainFiles,
                mainOutputDirectory,
                classpath,
                "MAIN"
        );

        logCompilationResult(mainResult);

        if (!mainResult.success()) {
            violations.add("[MAIN] Compilation failed.");
            violations.add(mainResult.output());
        }

        String testCompilationClasspath =
                mainOutputDirectory.toAbsolutePath()
                        + java.io.File.pathSeparator
                        + classpath;

        CompilationResult testResult = compileSourceSet(
                testFiles,
                testOutputDirectory,
                testCompilationClasspath,
                "TEST"
        );

        logCompilationResult(testResult);

        if (!testResult.success()) {
            violations.add("[TEST] Compilation failed.");
            violations.add(testResult.output());
        }

        if (!violations.isEmpty()) {
            fail(String.join(System.lineSeparator(), violations));
        }
    }

    /**
     * Resolves the generated project root directory.
     *
     * @return generated project root
     */
    private Path resolveGeneratedProjectRoot() {
        return GeneratedProjectPathResolver.resolveGeneratedProjectRoot();
    }

    /**
     * Collects Java files from the given root directory.
     *
     * @param root Java source root
     * @return collected Java file paths
     * @throws Exception if file traversal fails
     */
    private List<String> collectJavaFiles(Path root) throws Exception {
        List<String> files = new ArrayList<>();

        if (!Files.exists(root)) {
            return files;
        }

        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> files.add(path.toAbsolutePath().toString()));
        }

        return files;
    }

    /**
     * Compiles the given source set with javac.
     *
     * @param files Java files to compile
     * @param outputDirectory compilation output directory
     * @param classpath classpath to use
     * @param label source set label
     * @return compilation result
     * @throws Exception if process execution fails
     */
    private CompilationResult compileSourceSet(
            List<String> files,
            Path outputDirectory,
            String classpath,
            String label
    ) throws Exception {
        if (files.isEmpty()) {
            return new CompilationResult(label, true, "No files found for compilation.");
        }

        Path argsFile = createArgsFile(outputDirectory, files, classpath);

        Process process = new ProcessBuilder("javac", "@" + argsFile.toAbsolutePath())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        return new CompilationResult(label, exitCode == 0, output);
    }

    /**
     * Logs the compilation result for a source set.
     *
     * @param result compilation result
     */
    private void logCompilationResult(CompilationResult result) {
        if (result.success()) {
            log.info("[{}] Compilation PASSED.", result.label());
            return;
        }

        log.error("[{}] Compilation FAILED.", result.label());
        log.error("[{}] Compiler output:\n{}", result.label(), result.output());
    }

    /**
     * Creates a javac argument file.
     *
     * @param outputDirectory compilation output directory
     * @param files Java files to compile
     * @param classpath classpath to use
     * @return path to args file
     * @throws Exception if args file creation fails
     */
    private Path createArgsFile(
            Path outputDirectory,
            List<String> files,
            String classpath
    ) throws Exception {
        Path argsFile = Files.createTempFile("javac-args", ".txt");

        List<String> lines = new ArrayList<>();
        lines.add("-cp");
        lines.add(quote(classpath));
        lines.add("-processorpath");
        lines.add(quote(classpath));
        lines.add("-d");
        lines.add(quote(outputDirectory.toAbsolutePath().toString()));

        for (String file : files) {
            lines.add(quote(file));
        }

        Files.write(argsFile, lines, StandardCharsets.UTF_8);

        return argsFile;
    }

    /**
     * Builds the generated project classpath using Maven wrapper.
     *
     * @param projectRoot generated project root
     * @return resolved classpath
     * @throws Exception if Maven wrapper execution fails
     */
    private String buildClasspath(Path projectRoot) throws Exception {
        Path wrapperCommand = projectRoot.resolve("mvnw.cmd");
        Path outputFile = Files.createTempFile("mvn-classpath", ".txt");

        if (!Files.exists(wrapperCommand)) {
            throw new IllegalStateException("Missing Maven wrapper command: " + wrapperCommand.toAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add(wrapperCommand.toAbsolutePath().toString());
        command.add("dependency:build-classpath");
        command.add("-Dmdep.outputFile=" + outputFile.toAbsolutePath());

        command.add("-Dmdep.includeScope=test");

        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Failed to build classpath via Maven wrapper." + System.lineSeparator() + output);
        }

        return Files.readString(outputFile).trim();
    }

    /**
     * Quotes a javac argument value.
     *
     * @param value raw value
     * @return quoted value
     */
    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\") + "\"";
    }

    /**
     * Holds the result of a javac compilation run.
     *
     * @param label source set label
     * @param success compilation success flag
     * @param output compiler output
     */
    private record CompilationResult(String label, boolean success, String output) {
    }
}