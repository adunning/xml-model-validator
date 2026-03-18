package ca.andrewdunning.xmlmodelvalidator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchemaResolutionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesUsingSchemaAlias() throws Exception {
        Path aliasedSchema = write("schemas/local.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <?xml-model href="https://example.com/schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """);

        XmlFileValidator validator = new XmlFileValidator(Map.of("https://example.com/schema.rng", aliasedSchema));
        ValidationResult result = validator.validate(xml);

        assertTrue(result.ok(), "Expected alias-mapped schema validation to pass");
    }

    @Test
    void validatesUsingRemoteSchema() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/schema.rng", exchange -> {
                byte[] body = """
                        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                          <start>
                            <element name="root">
                              <empty/>
                            </element>
                          </start>
                        </grammar>
                        """.stripIndent().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(body);
                }
            });
            server.start();

            Path xml = write("document.xml", """
                    <?xml version="1.0"?>
                    <?xml-model href="%s" schematypens="http://relaxng.org/ns/structure/1.0"?>
                    <root/>
                    """.formatted("http://127.0.0.1:" + server.getAddress().getPort() + "/schema.rng"));

            ValidationResult result = new XmlFileValidator(Map.of()).validate(xml);

            assertTrue(result.ok(), "Expected remote schema validation to pass");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsRemoteSchemaHttpFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/missing.rng", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();

            Path xml = write("document.xml", """
                <?xml version="1.0"?>
                <?xml-model href="%s" schematypens="http://relaxng.org/ns/structure/1.0"?>
                <root/>
                """.formatted("http://127.0.0.1:" + server.getAddress().getPort() + "/missing.rng"));

            ValidationResult result = new XmlFileValidator(Map.of()).validate(xml);

            assertFalse(result.ok(), "Expected a remote schema fetch failure");
            assertTrue(
                result.issues().stream().anyMatch(issue ->
                    issue.message().contains("Could not fetch remote schema URL")
                        || issue.message().contains("HTTP 404")
                )
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void detectsSchematronByTypeHint() throws Exception {
        write("rules.xml", """
                <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
                  <pattern>
                    <rule context="root">
                      <assert test="child">root must have a child</assert>
                    </rule>
                  </pattern>
                </schema>
                """);
        Path xml = write("document.xml",
                """
                        <?xml version="1.0"?>
                        <?xml-model href="rules.xml" type="application/xml" schematypens="http://purl.oclc.org/dsdl/schematron"?>
                        <root/>
                        """);

        ValidationResult result = new XmlFileValidator(Map.of()).validate(xml);

        assertFalse(result.ok(), "Expected Schematron detection by schema namespace to fail validation");
        assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("root must have a child")));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}
