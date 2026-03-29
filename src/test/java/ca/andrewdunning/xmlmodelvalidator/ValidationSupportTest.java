package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidationSupportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsConfigWithSchemaAliasesAndXmlModelRules() throws Exception {
        Path config = write("config.toml", """
                [schema_aliases]
                "https://example.com/schema.rng" = "schemas/local.rng"

                [[xml_model_rules]]
                directory = "styles"
                extension = "csl"
                mode = "replace"

                [[xml_model_rules.declarations]]
                href = "schema.rng"
                schematypens = "http://relaxng.org/ns/structure/1.0"

                [[xml_model_rules]]

                [[xml_model_rules.declarations]]
                href = "shared.sch"
                schematypens = "http://purl.oclc.org/dsdl/schematron"
                """);

        ValidatorConfig loaded = ValidationSupport.loadConfig(config);

        assertEquals(
                temporaryDirectory.resolve("schemas/local.rng").toAbsolutePath().normalize(),
                loaded.schemaAliases().get("https://example.com/schema.rng"));
        assertEquals(2, loaded.xmlModelRules().size());
        assertEquals(
                ValidationSupport.resolveAgainstWorkspace(Path.of("styles")),
                loaded.xmlModelRules().get(0).directory());
        assertEquals(".csl", loaded.xmlModelRules().get(0).extension());
        assertEquals(XmlModelRuleMode.REPLACE, loaded.xmlModelRules().get(0).mode());
        assertEquals(
                ValidationSupport.resolveAgainstWorkspace(Path.of("schema.rng")).toString(),
                loaded.xmlModelRules().get(0).entries().getFirst().href());
        assertEquals("", loaded.xmlModelRules().get(1).extension());
        assertEquals(XmlModelRuleMode.FALLBACK, loaded.xmlModelRules().get(1).mode());
        assertEquals(
                ValidationSupport.resolveAgainstWorkspace(Path.of("shared.sch")).toString(),
                loaded.xmlModelRules().get(1).entries().getFirst().href());
    }

    @Test
    void rejectsMalformedConfigToml() throws Exception {
        Path config = write("config.toml", """
                [[xml_model_rules]
                directory = "styles"
                """);

        IOException exception = assertThrows(IOException.class, () -> ValidationSupport.loadConfig(config));

        assertTrue(exception.getMessage().contains("Invalid validator config file"));
    }

    @Test
    void rejectsXmlModelRuleWithoutDeclarations() throws Exception {
        Path config = write("config.toml", """
                [[xml_model_rules]]
                directory = "styles"
                """);

        IOException exception = assertThrows(IOException.class, () -> ValidationSupport.loadConfig(config));

        assertTrue(exception.getMessage().contains("must declare at least one entry"));
    }

    @Test
    void rejectsXmlModelDeclarationWithoutHref() throws Exception {
        Path config = write("config.toml", """
                [[xml_model_rules]]

                [[xml_model_rules.declarations]]
                type = "application/xml"
                """);

        IOException exception = assertThrows(IOException.class, () -> ValidationSupport.loadConfig(config));

        assertTrue(exception.getMessage().contains("href"));
    }

    @Test
    void rejectsUnknownTopLevelConfigKey() throws Exception {
        Path config = write("config.toml", """
                title = "unexpected"
                """);

        IOException exception = assertThrows(IOException.class, () -> ValidationSupport.loadConfig(config));

        assertTrue(exception.getMessage().contains("Unsupported key(s)"));
        assertTrue(exception.getMessage().contains("title"));
    }

    @Test
    void rejectsUnknownRuleKey() throws Exception {
        Path config = write("config.toml", """
                [[xml_model_rules]]
                directory = "styles"
                priority = 10

                [[xml_model_rules.declarations]]
                href = "schema.rng"
                """);

        IOException exception = assertThrows(IOException.class, () -> ValidationSupport.loadConfig(config));

        assertTrue(exception.getMessage().contains("Unsupported key(s)"));
        assertTrue(exception.getMessage().contains("priority"));
    }

    @Test
    void rejectsUnknownDeclarationKey() throws Exception {
        Path config = write("config.toml", """
                [[xml_model_rules]]

                [[xml_model_rules.declarations]]
                href = "schema.rng"
                role = "primary"
                """);

        IOException exception = assertThrows(IOException.class, () -> ValidationSupport.loadConfig(config));

        assertTrue(exception.getMessage().contains("Unsupported key(s)"));
        assertTrue(exception.getMessage().contains("role"));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}
