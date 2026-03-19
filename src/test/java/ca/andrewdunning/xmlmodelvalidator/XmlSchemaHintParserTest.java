package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class XmlSchemaHintParserTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsNoNamespaceSchemaLocation() throws Exception {
        Path xml = write("document.xml", """
                <root xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:noNamespaceSchemaLocation="schema.xsd"/>
                """);

        List<String> locations = new XmlSchemaHintParser().parse(xml);

        assertEquals(List.of("schema.xsd"), locations);
    }

    @Test
    void ignoresTrailingOddTokenInSchemaLocation() throws Exception {
        Path xml = write("document.xml", """
                <root xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:schemaLocation="urn:one one.xsd urn:two"/>
                """);

        List<String> locations = new XmlSchemaHintParser().parse(xml);

        assertEquals(List.of("one.xsd"), locations);
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}