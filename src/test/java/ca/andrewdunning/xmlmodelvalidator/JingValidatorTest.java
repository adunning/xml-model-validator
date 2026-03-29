package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JingValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesSeverityPrefixAndQuotedTokens() {
        String normalized = JingValidator.normalizeMessage(
                "error: element \"respStmt\" not allowed yet; missing required element \"title\"");

        assertEquals("element `respStmt` not allowed yet; missing required element `title`", normalized);
    }

    @Test
    void preservesMessagesWithoutSeverityPrefix() {
        String normalized = JingValidator.normalizeMessage("element \"a\" conflicts with \"b\"");

        assertEquals("element `a` conflicts with `b`", normalized);
    }

    @Test
    void validatesValidDocument() throws Exception {
        Path schema = write("schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <root/>
                """);

        List<ValidationIssue> issues = new JingValidator().validate(schema, xml);

        assertTrue(issues.isEmpty());
    }

    @Test
    void reportsInvalidDocument() throws Exception {
        Path schema = write("schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <wrong/>
                """);

        List<ValidationIssue> issues = new JingValidator().validate(schema, xml);

        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("element `wrong`")));
    }

    @Test
    void cachesCompiledSchemaAcrossValidations() throws Exception {
        Path schema = write("schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        Path first = write("first.xml", """
                <?xml version="1.0"?>
                <root/>
                """);
        Path second = write("second.xml", """
                <?xml version="1.0"?>
                <wrong/>
                """);
        JingValidator runner = new JingValidator();

        assertTrue(runner.validate(schema, first).isEmpty());
        assertEquals(1, runner.cachedSchemaCount());

        assertFalse(runner.validate(schema, second).isEmpty());
        assertEquals(1, runner.cachedSchemaCount());
    }

    private Path write(String filename, String content) throws Exception {
        Path file = temporaryDirectory.resolve(filename);
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}
