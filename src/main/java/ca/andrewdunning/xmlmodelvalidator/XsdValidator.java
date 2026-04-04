package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/** Validates XML files against XML Schema definitions discovered from instance hints. */
final class XsdValidator {
    private final SchemaResolver schemaResolver;

    XsdValidator(SchemaResolver schemaResolver) {
        this.schemaResolver = schemaResolver;
    }

    /** Validates the document against the provided XSD locations and returns all reported issues. */
    List<ValidationIssue> validate(Path xmlFile, List<String> schemaLocations) throws Exception {
        if (schemaLocations.isEmpty()) {
            return List.of();
        }

        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        schemaFactory.setResourceResolver(new Resolver(xmlFile.getParent(), schemaResolver));

        try (OpenedSources openedSources = new OpenedSources()) {
            Source[] sources = new Source[schemaLocations.size()];
            for (int index = 0; index < schemaLocations.size(); index += 1) {
                ResolvedSchemaSource resolved =
                        schemaResolver.resolveSource(schemaLocations.get(index), xmlFile.getParent());
                StreamSource source = openedSources.open(resolved);
                sources[index] = source;
            }

            Validator validator = schemaFactory.newSchema(sources).newValidator();
            CollectingErrorHandler errorHandler = new CollectingErrorHandler(xmlFile);
            validator.setErrorHandler(errorHandler);
            try (InputStream inputStream = Files.newInputStream(xmlFile)) {
                StreamSource xmlSource = new StreamSource(inputStream);
                xmlSource.setSystemId(xmlFile.toUri().toString());
                validator.validate(xmlSource);
            } catch (SAXParseException exception) {
                errorHandler.error(exception);
            }
            return errorHandler.issues();
        }
    }

    /** Collects recoverable and fatal XSD validation diagnostics into a single issue list. */
    private static final class CollectingErrorHandler implements ErrorHandler {
        private final Path xmlFile;
        private final List<ValidationIssue> issues;

        private CollectingErrorHandler(Path xmlFile) {
            this.xmlFile = xmlFile;
            this.issues = new ArrayList<>();
        }

        @Override
        public void warning(SAXParseException exception) {
            issues.add(issueFrom(exception, true));
        }

        @Override
        public void error(SAXParseException exception) {
            issues.add(issueFrom(exception, false));
        }

        @Override
        public void fatalError(SAXParseException exception) {
            issues.add(issueFrom(exception, false));
        }

        private ValidationIssue issueFrom(SAXParseException exception, boolean warning) {
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
            return new ValidationIssue(xmlFile, exception.getMessage(), line, column, warning);
        }

        private List<ValidationIssue> issues() {
            return issues;
        }
    }

    /** Resolves imported XSD resources through the same alias/local/remote logic used elsewhere in the validator. */
    private static final class Resolver implements LSResourceResolver {
        private final Path fallbackBaseDirectory;
        private final SchemaResolver schemaResolver;

        private Resolver(Path fallbackBaseDirectory, SchemaResolver schemaResolver) {
            this.fallbackBaseDirectory = fallbackBaseDirectory;
            this.schemaResolver = schemaResolver;
        }

        @Override
        public LSInput resolveResource(
                String type, String namespaceURI, String publicId, String systemId, String baseURI) {
            if (systemId == null || systemId.isBlank()) {
                return null;
            }

            ResolvedSchemaSource resolved =
                    schemaResolver.resolveRelativeToSystemId(systemId, baseURI, fallbackBaseDirectory);
            try {
                return new Input(resolved);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not resolve imported XSD resource: " + systemId, exception);
            }
        }
    }

    /** Minimal LSInput backed by an already-resolved schema file on disk. */
    private static final class Input implements LSInput {
        private final InputStream byteStream;
        private final String publicId;
        private final String systemId;
        private final String baseUri;

        private Input(ResolvedSchemaSource resolved) throws IOException {
            this.byteStream = Files.newInputStream(resolved.path());
            this.publicId = null;
            this.systemId = resolved.systemId();
            this.baseUri = resolved.systemId();
        }

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {}

        @Override
        public InputStream getByteStream() {
            return byteStream;
        }

        @Override
        public void setByteStream(InputStream byteStream) {}

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(String stringData) {}

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {}

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(String publicId) {}

        @Override
        public String getBaseURI() {
            return baseUri;
        }

        @Override
        public void setBaseURI(String baseURI) {}

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public void setEncoding(String encoding) {}

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {}
    }

    /** Tracks schema streams so they are closed deterministically after schema compilation. */
    private static final class OpenedSources implements AutoCloseable {
        private final List<InputStream> streams = new ArrayList<>();

        private StreamSource open(ResolvedSchemaSource resolved) throws IOException {
            InputStream stream = Files.newInputStream(resolved.path());
            streams.add(stream);
            StreamSource source = new StreamSource(stream);
            source.setSystemId(resolved.systemId());
            return source;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (InputStream stream : streams.reversed()) {
                try {
                    stream.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
