package ca.andrewdunning.xmlmodelvalidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class XmlDocumentScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void collectsXmlModelDeclarationsAndSchemaHintsInOneScan() throws Exception {
        Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:noNamespaceSchemaLocation="fallback.xsd"
                      xsi:schemaLocation="urn:one one.xsd urn:two two.xsd"/>
                """);

        XmlDocumentScan scan = new XmlDocumentScanner().scan(xml);

        assertEquals(
                List.of("schema.rng"),
                scan.xmlModelEntries().stream().map(XmlModelEntry::href).toList());
        assertEquals(List.of("fallback.xsd", "one.xsd", "two.xsd"), scan.schemaLocations());
        assertEquals(null, scan.wellFormednessIssue());
    }

    @Test
    void ignoresXmlModelInstructionsAfterRootStart() throws Exception {
        Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <root><?xml-model href="ignored.rng" schematypens="http://relaxng.org/ns/structure/1.0"?></root>
                """);

        XmlDocumentScan scan = new XmlDocumentScanner().scan(xml);

        assertTrue(scan.xmlModelEntries().isEmpty());
    }

    @Test
    void reportsMalformedXmlAsWellFormednessIssue() throws Exception {
        Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root><child></root>
                """);

        XmlDocumentScan scan = new XmlDocumentScanner().scan(xml);

        assertNotNull(scan.wellFormednessIssue());
        assertTrue(scan.wellFormednessIssue().message().contains("`")
                || !scan.wellFormednessIssue().message().isBlank());
        assertEquals(
                List.of("schema.rng"),
                scan.xmlModelEntries().stream().map(XmlModelEntry::href).toList());
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}
