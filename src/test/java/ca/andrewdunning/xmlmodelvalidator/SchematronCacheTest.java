package ca.andrewdunning.xmlmodelvalidator;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XsltExecutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SchematronCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cachesPreparedEmbeddedSchematronExtraction() throws Exception {
        Path schema = write("schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0"
                         xmlns:sch="http://purl.oclc.org/dsdl/schematron">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                  <sch:pattern>
                    <sch:rule context="root">
                      <sch:assert test="@ok">root must be marked ok</sch:assert>
                    </sch:rule>
                  </sch:pattern>
                </grammar>
                """);
        SchematronCache cache = new SchematronCache(new Processor(false));

        ResolvedSchemaSource source = new ResolvedSchemaSource(schema, schema.toUri().toString());
        Path first = cache.prepare(source);
        Path second = cache.prepare(source);

        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(1, cache.cachedPreparedSchemaCount());
    }

    @Test
    void cachesSchemasWithoutEmbeddedSchematronAsMissing() throws Exception {
        Path schema = write("schema.rng", """
                <grammar xmlns="http://relaxng.org/ns/structure/1.0">
                  <start>
                    <element name="root">
                      <empty/>
                    </element>
                  </start>
                </grammar>
                """);
        SchematronCache cache = new SchematronCache(new Processor(false));

        ResolvedSchemaSource source = new ResolvedSchemaSource(schema, schema.toUri().toString());
        Path first = cache.prepare(source);
        Path second = cache.prepare(source);

        assertNull(first);
        assertNull(second);
        assertEquals(1, cache.cachedPreparedSchemaCount());
    }

    @Test
    void convertsCompactSyntaxBeforeExtractingEmbeddedSchematron() throws Exception {
        Path schema = write("schema.rnc", """
                namespace sch = "http://purl.oclc.org/dsdl/schematron"
                start = element root { empty }
                  >> sch:pattern [
                       sch:rule [
                         context = "root"
                         sch:assert [
                           test = "@id"
                           "root must have an id"
                         ]
                       ]
                     ]
                """);
        SchematronCache cache = new SchematronCache(new Processor(false));

        Path prepared = cache.prepare(new ResolvedSchemaSource(schema, schema.toUri().toString()));

        assertNotNull(prepared);
        assertEquals(".sch", prepared.getFileName().toString().substring(prepared.getFileName().toString().length() - 4));
    }

    @Test
    void cachesCompiledSchematronValidator() throws Exception {
        Path schema = write("rules.sch", """
                <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
                  <pattern>
                    <rule context="root">
                      <assert test="@ok">root must be marked ok</assert>
                    </rule>
                  </pattern>
                </schema>
                """);
        SchematronCache cache = new SchematronCache(new Processor(false));

        XsltExecutable first = cache.getValidator(schema);
        XsltExecutable second = cache.getValidator(schema);

        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(1, cache.cachedValidatorCount());
    }

    private Path write(String filename, String content) throws Exception {
        Path file = temporaryDirectory.resolve(filename);
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        return file;
    }
}
