package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidationSupportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsSchemaAliasesAndIgnoresCommentsAndBlankLines() throws Exception {
        Path schema = write("schemas/local.rng", "<grammar xmlns=\"http://relaxng.org/ns/structure/1.0\"/>");
        Path aliases = write("aliases.tsv", """
                # comment

                https://example.com/schema.rng\tschemas/local.rng
                """);

        Map<String, Path> loaded = ValidationSupport.loadSchemaAliases(aliases);

        assertEquals(1, loaded.size());
        assertEquals(schema.toAbsolutePath().normalize(), loaded.get("https://example.com/schema.rng"));
    }

    @Test
    void rejectsMalformedAliasLine() throws Exception {
        Path aliases = write("aliases.tsv", "missing-tab-separator\n");

        IOException exception = assertThrows(IOException.class, () -> ValidationSupport.loadSchemaAliases(aliases));

        assertTrue(exception.getMessage().contains("Invalid schema alias line"));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}