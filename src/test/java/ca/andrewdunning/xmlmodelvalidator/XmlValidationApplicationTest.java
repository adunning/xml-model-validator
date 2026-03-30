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
                null,
                1,
                false);
        XmlValidationApplication application = createApplication(Map.of(), List.of());

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
                null,
                1,
                true);
        XmlValidationApplication application = createApplication(Map.of(), List.of());
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
                null,
                2,
                false);
        XmlValidationApplication application = createApplication(Map.of(), List.of());
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
        assertFalse(stderrBuffer.toString(StandardCharsets.UTF_8).contains("No matching files found"));
    }

    @Test
    void executeAcceptsConfigFile() throws Exception {
        write("styles/schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        write("styles/document.csl", """
                <?xml version="1.0"?>
                <root/>
                """);
        Path configFile = write("config.toml", """
                [[xml_model_rules]]
                directory = "%s"
                extension = "csl"
                mode = "fallback"

                [[xml_model_rules.declarations]]
                href = "%s"
                schematypens = "http://relaxng.org/ns/structure/1.0"
                """.formatted(
                        temporaryDirectory.resolve("styles"),
                        temporaryDirectory.resolve("styles/schema.rng")));
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;

        int exitCode;
        try {
            System.setErr(new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
            exitCode = XmlValidationApplication.execute(
                    new String[] {
                            "--directory",
                            temporaryDirectory.resolve("styles").toString(),
                            "--file-extensions",
                            "csl",
                            "--config",
                            configFile.toString()
                    },
                    new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                    new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(0, exitCode);
        assertFalse(stderrBuffer.toString(StandardCharsets.UTF_8).contains("No matching files found"));
    }

    @Test
    void executeAcceptsInlineXmlModelRule() throws Exception {
        write("styles/schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        write("styles/document.csl", """
                <?xml version="1.0"?>
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
                            temporaryDirectory.resolve("styles").toString(),
                            "--file-extensions",
                            "csl",
                            "--rule-mode",
                            "fallback",
                            "--rule-directory",
                            temporaryDirectory.resolve("styles").toString(),
                            "--rule-extension",
                            "csl",
                            "--xml-model-declaration",
                            "href=\"%s\" schematypens=\"http://relaxng.org/ns/structure/1.0\""
                                    .formatted(temporaryDirectory.resolve("styles/schema.rng"))
                    },
                    new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                    new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(0, exitCode);
        assertFalse(stderrBuffer.toString(StandardCharsets.UTF_8).contains("No matching files found"));
    }

    @Test
    void executeInfersFileExtensionsFromInlineRuleExtension() throws Exception {
        write("styles/schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        write("styles/document.csl", """
                <?xml version="1.0"?>
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
                            temporaryDirectory.resolve("styles").toString(),
                            "--rule-mode",
                            "fallback",
                            "--rule-directory",
                            temporaryDirectory.resolve("styles").toString(),
                            "--rule-extension",
                            "csl",
                            "--xml-model-declaration",
                            "href=\"%s\" schematypens=\"http://relaxng.org/ns/structure/1.0\""
                                    .formatted(temporaryDirectory.resolve("styles/schema.rng"))
                    },
                    new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                    new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(0, exitCode);
        assertFalse(stderrBuffer.toString(StandardCharsets.UTF_8).contains("No matching files found"));
    }

    @Test
    void executeFailsForMissingExplicitConfigFile() throws Exception {
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        int exitCode = XmlValidationApplication.execute(
                new String[] { "--config", temporaryDirectory.resolve("missing.toml").toString() },
                new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertTrue(stderrBuffer.toString(StandardCharsets.UTF_8).contains("does not exist"));
    }

    @Test
    void executeFailsForInlineRuleFlagsWithoutDeclarations() throws Exception {
        write("document.xml", "<root/>");
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        int exitCode = XmlValidationApplication.execute(
                new String[] {
                        "--directory",
                        temporaryDirectory.toString(),
                        "--rule-mode",
                        "replace"
                },
                new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));

        assertEquals(1, exitCode);
        assertTrue(stderrBuffer.toString(StandardCharsets.UTF_8).contains("--xml-model-declaration"));
    }

    @Test
    void inlineRuleOverridesConflictingConfigRule() throws Exception {
        write("styles/config.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="wrong">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        write("styles/inline.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        write("styles/document.csl", """
                <?xml version="1.0"?>
                <root/>
                """);
        Path configFile = write("config.toml", """
                [[xml_model_rules]]
                directory = "%s"
                extension = "csl"
                mode = "fallback"

                [[xml_model_rules.declarations]]
                href = "%s"
                schematypens = "http://relaxng.org/ns/structure/1.0"
                """.formatted(
                        temporaryDirectory.resolve("styles"),
                        temporaryDirectory.resolve("styles/config.rng")));
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;

        int exitCode;
        try {
            System.setErr(new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
            exitCode = XmlValidationApplication.execute(
                    new String[] {
                            "--directory",
                            temporaryDirectory.resolve("styles").toString(),
                            "--file-extensions",
                            "csl",
                            "--config",
                            configFile.toString(),
                            "--rule-mode",
                            "fallback",
                            "--rule-directory",
                            temporaryDirectory.resolve("styles").toString(),
                            "--rule-extension",
                            "csl",
                            "--xml-model-declaration",
                            "href=\"%s\" schematypens=\"http://relaxng.org/ns/structure/1.0\""
                                    .formatted(temporaryDirectory.resolve("styles/inline.rng"))
                    },
                    new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8),
                    new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(0, exitCode);
    }

    private XmlValidationApplication createApplication(Map<String, Path> schemaAliases, List<XmlModelRule> xmlModelRules) throws Exception {
        Constructor<XmlValidationApplication> constructor = XmlValidationApplication.class
                .getDeclaredConstructor(Map.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(schemaAliases, xmlModelRules);
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
