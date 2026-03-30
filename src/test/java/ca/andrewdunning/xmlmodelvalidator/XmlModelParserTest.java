package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XmlModelParserTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsXmlModelEntriesWithBlankHref() throws Exception {
        Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <?xml-model href="" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);

        List<XmlModelEntry> entries = new XmlModelParser().parse(xml);

        assertEquals(1, entries.size());
        assertEquals("schema.rng", entries.getFirst().href());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            broken.xml|<root><child></root>|false
            broken-prolog.xml|<?xml-model href="schema.rng"<root/>|true
            """)
    void handlesMalformedXmlDependingOnWhetherTheRootHasStarted(
            String filename,
            String content,
            boolean shouldFail) throws Exception {
        Path xml = write(filename, content);

        if (shouldFail) {
            IOException exception = assertThrows(IOException.class, () -> new XmlModelParser().parse(xml));
            assertTrue(exception.getMessage().contains("Could not parse xml-model processing instructions"));
            return;
        }

        List<XmlModelEntry> entries = assertDoesNotThrow(() -> new XmlModelParser().parse(xml));
        assertTrue(entries.isEmpty());
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}
