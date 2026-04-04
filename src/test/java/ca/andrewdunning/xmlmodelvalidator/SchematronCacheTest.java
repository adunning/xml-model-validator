package ca.andrewdunning.xmlmodelvalidator;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XsltExecutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    XsltExecutable first = cache.getValidator(schema, null);
    XsltExecutable second = cache.getValidator(schema, null);

    assertNotNull(first);
    assertEquals(first, second);
    assertEquals(1, cache.cachedValidatorCount());
  }

  @Test
  void cachesDifferentExecutablesForDifferentPhases() throws Exception {
    Path schema = write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <phase id="strict">
            <active pattern="strict-pattern"/>
          </phase>
          <pattern id="strict-pattern">
            <rule context="root">
              <assert test="@id">root must have an id</assert>
            </rule>
          </pattern>
        </schema>
        """);
    SchematronCache cache = new SchematronCache(new Processor(false));

    XsltExecutable defaultValidator = cache.getValidator(schema, null);
    XsltExecutable strictValidator = cache.getValidator(schema, "strict");

    assertNotNull(defaultValidator);
    assertNotNull(strictValidator);
    assertNotEquals(defaultValidator, strictValidator, "Expected different compiled executables per phase");
    assertEquals(2, cache.cachedValidatorCount());
  }

  @Test
  void selectsAnyPhaseFromDocument() throws Exception {
    // The create-phase-selector mode generates a stylesheet whose template
    // evaluates
    // each sch:phase/@when expression against the document to pick the first
    // matching
    // phase, falling back to #ALL when none match.
    Path schema = write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <phase id="strict" when="//root/@mode = 'strict'">
            <active pattern="strict-pattern"/>
          </phase>
          <pattern id="strict-pattern">
            <rule context="root">
              <assert test="@id">root must have an id</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path strictDocument = write("strict.xml", """
        <?xml version="1.0"?>
        <root mode="strict"/>
        """);
    Path lenientDocument = write("lenient.xml", """
        <?xml version="1.0"?>
        <root mode="lenient"/>
        """);
    SchematronCache cache = new SchematronCache(new Processor(false));

    String strictPhase = cache.selectAnyPhase(schema, strictDocument);
    String lenientPhase = cache.selectAnyPhase(schema, lenientDocument);

    assertEquals("strict", strictPhase, "Expected @when condition to select the strict phase");
    assertEquals("#ALL", lenientPhase, "Expected no matching @when to fall back to #ALL");
  }

  @Test
  void rejectsInvalidSchematronSchemaWhenStrictChecksAreEnabled() throws Exception {
    Path schema = write("invalid.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <phase id="broken">
            <active pattern="missing-pattern"/>
          </phase>
        </schema>
        """);
    SchematronCache cache = new SchematronCache(new Processor(false), true);

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> cache.getValidator(schema, null));

    assertTrue(exception.getMessage().contains("Invalid Schematron schema"));
  }

  private Path write(String filename, String content) throws Exception {
    Path file = temporaryDirectory.resolve(filename);
    Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
    return file;
  }
}
