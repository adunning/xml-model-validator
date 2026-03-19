package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        ValidationArguments arguments = ValidationArguments.parse(new String[] { valid.toString(), "--jobs", "1" });
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

        ValidationArguments arguments = ValidationArguments.parse(new String[] {
                "--fail-fast",
                "--jobs",
                "1",
                invalid.toString(),
                validOne.toString(),
                validTwo.toString()
        });
        XmlValidationApplication application = createApplication(Map.of());
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;

        int exitCode;
        try {
            System.setErr(new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8));
            exitCode = invokeRun(application, arguments, arguments.resolveFiles());
        } finally {
            System.setErr(originalErr);
        }

        String stderr = stderrBuffer.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode);
        assertTrue(stderr.contains("Validated 1 file(s): 0 OK, 1 failed"));
    }

    @Test
    void reportsConfiguredWorkerCount() throws Exception {
        writeRelaxNgSchema("schema.rng");
        Path valid = writeXml("single.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);

        ValidationArguments arguments = ValidationArguments.parse(new String[] {
                "--jobs",
                "2",
                valid.toString()
        });
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