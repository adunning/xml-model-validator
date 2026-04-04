package ca.andrewdunning.xmlmodelvalidator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ResourceLock(Resources.SYSTEM_ERR)
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
    void handlesMalformedXmlDependingOnWhetherTheRootHasStarted(String filename, String content, boolean shouldFail)
            throws Exception {
        Path xml = write(filename, content);

        if (shouldFail) {
            PrintStream originalErr = System.err;
            ByteArrayOutputStream ignoredErr = new ByteArrayOutputStream();
            IOException exception;
            try {
                System.setErr(new PrintStream(ignoredErr, true, StandardCharsets.UTF_8));
                exception = assertThrows(IOException.class, () -> new XmlModelParser().parse(xml));
            } finally {
                System.setErr(originalErr);
            }
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
