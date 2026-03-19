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

final class SchemaResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesAliasBeforeFilesystemLookup() throws Exception {
        Path aliased = write("schemas/aliased.rng", "<grammar xmlns=\"http://relaxng.org/ns/structure/1.0\"/>");
        SchemaResolver resolver = new SchemaResolver(
                Map.of("https://example.com/schema.rng", aliased),
                new RemoteSchemaCache());

        Path resolved = resolver.resolve("https://example.com/schema.rng", temporaryDirectory);

        assertEquals(aliased.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolvesRelativeToFileSystemId() throws Exception {
        Path parentSchema = write("schemas/main.xsd", "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        Path imported = write("schemas/imported.xsd", "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        SchemaResolver resolver = new SchemaResolver(Map.of(), new RemoteSchemaCache());

        ResolvedSchemaSource resolved = resolver.resolveRelativeToSystemId(
                "imported.xsd",
                parentSchema.toUri().toString(),
                temporaryDirectory);

        assertEquals(imported.toAbsolutePath().normalize(), resolved.path());
        assertEquals(imported.toUri().toString(), resolved.systemId());
    }

    @Test
    void throwsForMissingLocalPath() {
        SchemaResolver resolver = new SchemaResolver(Map.of(), new RemoteSchemaCache());

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("missing.rng", temporaryDirectory));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}