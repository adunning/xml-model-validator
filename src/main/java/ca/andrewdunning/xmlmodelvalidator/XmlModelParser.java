package ca.andrewdunning.xmlmodelvalidator;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

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
 * Extracts {@code xml-model} processing instructions from an XML document
 * without fully building a DOM.
 */
final class XmlModelParser {
    private static final Pattern XML_MODEL_ATTRIBUTE_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*([\"'])(.*?)\\2");
    private static final Pattern XML_MODEL_WRAPPER_PATTERN = Pattern.compile("^<\\?xml-model\\s+(.*?)\\?>$", Pattern.DOTALL);
    private static final SecureXmlReaderPool XML_READERS = new SecureXmlReaderPool();

    /**
     * Returns xml-model declarations in document order.
     */
    List<XmlModelEntry> parse(Path file) throws IOException {
        List<XmlModelEntry> entries = new ArrayList<>();
        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLReader reader = XML_READERS.reader();
            reader.setContentHandler(new XmlModelHandler(entries));
            InputSource inputSource = new InputSource(inputStream);
            inputSource.setSystemId(file.toUri().toString());
            reader.parse(inputSource);
            return entries;
        } catch (StopParsingException ignored) {
            return entries;
        } catch (SAXParseException exception) {
            throw new IOException(
                    "Could not parse xml-model processing instructions from "
                            + file
                            + " at line "
                            + exception.getLineNumber()
                            + ", column "
                            + exception.getColumnNumber(),
                    exception);
        } catch (SAXException exception) {
            throw new IOException("Could not parse xml-model processing instructions from " + file, exception);
        }
    }

    /**
     * Parses one manual {@code xml-model} declaration string into an entry.
     */
    static XmlModelEntry parseDeclaration(String declaration) throws IOException {
        String normalized = declaration == null ? "" : declaration.trim();
        if (normalized.isEmpty()) {
            throw new IOException("Manual xml-model declaration must not be blank");
        }

        Matcher wrapperMatcher = XML_MODEL_WRAPPER_PATTERN.matcher(normalized);
        if (wrapperMatcher.matches()) {
            normalized = wrapperMatcher.group(1).trim();
        }

        Map<String, String> attributes = parseAttributes(normalized);
        String href = attributes.get("href");
        if (href == null || href.isBlank()) {
            throw new IOException("Manual xml-model declaration must include a non-blank href attribute");
        }

        return new XmlModelEntry(
                href,
                attributes.get("schematypens"),
                attributes.get("type"),
                attributes.get("phase"));
    }

    private static Map<String, String> parseAttributes(String data) {
        Map<String, String> attributes = new HashMap<>();
        Matcher attributeMatcher = XML_MODEL_ATTRIBUTE_PATTERN.matcher(data);
        while (attributeMatcher.find()) {
            attributes.put(attributeMatcher.group(1), attributeMatcher.group(3));
        }
        return attributes;
    }

    private static final class XmlModelHandler extends DefaultHandler {
        private final List<XmlModelEntry> entries;

        private XmlModelHandler(List<XmlModelEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void processingInstruction(String target, String data) {
            if (!"xml-model".equals(target)) {
                return;
            }
            try {
                entries.add(parseDeclaration(data));
            } catch (IOException ignored) {
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            throw new StopParsingException();
        }
    }

    private static final class StopParsingException extends SAXException {
    }
}
