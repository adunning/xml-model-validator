package ca.andrewdunning.xmlmodelvalidator;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/** Reuses secure SAX readers per thread to avoid repeated parser construction on hot validation paths. */
final class SecureXmlReaderPool {
    private final ThreadLocal<XMLReader> readers = ThreadLocal.withInitial(this::createReaderUnchecked);

    XMLReader reader() {
        return readers.get();
    }

    private XMLReader createReaderUnchecked() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return factory.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IllegalStateException("Could not create secure XML reader", exception);
        }
    }

    private static void setFeature(SAXParserFactory factory, String feature, boolean value)
            throws ParserConfigurationException, SAXException {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | SAXException ignored) {
        }
    }
}
