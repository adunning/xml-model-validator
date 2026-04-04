package ca.andrewdunning.xmlmodelvalidator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.transform.stream.StreamSource;

/** Represents a schema file together with the system identifier that should be exposed to downstream XML tooling. */
record ResolvedSchemaSource(Path path, String systemId) {
    StreamSource openStreamSource() throws java.io.IOException {
        InputStream inputStream = Files.newInputStream(path);
        StreamSource source = new StreamSource(inputStream);
        source.setSystemId(systemId);
        return source;
    }
}
