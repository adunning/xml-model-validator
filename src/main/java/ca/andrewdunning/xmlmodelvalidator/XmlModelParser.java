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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code xml-model} processing instructions from an XML document without fully building a DOM.
 */
final class XmlModelParser {
    private static final Pattern XML_MODEL_ATTRIBUTE_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*([\"'])(.*?)\\2");

    /**
     * Returns xml-model declarations in document order.
     */
    List<XmlModelEntry> parse(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);

        List<XmlModelEntry> entries = new ArrayList<>();
        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.PROCESSING_INSTRUCTION
                        && "xml-model".equals(reader.getPITarget())) {
                    Map<String, String> attributes = new HashMap<>();
                    Matcher attributeMatcher = XML_MODEL_ATTRIBUTE_PATTERN.matcher(reader.getPIData());
                    while (attributeMatcher.find()) {
                        attributes.put(attributeMatcher.group(1), attributeMatcher.group(3));
                    }
                    String href = attributes.get("href");
                    if (href != null && !href.isBlank()) {
                        entries.add(new XmlModelEntry(
                                href,
                                attributes.get("schematypens"),
                                attributes.get("type"),
                                attributes.get("phase")));
                    }
                }
            }
            reader.close();
            return entries;
        } catch (XMLStreamException exception) {
            throw new IOException("Could not parse xml-model processing instructions from " + file, exception);
        }
    }
}
