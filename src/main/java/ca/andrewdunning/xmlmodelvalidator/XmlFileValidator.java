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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a single XML file by applying supported {@code xml-model}
 * declarations first and falling
 * back to XML Schema instance hints when no supported model is present.
 */
final class XmlFileValidator {
    private final Processor processor;
    private final XPathCompiler svrlXPathCompiler;
    private final XmlDocumentScanner xmlDocumentScanner;
    private final SchematronCache schematronCache;
    private final JingValidator jingValidator;
    private final SchemaResolver schemaResolver;
    private final XsdValidator xsdValidator;
    private final List<XmlModelRule> xmlModelRules;

    XmlFileValidator(Map<String, Path> schemaAliases) {
        this(schemaAliases, List.of());
    }

    XmlFileValidator(Map<String, Path> schemaAliases, List<XmlModelRule> xmlModelRules) {
        this.processor = new Processor(false);
        this.svrlXPathCompiler = processor.newXPathCompiler();
        this.svrlXPathCompiler.declareNamespace("svrl", ValidationSupport.SVRL_NS);
        this.xmlDocumentScanner = new XmlDocumentScanner();
        this.schematronCache = new SchematronCache(processor);
        this.jingValidator = new JingValidator();
        this.schemaResolver = new SchemaResolver(schemaAliases, new RemoteSchemaCache());
        this.xsdValidator = new XsdValidator(schemaResolver);
        this.xmlModelRules = List.copyOf(xmlModelRules);
    }

    ValidationResult validate(Path file) {
        try {
            XmlDocumentScan scan = xmlDocumentScanner.scan(file);
            if (scan.wellFormednessIssue() != null) {
                return ValidationResult.failed(file, scan.wellFormednessIssue());
            }
            List<XmlModelEntry> entries = resolveXmlModelEntries(file, scan.xmlModelEntries());
            List<ResolvedSchema> relaxNgSchemas = resolveSchemas(entries, file, SchemaKind.RELAX_NG);
            List<ResolvedSchema> schematronSchemas = resolveSchemas(entries, file, SchemaKind.SCHEMATRON);

            if (relaxNgSchemas.isEmpty() && schematronSchemas.isEmpty()) {
                // XSD hints are intentionally a fallback so xml-model stays the primary control
                // surface.
                List<String> xsdSchemaLocations = scan.schemaLocations();
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
                issues.addAll(jingValidator.validate(schema.path(), file));
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

    private List<XmlModelEntry> resolveXmlModelEntries(Path file, List<XmlModelEntry> inlineEntries) {
        Optional<XmlModelRule> matchingRule = findXmlModelRule(file);
        if (matchingRule.isEmpty()) {
            return inlineEntries;
        }
        XmlModelRule rule = matchingRule.get();
        if (rule.mode() == XmlModelRuleMode.REPLACE) {
            return rule.entries();
        }
        if (inlineEntries.isEmpty()) {
            return rule.entries();
        }
        return inlineEntries;
    }

    private Optional<XmlModelRule> findXmlModelRule(Path file) {
        XmlModelRule bestRule = null;
        int bestSpecificity = Integer.MIN_VALUE;
        int bestPriority = Integer.MIN_VALUE;
        for (XmlModelRule rule : xmlModelRules) {
            if (!rule.matches(file)) {
                continue;
            }
            int specificity = rule.specificity();
            if (specificity > bestSpecificity
                    || (specificity == bestSpecificity && rule.priority() > bestPriority)) {
                bestRule = rule;
                bestSpecificity = specificity;
                bestPriority = rule.priority();
                continue;
            }
            if (specificity == bestSpecificity
                    && rule.priority() == bestPriority
                    && bestRule != null) {
                throw new IllegalStateException(
                        "Ambiguous xml-model rules match "
                                + file
                                + ": ["
                                + bestRule.describe()
                                + "] and ["
                                + rule.describe()
                                + "]");
            }
        }
        return Optional.ofNullable(bestRule);
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
