package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void toleratesMalformedXmlAfterRootStart() throws Exception {
        Path xml = write("broken.xml", "<root><child></root>");

        List<XmlModelEntry> entries = assertDoesNotThrow(() -> new XmlModelParser().parse(xml));

        assertTrue(entries.isEmpty());
    }

    @Test
    void throwsIOExceptionForMalformedXmlBeforeRootStart() throws Exception {
        Path malformedProlog = write("broken-prolog.xml", "<?xml-model href=\"schema.rng\"<root/>");

        IOException exception = assertThrows(IOException.class, () -> new XmlModelParser().parse(malformedProlog));

        assertTrue(exception.getMessage().contains("Could not parse xml-model processing instructions"));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}