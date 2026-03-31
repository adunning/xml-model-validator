package ca.andrewdunning.xmlmodelvalidator;

import com.thaiopensource.relaxng.translate.Driver;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Caches prepared Schematron schemas and compiled SchXslt validators.
 */
final class SchematronCache {
    private final Processor processor;
    private final Map<Path, Optional<Path>> preparedSchemas;
    private final Map<Path, XsltExecutable> validators;
    private final XsltExecutable transpiler;
    private final RemoteSchemaCache remoteSchemaCache;

    SchematronCache(Processor processor) {
        this.processor = processor;
        this.preparedSchemas = new HashMap<>();
        this.validators = new HashMap<>();
        this.transpiler = compileTranspiler(processor);
        this.remoteSchemaCache = new RemoteSchemaCache();
    }

    /**
     * Returns a standalone Schematron schema, extracting embedded Schematron rules from Relax NG when
     * needed.
     */
    synchronized Path prepare(ResolvedSchemaSource schemaSource) throws IOException, ParserConfigurationException, TransformerException {
        Path normalizedSchemaPath = schemaSource.path().toAbsolutePath().normalize();
        Optional<Path> cached = preparedSchemas.get(normalizedSchemaPath);
        if (cached != null) {
            return cached.orElse(null);
        }
        Files.createDirectories(ValidationSupport.SCHEMATRON_CACHE_DIR);
        ResolvedSchemaSource schematronSource = prepareSchematronSource(
                new ResolvedSchemaSource(normalizedSchemaPath, schemaSource.systemId()));
        Document document = parseDocument(schematronSource.path());
        if (ValidationSupport.SCHEMATRON_NS.equals(document.getDocumentElement().getNamespaceURI())
                && "schema".equals(document.getDocumentElement().getLocalName())) {
            ensureSupportedQueryBinding(document, schematronSource.path());
            preparedSchemas.put(normalizedSchemaPath, Optional.of(schematronSource.path()));
            return schematronSource.path();
        }
        if (!ValidationSupport.RELAXNG_NS.equals(document.getDocumentElement().getNamespaceURI())
                || !"grammar".equals(document.getDocumentElement().getLocalName())) {
            throw new IOException("Unsupported Schematron source: " + schematronSource.path());
        }

        Path output = ValidationSupport.SCHEMATRON_CACHE_DIR.resolve(
                normalizedSchemaPath.getFileName().toString() + "-" + sha256(normalizedSchemaPath.toString()) + ".sch");
        if (Files.exists(output)) {
            preparedSchemas.put(normalizedSchemaPath, Optional.of(output));
            return output;
        }

        Document extracted = createDocument();
        Element root = extracted.createElementNS(ValidationSupport.SCHEMATRON_NS, "schema");
        root.setAttribute("queryBinding", "xslt2");
        extracted.appendChild(root);
        ExtractionState extractionState = new ExtractionState(extracted, root);
        appendSchematronFragments(schematronSource, extractionState);
        if (!extractionState.foundPattern()) {
            preparedSchemas.put(normalizedSchemaPath, Optional.empty());
            return null;
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        try (OutputStream stream = Files.newOutputStream(output)) {
            transformer.transform(new DOMSource(extracted), new StreamResult(stream));
        }
        preparedSchemas.put(normalizedSchemaPath, Optional.of(output));
        return output;
    }

    private void appendSchematronFragments(ResolvedSchemaSource schemaSource, ExtractionState extractionState)
            throws IOException, ParserConfigurationException {
        if (!extractionState.markVisited(schemaSource.systemId())) {
            return;
        }

        Document document = parseDocument(schemaSource.path());
        if (!ValidationSupport.RELAXNG_NS.equals(document.getDocumentElement().getNamespaceURI())
                || !"grammar".equals(document.getDocumentElement().getLocalName())) {
            return;
        }

        NodeList namespaces = document.getElementsByTagNameNS(ValidationSupport.SCHEMATRON_NS, "ns");
        for (int index = 0; index < namespaces.getLength(); index += 1) {
            Element namespace = (Element) namespaces.item(index);
            extractionState.appendNamespace(namespace);
        }

        NodeList patterns = document.getElementsByTagNameNS(ValidationSupport.SCHEMATRON_NS, "pattern");
        for (int index = 0; index < patterns.getLength(); index += 1) {
            extractionState.appendPattern((Element) patterns.item(index));
        }

        NodeList includes = document.getElementsByTagNameNS(ValidationSupport.RELAXNG_NS, "include");
        for (int index = 0; index < includes.getLength(); index += 1) {
            String href = ((Element) includes.item(index)).getAttribute("href");
            if (href != null && !href.isBlank()) {
                appendSchematronFragments(prepareSchematronSource(resolveRelativeSource(href, schemaSource)), extractionState);
            }
        }

        NodeList externalRefs = document.getElementsByTagNameNS(ValidationSupport.RELAXNG_NS, "externalRef");
        for (int index = 0; index < externalRefs.getLength(); index += 1) {
            String href = ((Element) externalRefs.item(index)).getAttribute("href");
            if (href != null && !href.isBlank()) {
                appendSchematronFragments(prepareSchematronSource(resolveRelativeSource(href, schemaSource)), extractionState);
            }
        }
    }

    private ResolvedSchemaSource prepareSchematronSource(ResolvedSchemaSource schemaSource) throws IOException {
        String filename = schemaSource.path().getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".rnc")) {
            return schemaSource;
        }

        Path output = ValidationSupport.SCHEMATRON_CACHE_DIR.resolve(
                schemaSource.path().getFileName().toString() + "-" + sha256(schemaSource.systemId()) + ".rng");
        if (Files.exists(output)) {
            return new ResolvedSchemaSource(output, schemaSource.systemId());
        }

        Driver driver = new Driver();
        int exitCode = driver.run(new String[] {
                "-I", "rnc",
                "-O", "rng",
                schemaSource.systemId(),
                output.toString()
        });
        if (exitCode != 0 || !Files.exists(output)) {
            throw new IOException("Could not convert RELAX NG Compact Syntax schema to XML syntax: " + schemaSource.systemId());
        }
        return new ResolvedSchemaSource(output, schemaSource.systemId());
    }

    private ResolvedSchemaSource resolveRelativeSource(String href, ResolvedSchemaSource baseSource) throws IOException {
        String effectiveHref = href;
        String baseSystemId = baseSource.systemId();
        if (baseSystemId != null
                && baseSystemId.toLowerCase(Locale.ROOT).endsWith(".rnc")
                && href.toLowerCase(Locale.ROOT).endsWith(".rng")) {
            effectiveHref = href.substring(0, href.length() - 4) + ".rnc";
        }

        if (baseSystemId != null && !baseSystemId.isBlank()) {
            java.net.URI resolved = java.net.URI.create(baseSystemId).resolve(effectiveHref);
            String resolvedString = resolved.toString();
            if (resolvedString.startsWith("http://") || resolvedString.startsWith("https://")) {
                try {
                    return new ResolvedSchemaSource(remoteSchemaCache.fetch(resolvedString), resolvedString);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while fetching schema: " + resolvedString, exception);
                }
            }
            if ("file".equalsIgnoreCase(resolved.getScheme())) {
                Path resolvedPath = Path.of(resolved).toAbsolutePath().normalize();
                if (Files.exists(resolvedPath)) {
                    return new ResolvedSchemaSource(resolvedPath, resolvedPath.toUri().toString());
                }
            }
        }

        Path resolvedPath = baseSource.path().getParent().resolve(effectiveHref).normalize().toAbsolutePath();
        if (!Files.exists(resolvedPath)) {
            throw new IOException("Could not resolve included schema reference '" + effectiveHref + "' from " + baseSource.systemId());
        }
        return new ResolvedSchemaSource(resolvedPath, resolvedPath.toUri().toString());
    }

    /**
     * Compiles and memoizes the SchXslt-generated validator stylesheet for a prepared schema.
     */
    synchronized XsltExecutable getValidator(Path schemaPath) throws SaxonApiException {
        Path normalizedSchemaPath = schemaPath.toAbsolutePath().normalize();
        if (validators.containsKey(normalizedSchemaPath)) {
            return validators.get(normalizedSchemaPath);
        }
        DocumentBuilder builder = processor.newDocumentBuilder();
        XdmNode schematron = builder.build(new StreamSource(normalizedSchemaPath.toFile()));
        XdmDestination stylesheetDestination = new XdmDestination();
        Xslt30Transformer transformer = transpiler.load30();
        transformer.transform(schematron.asSource(), stylesheetDestination);

        XsltCompiler compiler = processor.newXsltCompiler();
        XsltExecutable validator = compiler.compile(stylesheetDestination.getXdmNode().asSource());
        validators.put(normalizedSchemaPath, validator);
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

    private static void ensureSupportedQueryBinding(Document document, Path schemaPath) throws IOException {
        String queryBinding = document.getDocumentElement().getAttribute("queryBinding");
        if (queryBinding == null || queryBinding.isBlank()) {
            return;
        }
        String normalized = queryBinding.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("xslt") || normalized.equals("xslt2") || normalized.equals("xslt3")) {
            return;
        }
        throw new IOException(
                "Unsupported Schematron queryBinding '" + queryBinding + "' in " + schemaPath
                        + ". Supported values are xslt, xslt2, xslt3, or an omitted queryBinding.");
    }

    private static Document parseDocument(Path path) throws ParserConfigurationException, IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            var builder = documentBuilderFactory().newDocumentBuilder();
            return builder.parse(stream);
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
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException ignored) {
        }
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
        }
    }

    int cachedPreparedSchemaCount() {
        return preparedSchemas.size();
    }

    int cachedValidatorCount() {
        return validators.size();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class ExtractionState {
        private final Document document;
        private final Element root;
        private final Set<String> seenNamespaces = new HashSet<>();
        private final Set<String> visitedSystemIds = new HashSet<>();
        private boolean foundPattern;

        private ExtractionState(Document document, Element root) {
            this.document = document;
            this.root = root;
        }

        private boolean markVisited(String systemId) {
            return visitedSystemIds.add(systemId);
        }

        private void appendNamespace(Element namespace) {
            String prefix = namespace.getAttribute("prefix");
            String uri = namespace.getAttribute("uri");
            String key = prefix + "\n" + uri;
            if (prefix.isBlank() || !seenNamespaces.add(key)) {
                return;
            }
            root.appendChild(document.importNode(namespace, true));
        }

        private void appendPattern(Element pattern) {
            foundPattern = true;
            root.appendChild(document.importNode(pattern, true));
        }

        private boolean foundPattern() {
            return foundPattern;
        }
    }
}
