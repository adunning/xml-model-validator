package ca.andrewdunning.xmlmodelvalidator;

import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Caches prepared Schematron schemas and compiled SchXslt validators.
 */
final class SchematronCache {
    private final Processor processor;
    private final Map<Path, XsltExecutable> validators;
    private final XsltExecutable transpiler;

    SchematronCache(Processor processor) {
        this.processor = processor;
        this.validators = new HashMap<>();
        this.transpiler = compileTranspiler(processor);
    }

    /**
     * Returns a standalone Schematron schema, extracting embedded Schematron rules from Relax NG when
     * needed.
     */
    synchronized Path prepare(Path schemaPath) throws IOException, ParserConfigurationException, TransformerException {
        Files.createDirectories(ValidationSupport.SCHEMATRON_CACHE_DIR);
        Document document = parseDocument(schemaPath);
        if (ValidationSupport.SCHEMATRON_NS.equals(document.getDocumentElement().getNamespaceURI())
                && "schema".equals(document.getDocumentElement().getLocalName())) {
            return schemaPath;
        }
        if (!ValidationSupport.RELAXNG_NS.equals(document.getDocumentElement().getNamespaceURI())
                || !"grammar".equals(document.getDocumentElement().getLocalName())) {
            throw new IOException("Unsupported Schematron source: " + schemaPath);
        }

        NodeList patterns = document.getElementsByTagNameNS(ValidationSupport.SCHEMATRON_NS, "pattern");
        if (patterns.getLength() == 0) {
            return null;
        }

        Path output = ValidationSupport.SCHEMATRON_CACHE_DIR.resolve(
                schemaPath.getFileName().toString() + "-" + sha256(schemaPath.toString()) + ".sch");
        if (Files.exists(output)) {
            return output;
        }

        Document extracted = createDocument();
        Element root = extracted.createElementNS(ValidationSupport.SCHEMATRON_NS, "schema");
        root.setAttribute("queryBinding", "xslt2");
        extracted.appendChild(root);

        NodeList namespaces = document.getElementsByTagNameNS(ValidationSupport.SCHEMATRON_NS, "ns");
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < namespaces.getLength(); index += 1) {
            Element namespace = (Element) namespaces.item(index);
            String prefix = namespace.getAttribute("prefix");
            if (prefix.isBlank() || seen.contains(prefix)) {
                continue;
            }
            seen.add(prefix);
            root.appendChild(extracted.importNode(namespace, true));
        }

        for (int index = 0; index < patterns.getLength(); index += 1) {
            root.appendChild(extracted.importNode(patterns.item(index), true));
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(extracted), new StreamResult(output.toFile()));
        return output;
    }

    /**
     * Compiles and memoizes the SchXslt-generated validator stylesheet for a prepared schema.
     */
    synchronized XsltExecutable getValidator(Path schemaPath) throws SaxonApiException {
        if (validators.containsKey(schemaPath)) {
            return validators.get(schemaPath);
        }
        DocumentBuilder builder = processor.newDocumentBuilder();
        XdmNode schematron = builder.build(schemaPath.toFile());
        XdmDestination stylesheetDestination = new XdmDestination();
        Xslt30Transformer transformer = transpiler.load30();
        transformer.transform(schematron.asSource(), stylesheetDestination);

        XsltCompiler compiler = processor.newXsltCompiler();
        XsltExecutable validator = compiler.compile(stylesheetDestination.getXdmNode().asSource());
        validators.put(schemaPath, validator);
        return validator;
    }

    private static XsltExecutable compileTranspiler(Processor processor) {
        try {
            XsltCompiler compiler = processor.newXsltCompiler();
            try (InputStream stream = SchematronCache.class.getClassLoader()
                    .getResourceAsStream("content/transpile.xsl")) {
                if (stream == null) {
                    throw new IllegalStateException("Could not find SchXslt2 transpile.xsl on the classpath");
                }
                return compiler.compile(new StreamSource(stream));
            }
        } catch (IOException | SaxonApiException exception) {
            throw new IllegalStateException("Could not compile SchXslt2 transpiler", exception);
        }
    }

    private static Document parseDocument(Path path) throws ParserConfigurationException, IOException {
        try {
            var builder = documentBuilderFactory().newDocumentBuilder();
            return builder.parse(path.toFile());
        } catch (org.xml.sax.SAXException exception) {
            throw new IOException("Could not parse XML document: " + path, exception);
        }
    }

    private static Document createDocument() throws ParserConfigurationException {
        return documentBuilderFactory().newDocumentBuilder().newDocument();
    }

    private static DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException ignored) {
        }
        return factory;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
