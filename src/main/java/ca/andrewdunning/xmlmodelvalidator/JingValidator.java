package ca.andrewdunning.xmlmodelvalidator;

import com.thaiopensource.validate.Schema;
import com.thaiopensource.validate.SchemaReader;
import com.thaiopensource.util.PropertyMapBuilder;
import com.thaiopensource.validate.ValidateProperty;
import com.thaiopensource.validate.Validator;
import com.thaiopensource.validate.auto.AutoSchemaReader;
import com.thaiopensource.validate.rng.CompactSchemaReader;
import com.thaiopensource.validate.rng.SAXSchemaReader;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates documents with Jing and converts its diagnostics into structured issues.
 */
final class JingValidator {
    private static final Pattern LEADING_SEVERITY_PATTERN = Pattern.compile("^(?i)(error|warning):\\s*");
    private static final Pattern QUOTED_TOKEN_PATTERN = Pattern.compile("\"([^\"\\r\\n]+)\"");
    private static final SecureXmlReaderPool XML_READERS = new SecureXmlReaderPool();

    private final Map<Path, Schema> schemaCache = new HashMap<>();

    /**
     * Runs Jing through its embedded validation API, caching compiled schemas and
     * creating a fresh validator per document.
     */
    List<ValidationIssue> validate(Path schema, Path xmlFile) throws Exception {
        Path normalizedSchema = schema.toAbsolutePath().normalize();
        CollectingErrorHandler errorHandler = new CollectingErrorHandler(xmlFile);
        Schema compiledSchema = loadSchema(normalizedSchema, errorHandler);
        Validator validator = compiledSchema.createValidator(instanceProperties(errorHandler));
        XMLReader reader = XML_READERS.reader();
        reader.setContentHandler(validator.getContentHandler());
        reader.setDTDHandler(validator.getDTDHandler());
        reader.setErrorHandler(errorHandler);
        reader.parse(asInputSource(xmlFile));
        if (errorHandler.hasErrors()) {
            return errorHandler.finish("Relax NG validation failed for " + xmlFile);
        }
        return errorHandler.finish(null);
    }

    private synchronized Schema loadSchema(Path schemaPath, ErrorHandler errorHandler) throws Exception {
        Schema cached = schemaCache.get(schemaPath);
        if (cached != null) {
            return cached;
        }
        SchemaReader schemaReader = schemaReaderFor(schemaPath);
        Schema loaded = schemaReader.createSchema(asInputSource(schemaPath), schemaProperties(errorHandler));
        schemaCache.put(schemaPath, loaded);
        return loaded;
    }

    private static SchemaReader schemaReaderFor(Path schemaPath) {
        String filename = schemaPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (filename.endsWith(".rnc")) {
            return CompactSchemaReader.getInstance();
        }
        if (filename.endsWith(".rng")) {
            return SAXSchemaReader.getInstance();
        }
        return new AutoSchemaReader();
    }

    private static com.thaiopensource.util.PropertyMap schemaProperties(ErrorHandler errorHandler) {
        PropertyMapBuilder properties = new PropertyMapBuilder();
        properties.put(ValidateProperty.ERROR_HANDLER, errorHandler);
        return properties.toPropertyMap();
    }

    private static com.thaiopensource.util.PropertyMap instanceProperties(ErrorHandler errorHandler) {
        PropertyMapBuilder properties = new PropertyMapBuilder();
        properties.put(ValidateProperty.ERROR_HANDLER, errorHandler);
        return properties.toPropertyMap();
    }

    private static InputSource asInputSource(Path path) {
        InputSource source = new InputSource(path.toUri().toString());
        source.setSystemId(path.toUri().toString());
        return source;
    }

    static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String withoutSeverity = LEADING_SEVERITY_PATTERN.matcher(message).replaceFirst("");
        return QUOTED_TOKEN_PATTERN.matcher(withoutSeverity).replaceAll("`$1`");
    }

    private static final class CollectingErrorHandler implements ErrorHandler {
        private final Path xmlFile;
        private final List<ValidationIssue> issues = new ArrayList<>();
        private boolean errorSeen;

        private CollectingErrorHandler(Path xmlFile) {
            this.xmlFile = xmlFile;
        }

        @Override
        public void warning(SAXParseException exception) {
            issues.add(issueFrom(exception, true));
        }

        @Override
        public void error(SAXParseException exception) {
            errorSeen = true;
            issues.add(issueFrom(exception, false));
        }

        @Override
        public void fatalError(SAXParseException exception) {
            errorSeen = true;
            issues.add(issueFrom(exception, false));
        }

        private boolean hasErrors() {
            return errorSeen;
        }

        private List<ValidationIssue> finish(String fallbackMessage) {
            if (issues.isEmpty() && fallbackMessage != null && !fallbackMessage.isBlank()) {
                return List.of(new ValidationIssue(xmlFile, fallbackMessage, null, null, false));
            }
            return List.copyOf(issues);
        }

        private ValidationIssue issueFrom(SAXParseException exception, boolean warning) {
            Integer line = exception.getLineNumber() > 0 ? exception.getLineNumber() : null;
            Integer column = exception.getColumnNumber() > 0 ? exception.getColumnNumber() : null;
            return new ValidationIssue(xmlFile, normalizeMessage(exception.getMessage()), line, column, warning);
        }
    }

    int cachedSchemaCount() {
        return schemaCache.size();
    }
}
