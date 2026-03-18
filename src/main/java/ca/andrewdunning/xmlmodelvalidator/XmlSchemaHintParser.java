package ca.andrewdunning.xmlmodelvalidator;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads legacy XML Schema instance hints from the document element.
 */
final class XmlSchemaHintParser {
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    /**
     * Returns XSD schema locations from {@code xsi:schemaLocation} and
     * {@code xsi:noNamespaceSchemaLocation}.
     */
    List<String> parse(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);

        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    return extractSchemaLocations(reader);
                }
            }
            reader.close();
            return List.of();
        } catch (XMLStreamException exception) {
            throw new IOException("Could not parse XML Schema instance hints from " + file, exception);
        }
    }

    private List<String> extractSchemaLocations(XMLStreamReader reader) {
        List<String> schemaLocations = new ArrayList<>();

        String noNamespaceSchemaLocation = reader.getAttributeValue(XSI_NS, "noNamespaceSchemaLocation");
        if (noNamespaceSchemaLocation != null && !noNamespaceSchemaLocation.isBlank()) {
            schemaLocations.add(noNamespaceSchemaLocation.trim());
        }

        String schemaLocation = reader.getAttributeValue(XSI_NS, "schemaLocation");
        if (schemaLocation != null && !schemaLocation.isBlank()) {
            String[] parts = schemaLocation.trim().split("\\s+");
            for (int index = 1; index < parts.length; index += 2) {
                schemaLocations.add(parts[index]);
            }
        }

        return schemaLocations;
    }
}
