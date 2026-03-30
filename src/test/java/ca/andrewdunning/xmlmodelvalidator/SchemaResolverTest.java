package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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

    @ParameterizedTest
    @MethodSource("failingResolutionScenarios")
    void reportsContextForResolutionFailures(
            ThrowingSupplier<Path> resolution,
            String expectedFragment,
            String secondaryFragment) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, resolution::get);

        assertAll(
                () -> assertTrue(exception.getMessage().contains(expectedFragment)),
                () -> assertTrue(exception.getMessage().contains(secondaryFragment)));
    }

    private Stream<org.junit.jupiter.params.provider.Arguments> failingResolutionScenarios() {
        SchemaResolver defaultResolver = new SchemaResolver(Map.of(), new RemoteSchemaCache());
        Path missingAliasTarget = temporaryDirectory.resolve("schemas/missing.rng");
        SchemaResolver aliasResolver = new SchemaResolver(
                Map.of("https://example.com/schema.rng", missingAliasTarget),
                new RemoteSchemaCache());
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        (ThrowingSupplier<Path>) () -> defaultResolver.resolve("missing.rng", temporaryDirectory),
                        "missing.rng",
                        "checked"),
                org.junit.jupiter.params.provider.Arguments.of(
                        (ThrowingSupplier<Path>) () -> aliasResolver.resolve("https://example.com/schema.rng", temporaryDirectory),
                        "Schema alias 'https://example.com/schema.rng'",
                        "resolves to a missing file"));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
