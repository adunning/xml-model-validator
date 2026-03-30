package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.xml.sax.XMLReader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class SecureXmlReaderPoolTest {
    @Test
    void reusesReaderWithinTheSameThread() {
        SecureXmlReaderPool pool = new SecureXmlReaderPool();

        XMLReader first = pool.reader();
        XMLReader second = pool.reader();

        assertNotNull(first);
        assertSame(first, second);
    }
}
