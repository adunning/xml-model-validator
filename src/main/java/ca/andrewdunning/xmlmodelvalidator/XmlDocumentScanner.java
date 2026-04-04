package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Scans an XML document once to collect xml-model declarations, XSD instance hints, and any well-formedness failure.
 */
final class XmlDocumentScanner {
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final Pattern QUOTED_TOKEN_PATTERN = Pattern.compile("\"([^\"\\r\\n]+)\"");
    private static final SecureXmlReaderPool XML_READERS = new SecureXmlReaderPool();

    XmlDocumentScan scan(Path file) throws IOException {
        ScanHandler handler = new ScanHandler(file);
        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLReader reader = XML_READERS.reader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            InputSource inputSource = new InputSource(inputStream);
            inputSource.setSystemId(file.toUri().toString());
            reader.parse(inputSource);
            return handler.result();
        } catch (SAXParseException exception) {
            return handler.result(issueFrom(file, exception));
        } catch (SAXException exception) {
            throw new IOException("Could not scan XML document " + file, exception);
        }
    }

    private static ValidationIssue issueFrom(Path file, SAXParseException exception) {
        Integer line;
        if (exception.getLineNumber() > 0) {
            line = exception.getLineNumber();
        } else {
            line = null;
        }
        Integer column;
        if (exception.getColumnNumber() > 0) {
            column = exception.getColumnNumber();
        } else {
            column = null;
        }
        return new ValidationIssue(file, formatMessage(exception.getMessage()), line, column, false);
    }

    private static String formatMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return QUOTED_TOKEN_PATTERN.matcher(message).replaceAll("`$1`");
    }

    private static final class ScanHandler extends DefaultHandler {
        private final Path file;
        private final List<XmlModelEntry> xmlModelEntries = new ArrayList<>();
        private final List<String> schemaLocations = new ArrayList<>();
        private boolean rootSeen;

        private ScanHandler(Path file) {
            this.file = file;
        }

        @Override
        public void processingInstruction(String target, String data) {
            if (rootSeen || !"xml-model".equals(target)) {
                return;
            }
            try {
                xmlModelEntries.add(XmlModelParser.parseDeclaration(data));
            } catch (IOException ignored) {
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (rootSeen) {
                return;
            }
            rootSeen = true;
            collectSchemaLocations(attributes);
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }

        private void collectSchemaLocations(Attributes attributes) {
            schemaLocations.addAll(ValidationSupport.extractSchemaLocations(
                    attributes.getValue(XSI_NS, "noNamespaceSchemaLocation"),
                    attributes.getValue(XSI_NS, "schemaLocation")));
        }

        private XmlDocumentScan result() {
            return result(null);
        }

        private XmlDocumentScan result(ValidationIssue wellFormednessIssue) {
            return new XmlDocumentScan(
                    file, List.copyOf(xmlModelEntries), List.copyOf(schemaLocations), wellFormednessIssue);
        }
    }
}
