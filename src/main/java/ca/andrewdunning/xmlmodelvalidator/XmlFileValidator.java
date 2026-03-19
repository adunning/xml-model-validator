package ca.andrewdunning.xmlmodelvalidator;

import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmSequenceIterator;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltExecutable;
import org.xml.sax.InputSource;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a single XML file by applying supported {@code xml-model}
 * declarations first and falling
 * back to XML Schema instance hints when no supported model is present.
 */
final class XmlFileValidator {
    private static final Pattern QUOTED_TOKEN_PATTERN = Pattern.compile("\"([^\"\\r\\n]+)\"");

    private final Processor processor;
    private final XPathCompiler svrlXPathCompiler;
    private final XmlModelParser xmlModelParser;
    private final XmlSchemaHintParser xmlSchemaHintParser;
    private final SchematronCache schematronCache;
    private final JingRunner jingRunner;
    private final SchemaResolver schemaResolver;
    private final XsdValidator xsdValidator;

    XmlFileValidator(Map<String, Path> schemaAliases) {
        this.processor = new Processor(false);
        this.svrlXPathCompiler = processor.newXPathCompiler();
        this.svrlXPathCompiler.declareNamespace("svrl", ValidationSupport.SVRL_NS);
        this.xmlModelParser = new XmlModelParser();
        this.xmlSchemaHintParser = new XmlSchemaHintParser();
        this.schematronCache = new SchematronCache(processor);
        this.jingRunner = new JingRunner();
        this.schemaResolver = new SchemaResolver(schemaAliases, new RemoteSchemaCache());
        this.xsdValidator = new XsdValidator(schemaResolver);
    }

    ValidationResult validate(Path file) {
        try {
            List<XmlModelEntry> entries = xmlModelParser.parse(file);
            ValidationIssue wellFormednessIssue = validateWellFormedness(file);
            if (wellFormednessIssue != null) {
                return ValidationResult.failed(file, wellFormednessIssue);
            }
            List<ResolvedSchema> relaxNgSchemas = resolveSchemas(entries, file, SchemaKind.RELAX_NG);
            List<ResolvedSchema> schematronSchemas = resolveSchemas(entries, file, SchemaKind.SCHEMATRON);

            if (relaxNgSchemas.isEmpty() && schematronSchemas.isEmpty()) {
                // XSD hints are intentionally a fallback so xml-model stays the primary control
                // surface.
                List<String> xsdSchemaLocations = xmlSchemaHintParser.parse(file);
                if (!xsdSchemaLocations.isEmpty()) {
                    List<ValidationIssue> issues = xsdValidator.validate(file, xsdSchemaLocations);
                    boolean hasErrors = issues.stream().anyMatch(issue -> !issue.warning());
                    return new ValidationResult(file, !hasErrors, issues);
                }
                return ValidationResult.failed(
                        file,
                        new ValidationIssue(
                                file,
                                "No supported xml-model entry found. Expected Relax NG or Schematron, or XSD instance hints.",
                                null,
                                null,
                                false));
            }

            List<ValidationIssue> issues = new ArrayList<>();
            for (ResolvedSchema schema : relaxNgSchemas) {
                issues.addAll(jingRunner.validate(schema.path(), file));
                if (schema.entry().supportsEmbeddedSchematron()) {
                    issues.addAll(validateSchematron(schema.path(), file, schema.entry().phase()));
                }
            }
            for (ResolvedSchema schema : schematronSchemas) {
                issues.addAll(validateSchematron(schema.path(), file, schema.entry().phase()));
            }

            boolean hasErrors = issues.stream().anyMatch(issue -> !issue.warning());
            return new ValidationResult(file, !hasErrors, issues);
        } catch (Exception exception) {
            return ValidationResult.failed(
                    file,
                    new ValidationIssue(file, "Validation error: " + exception.getMessage(), null, null, false));
        }
    }

    private ValidationIssue validateWellFormedness(Path file)
            throws ParserConfigurationException, SAXException, java.io.IOException {
        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLReader reader = createWellFormednessReader();
            InputSource inputSource = new InputSource(inputStream);
            inputSource.setSystemId(file.toUri().toString());
            reader.parse(inputSource);
            return null;
        } catch (SAXParseException exception) {
            Integer line = exception.getLineNumber() > 0 ? exception.getLineNumber() : null;
            Integer column = exception.getColumnNumber() > 0 ? exception.getColumnNumber() : null;
            return new ValidationIssue(file, formatWellFormednessMessage(exception.getMessage()), line, column, false);
        }
    }

    private String formatWellFormednessMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return QUOTED_TOKEN_PATTERN.matcher(message).replaceAll("`$1`");
    }

    private XMLReader createWellFormednessReader() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setContentHandler(new DefaultHandler());
        reader.setErrorHandler(new WellFormednessErrorHandler());
        return reader;
    }

    private void setFeature(SAXParserFactory factory, String feature, boolean value)
            throws ParserConfigurationException, SAXException {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | SAXException ignored) {
        }
    }

    private static final class WellFormednessErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException exception) {
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }

    /**
     * Resolves matching schemas once per file and phase combination so repeated
     * xml-model entries do
     * not trigger duplicate validations.
     */
    private List<ResolvedSchema> resolveSchemas(List<XmlModelEntry> entries, Path file, SchemaKind schemaKind) {
        List<ResolvedSchema> schemas = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (XmlModelEntry entry : entries) {
            if (!entry.matches(schemaKind)) {
                continue;
            }
            Path resolved = schemaResolver.resolve(entry.href(), file.getParent());
            String dedupeKey = resolved.toString() + "|" + entry.phase();
            if (seen.add(dedupeKey)) {
                schemas.add(new ResolvedSchema(resolved, entry));
            }
        }
        return schemas;
    }

    private List<ValidationIssue> validateSchematron(Path schemaPath, Path xmlFile, String phase) throws Exception {
        Path preparedSchema = schematronCache.prepare(schemaPath);
        if (preparedSchema == null) {
            return List.of();
        }

        XsltExecutable validator = schematronCache.getValidator(preparedSchema);
        DocumentBuilder builder = processor.newDocumentBuilder();
        builder.setLineNumbering(true);
        XdmNode source = builder.build(xmlFile.toFile());

        XdmDestination destination = new XdmDestination();
        Xslt30Transformer transformer = validator.load30();
        if (phase != null && !phase.isBlank()) {
            transformer.setStylesheetParameters(
                    Map.of(
                            new QName("schxslt", "http://dmaus.name/ns/2023/schxslt", "phase"),
                            new XdmAtomicValue(phase)));
        }
        transformer.transform(source.asSource(), destination);
        return parseSvrl(destination.getXdmNode(), source, xmlFile);
    }

    /**
     * Converts SchXslt SVRL output into validator issues with the best source
     * locations Saxon can
     * recover from the original document.
     */
    private List<ValidationIssue> parseSvrl(XdmNode svrl, XdmNode source, Path xmlFile) throws SaxonApiException {
        List<ValidationIssue> issues = new ArrayList<>();
        XPathCompiler compiler = processor.newXPathCompiler();
        compiler.declareNamespace("svrl", ValidationSupport.SVRL_NS);
        XPathSelector selector = compiler.compile("//svrl:failed-assert | //svrl:successful-report").load();
        selector.setContextItem(svrl);
        XdmSequenceIterator<XdmItem> iterator = selector.iterator();
        while (iterator.hasNext()) {
            XdmNode issueNode = (XdmNode) iterator.next();
            String role = attribute(issueNode, "role").toLowerCase(Locale.ROOT);
            boolean warning = role.equals("warn") || role.equals("warning") || role.equals("nonfatal");
            String location = attribute(issueNode, "location");
            Integer line = resolveLineNumber(source, svrl, location);
            String message = issueNode.getStringValue().trim();
            issues.add(new ValidationIssue(xmlFile, message, line, null, warning));
        }
        return issues;
    }

    private Integer resolveLineNumber(XdmNode source, XdmNode svrl, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            XPathCompiler compiler = processor.newXPathCompiler();
            XPathSelector nsSelector = svrlXPathCompiler
                    .compile("/svrl:schematron-output/svrl:ns-prefix-in-attribute-values").load();
            nsSelector.setContextItem(svrl);
            XdmSequenceIterator<XdmItem> namespaceIterator = nsSelector.iterator();
            while (namespaceIterator.hasNext()) {
                XdmNode nsNode = (XdmNode) namespaceIterator.next();
                String prefix = attribute(nsNode, "prefix");
                String uri = attribute(nsNode, "uri");
                if (!prefix.isBlank() && !uri.isBlank()) {
                    compiler.declareNamespace(prefix, uri);
                }
            }

            XPathSelector selector = compiler.compile(location).load();
            selector.setContextItem(source);
            XdmItem item = selector.evaluateSingle();
            if (item instanceof XdmNode node) {
                int line = node.getUnderlyingNode().getLineNumber();
                return line > 0 ? line : null;
            }
        } catch (SaxonApiException ignored) {
            return null;
        }
        return null;
    }

    private static String attribute(XdmNode node, String localName) {
        String value = node.getAttributeValue(new QName(localName));
        return value == null ? "" : value;
    }

    /**
     * Associates a resolved schema file with the xml-model entry that requested it.
     */
    private record ResolvedSchema(Path path, XmlModelEntry entry) {
    }
}
