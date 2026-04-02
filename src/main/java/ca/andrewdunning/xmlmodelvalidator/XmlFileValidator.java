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
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltExecutable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validates a single XML file by applying supported {@code xml-model}
 * declarations first and falling
 * back to XML Schema instance hints when no supported model is present.
 */
final class XmlFileValidator {
    private final Processor processor;
    private final XPathCompiler svrlXPathCompiler;
    private final XPathExecutable svrlIssueSelector;
    private final XPathExecutable svrlNamespaceSelector;
    private final Map<String, XPathExecutable> locationXPathCache;
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
        try {
            this.svrlIssueSelector = svrlXPathCompiler.compile("//svrl:failed-assert | //svrl:successful-report");
            this.svrlNamespaceSelector = svrlXPathCompiler
                    .compile("/svrl:schematron-output/svrl:ns-prefix-in-attribute-values");
        } catch (SaxonApiException exception) {
            throw new IllegalStateException("Could not compile cached SVRL XPath expressions", exception);
        }
        this.locationXPathCache = new ConcurrentHashMap<>();
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
                issues.addAll(jingValidator.validate(schema.source(), file));
                if (schema.entry().supportsEmbeddedSchematron()) {
                    issues.addAll(validateSchematron(schema.source(), file, schema.entry().phase()));
                }
            }
            for (ResolvedSchema schema : schematronSchemas) {
                issues.addAll(validateSchematron(schema.source(), file, schema.entry().phase()));
            }

            boolean hasErrors = issues.stream().anyMatch(issue -> !issue.warning());
            return new ValidationResult(file, !hasErrors, issues);
        } catch (IllegalArgumentException exception) {
            return ValidationResult.failed(
                    file,
                    new ValidationIssue(file, exception.getMessage(), null, null, false));
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
            ResolvedSchemaSource resolved = schemaResolver.resolveSource(entry.href(), file.getParent());
            String dedupeKey = resolved.systemId() + "|" + entry.phase();
            if (seen.add(dedupeKey)) {
                schemas.add(new ResolvedSchema(resolved, entry));
            }
        }
        return schemas;
    }

    private List<XmlModelEntry> resolveXmlModelEntries(Path file, List<XmlModelEntry> inlineEntries) {
        XmlModelRule matchingRule = findXmlModelRule(file);
        if (matchingRule == null) {
            return inlineEntries;
        }
        if (matchingRule.mode() == XmlModelRuleMode.REPLACE) {
            return matchingRule.entries();
        }
        if (inlineEntries.isEmpty()) {
            return matchingRule.entries();
        }
        return inlineEntries;
    }

    private XmlModelRule findXmlModelRule(Path file) {
        List<XmlModelRule> bestRules = new ArrayList<>();
        int bestSpecificity = Integer.MIN_VALUE;
        int bestPriority = Integer.MIN_VALUE;
        for (XmlModelRule rule : xmlModelRules) {
            if (!rule.matches(file)) {
                continue;
            }
            int specificity = rule.specificity();
            if (specificity > bestSpecificity
                    || (specificity == bestSpecificity && rule.priority() > bestPriority)) {
                bestRules = new ArrayList<>(List.of(rule));
                bestSpecificity = specificity;
                bestPriority = rule.priority();
                continue;
            }
            if (specificity == bestSpecificity
                    && rule.priority() == bestPriority) {
                bestRules.add(rule);
            }
        }
        if (bestRules.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous xml-model rules match "
                            + file
                            + ". "
                            + bestRules.size()
                            + " rules tie at specificity "
                            + bestSpecificity
                            + " and priority "
                            + bestPriority
                            + ": "
                            + bestRules.stream()
                                    .map(rule -> "[" + rule.describe() + "]")
                                    .collect(Collectors.joining(", ")));
        }
        return bestRules.isEmpty() ? null : bestRules.getFirst();
    }

    private List<ValidationIssue> validateSchematron(ResolvedSchemaSource schemaSource, Path xmlFile, String phase) throws Exception {
        Path preparedSchema = schematronCache.prepare(schemaSource);
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
        Map<String, String> namespaceBindings = namespaceBindings(svrl);
        XPathSelector selector = svrlIssueSelector.load();
        selector.setContextItem(svrl);
        XdmSequenceIterator<XdmItem> iterator = selector.iterator();
        while (iterator.hasNext()) {
            XdmNode issueNode = (XdmNode) iterator.next();
            boolean warning = isWarning(issueNode);
            String location = attribute(issueNode, "location");
            Integer line = resolveLineNumber(source, namespaceBindings, location);
            String message = issueNode.getStringValue().trim();
            issues.add(new ValidationIssue(xmlFile, message, line, null, warning));
        }
        return issues;
    }

    private Map<String, String> namespaceBindings(XdmNode svrl) throws SaxonApiException {
        Map<String, String> bindings = new HashMap<>();
        XPathSelector selector = svrlNamespaceSelector.load();
        selector.setContextItem(svrl);
        XdmSequenceIterator<XdmItem> namespaceIterator = selector.iterator();
        while (namespaceIterator.hasNext()) {
            XdmNode nsNode = (XdmNode) namespaceIterator.next();
            String prefix = attribute(nsNode, "prefix");
            String uri = attribute(nsNode, "uri");
            if (!prefix.isBlank() && !uri.isBlank()) {
                bindings.put(prefix, uri);
            }
        }
        return Map.copyOf(bindings);
    }

    private Integer resolveLineNumber(XdmNode source, Map<String, String> namespaceBindings, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            XPathSelector selector = compiledLocationXPath(namespaceBindings, location).load();
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

    private XPathExecutable compiledLocationXPath(Map<String, String> namespaceBindings, String location)
            throws SaxonApiException {
        String cacheKey = locationCacheKey(namespaceBindings, location);
        XPathExecutable cached = locationXPathCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        XPathCompiler compiler = processor.newXPathCompiler();
        namespaceBindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> compiler.declareNamespace(entry.getKey(), entry.getValue()));
        XPathExecutable compiled = compiler.compile(location);
        locationXPathCache.put(cacheKey, compiled);
        return compiled;
    }

    private static String locationCacheKey(Map<String, String> namespaceBindings, String location) {
        StringBuilder key = new StringBuilder(location);
        namespaceBindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> key.append('\n').append(entry.getKey()).append('=').append(entry.getValue()));
        return key.toString();
    }

    private static String attribute(XdmNode node, String localName) {
        String value = node.getAttributeValue(new QName(localName));
        return value == null ? "" : value;
    }

    private static boolean isWarning(XdmNode issueNode) {
        String severity = attribute(issueNode, "severity").toLowerCase(Locale.ROOT);
        switch (severity) {
            case "info", "warning" -> {
                return true;
            }
            case "error", "fatal" -> {
                return false;
            }
            default -> {
            }
        }

        String role = attribute(issueNode, "role").toLowerCase(Locale.ROOT);
        return switch (role) {
            case "info", "warn", "warning", "nonfatal" -> true;
            case "error", "fatal" -> false;
            default -> issueNode.getNodeName().getLocalName().equals("successful-report");
        };
    }

    int cachedLocationXPathCount() {
        return locationXPathCache.size();
    }

    /**
     * Associates a resolved schema file with the xml-model entry that requested it.
     */
    private record ResolvedSchema(ResolvedSchemaSource source, XmlModelEntry entry) {
    }
}
