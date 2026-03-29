package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
final class XmlValidationApplicationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsZeroWhenAllFilesAreValid() throws Exception {
        writeRelaxNgSchema("schema.rng");
        Path valid = writeXml("a-valid.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);

        ValidationArguments arguments = ValidationArguments.fromCli(
                null,
                null,
                null,
                List.of(valid),
                List.of(),
                1,
                false);
        XmlValidationApplication application = createApplication(Map.of());

        int exitCode = invokeRun(application, arguments, arguments.resolveFiles());

        assertEquals(0, exitCode);
    }

    @Test
    void failFastStopsAfterFirstFailureWithSingleWorker() throws Exception {
        writeRelaxNgSchema("schema.rng");
        Path invalid = writeXml("a-invalid.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <wrong/>
                """);
        Path validOne = writeXml("b-valid.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);
        Path validTwo = writeXml("c-valid.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);

        ValidationArguments arguments = ValidationArguments.fromCli(
                null,
                null,
                null,
                List.of(invalid, validOne, validTwo),
                List.of(),
                1,
                true);
        XmlValidationApplication application = createApplication(Map.of());
        List<Path> files = arguments.resolveFiles();

        List<ValidationResult> results = invokeValidateFiles(application, arguments, files, 1);

        assertEquals(1, results.size());
        assertFalse(results.get(0).ok());
        assertEquals(invalid.toAbsolutePath().normalize(), results.get(0).file());
    }

    @Test
    void reportsConfiguredWorkerCount() throws Exception {
        writeRelaxNgSchema("schema.rng");
        Path valid = writeXml("single.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);

        ValidationArguments arguments = ValidationArguments.fromCli(
                null,
                null,
                null,
                List.of(valid),
                List.of(),
                2,
                false);
        XmlValidationApplication application = createApplication(Map.of());
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;

        try {
            System.setErr(new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
            invokeRun(application, arguments, arguments.resolveFiles());
        } finally {
            System.setErr(originalErr);
        }

        String stderr = stderrBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("Validating 1 file(s) with 2 worker(s)"));
    }

    @Test
    void executeReturnsTwoForInvalidFlag() throws Exception {
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        int exitCode = XmlValidationApplication.execute(
                new String[] { "--definitely-invalid" },
                new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        String stderr = stderrBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("Unknown option"));
        assertTrue(stdoutBuffer.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void executePrintsVersionForVersionFlag() throws Exception {
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        int exitCode = XmlValidationApplication.execute(
                new String[] { "--version" },
                new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));

        assertEquals(0, exitCode);
        String stdout = stdoutBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.startsWith("xml-model-validator "));
        assertTrue(stderrBuffer.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void executePrintsUsageForHelpFlag() throws Exception {
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        int exitCode = XmlValidationApplication.execute(
                new String[] { "--help" },
                new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));

        assertEquals(0, exitCode);
        String stdout = stdoutBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.contains("Usage: xml-model-validator"));
        assertTrue(stderrBuffer.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void executeReturnsTwoForMissingOptionValue() throws Exception {
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        int exitCode = XmlValidationApplication.execute(
                new String[] { "--directory" },
                new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        String stderr = stderrBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("Missing required parameter"));
        assertTrue(stdoutBuffer.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void executeAcceptsConfiguredFileExtensionsForDirectoryScans() throws Exception {
        writeRelaxNgSchema("schema.rng");
        write("record.csl", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;

        int exitCode;
        try {
            System.setErr(new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
            exitCode = XmlValidationApplication.execute(
                    new String[] {
                            "--directory",
                            temporaryDirectory.toString(),
                            "--file-extensions",
                            "csl"
                    },
                    new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                    new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(0, exitCode);
        assertTrue(stderrBuffer.toString(StandardCharsets.UTF_8).contains("Validating 1 file(s)"));
    }

    private XmlValidationApplication createApplication(Map<String, Path> schemaAliases) throws Exception {
        Constructor<XmlValidationApplication> constructor = XmlValidationApplication.class
                .getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(schemaAliases);
    }

    private int invokeRun(XmlValidationApplication application, ValidationArguments arguments, List<Path> files)
            throws Exception {
        Method run = XmlValidationApplication.class.getDeclaredMethod("run", ValidationArguments.class, List.class);
        run.setAccessible(true);
        return (Integer) run.invoke(application, arguments, files);
    }

    @SuppressWarnings("unchecked")
    private List<ValidationResult> invokeValidateFiles(
            XmlValidationApplication application,
            ValidationArguments arguments,
            List<Path> files,
            int workers) throws Exception {
        Method validateFiles = XmlValidationApplication.class.getDeclaredMethod(
                "validateFiles",
                ValidationArguments.class,
                List.class,
                int.class);
        validateFiles.setAccessible(true);
        return (List<ValidationResult>) validateFiles.invoke(application, arguments, files, workers);
    }

    private void writeRelaxNgSchema(String relativePath) throws IOException {
        write(relativePath, """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
    }

    private Path writeXml(String relativePath, String content) throws IOException {
        return write(relativePath, content);
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}
