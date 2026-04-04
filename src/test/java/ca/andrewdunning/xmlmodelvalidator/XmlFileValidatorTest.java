package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XmlFileValidatorTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void validatesRelaxNgXmlSyntax() throws Exception {
    write("schema.rng", """
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
        <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
        <root/>
        """);

    ValidationResult result = validator().validate(xml);

    assertTrue(result.ok(), "Expected Relax NG XML syntax validation to pass");
    assertTrue(result.issues().isEmpty(), "Expected no validation issues");
  }

  @Test
  void validatesRelaxNgCompactSyntax() throws Exception {
    write("schema.rnc", """
        start = element root { empty }
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="schema.rnc" schematypens="http://relaxng.org/ns/structure/1.0"?>
        <invalid/>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected Relax NG compact syntax validation to fail");
    assertFalse(result.issues().isEmpty(), "Expected at least one Relax NG compact syntax validation issue");
  }

  @Test
  void prefersRelaxNgCompactSyntaxWhenSchemaMetadataConflicts() throws Exception {
    write("schema.rnc", """
        start = element root { empty }
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="schema.rnc" schematypens="http://purl.oclc.org/dsdl/schematron"?>
        <root/>
        """);

    ValidationResult result = validator().validate(xml);

    assertTrue(result.ok(), "Expected .rnc declarations to prefer Relax NG validation over Schematron parsing");
    assertTrue(result.issues().isEmpty(), "Expected no Schematron parsing attempt for compact syntax");
  }

  @Test
  void validatesStandaloneSchematron() throws Exception {
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <pattern>
            <rule context="root">
              <assert test="child">root must have a child</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron"?>
        <root/>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected Schematron validation to fail");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("root must have a child")),
        "Expected the Schematron assertion message");
  }

  @Test
  void appliesSchematronSeverityThreshold() throws Exception {
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <pattern>
            <rule context="root">
              <assert test="false()" severity="warning">warning-level assertion</assert>
              <assert test="false()" severity="error">error-level assertion</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron"?>
        <root/>
        """);

    ValidationResult result = validator(false, SchematronSeverityLevel.ERROR).validate(xml);

    assertFalse(result.ok(), "Expected error-level assertions to remain active");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("error-level assertion")),
        "Expected error-severity assertion to be reported");
    assertFalse(result.issues().stream().anyMatch(issue -> issue.message().contains("warning-level assertion")),
        "Expected warning-severity assertion to be skipped by the threshold");
  }

  @Test
  void rejectsInvalidSchematronSchemaWhenStrictChecksAreEnabled() throws Exception {
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <phase id="broken">
            <active pattern="missing-pattern"/>
          </phase>
        </schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron"?>
        <root/>
        """);

    ValidationResult result = validator(true).validate(xml);

    assertFalse(result.ok(), "Expected strict Schematron schema checks to fail invalid schemas");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("Invalid Schematron schema")),
        "Expected the invalid-schema prefix to be reported");
    assertTrue(result.issues().stream().noneMatch(issue -> issue.message().contains("Validation error:")),
        "Expected strict schema failures to be reported directly");
  }

  @Test
  void cachesSchematronLocationXPathAcrossRepeatedValidations() throws Exception {
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <pattern>
            <rule context="root">
              <assert test="child">root must have a child</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron"?>
        <root/>
        """);
    XmlFileValidator validator = validator();

    validator.validate(xml);
    assertEquals(1, validator.cachedLocationXPathCount());

    validator.validate(xml);
    assertEquals(1, validator.cachedLocationXPathCount());
  }

  @Test
  void honorsAnyPhaseBySelectingPhaseFromDocument() throws Exception {
    // phase="#ANY" on an xml-model PI means the schema's sch:phase/@when
    // expressions are evaluated against the document to pick the effective phase
    // dynamically.
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <phase id="strict" when="//root/@mode = 'strict'">
            <active pattern="strict-pattern"/>
          </phase>
          <pattern id="first-pattern">
            <rule context="root">
              <assert test="first">first child required (all phases)</assert>
            </rule>
          </pattern>
          <pattern id="strict-pattern">
            <rule context="root">
              <assert test="second">second child required (strict)</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron" phase="#ANY"?>
        <root mode="strict"/>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected #ANY phase validation to fail");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("second child required")),
        "Expected the strict-phase assertion to run");
    assertFalse(result.issues().stream().anyMatch(issue -> issue.message().contains("first child required")),
        "Expected patterns not in the selected phase to be ignored");
  }

  @Test
  void honorsSchematronPhaseFromXmlModel() throws Exception {
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <phase id="phase-two">
            <active pattern="second-pattern"/>
          </phase>
          <pattern id="first-pattern">
            <rule context="root">
              <assert test="first">first child required</assert>
            </rule>
          </pattern>
          <pattern id="second-pattern">
            <rule context="root">
              <assert test="second">second child required</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron" phase="phase-two"?>
        <root><first/></root>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected phase-specific Schematron validation to fail");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("second child required")),
        "Expected the active phase assertion to run");
    assertFalse(result.issues().stream().anyMatch(issue -> issue.message().contains("first child required")),
        "Expected inactive phase patterns to be ignored");
  }

  @Test
  void rejectsUnsupportedSchematronQueryBinding() throws Exception {
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xpath2">
          <pattern>
            <rule context="root">
              <assert test="false()">unsupported binding</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron"?>
        <root/>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected an unsupported queryBinding to fail validation");
    assertTrue(
        result.issues().stream().anyMatch(issue -> issue.message().contains("Unsupported Schematron queryBinding")));
  }

  @Test
  void validatesEmbeddedSchematronInRelaxNgSchema() throws Exception {
    write("schema.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0"
                 xmlns:sch="http://purl.oclc.org/dsdl/schematron">
          <start>
            <element name="root">
              <zeroOrMore>
                <element name="item">
                  <text/>
                </element>
              </zeroOrMore>
            </element>
          </start>
          <sch:pattern>
            <sch:rule context="root">
              <sch:assert test="item">root must contain an item</sch:assert>
            </sch:rule>
          </sch:pattern>
        </grammar>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
        <root/>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected embedded Schematron validation to fail");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("root must contain an item")),
        "Expected the embedded Schematron assertion to run");
  }

  @Test
  void validatesEmbeddedSchematronInIncludedRelaxNgSchema() throws Exception {
    write("child.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0"
                 xmlns:sch="http://purl.oclc.org/dsdl/schematron">
          <start>
            <element name="root">
              <empty/>
            </element>
          </start>
          <sch:pattern>
            <sch:rule context="root">
              <sch:assert test="@id">root must have an id</sch:assert>
            </sch:rule>
          </sch:pattern>
        </grammar>
        """);
    write("schema.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
          <include href="child.rng"/>
        </grammar>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
        <root/>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected embedded Schematron in an included grammar to fail");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("root must have an id")),
        "Expected the included embedded Schematron assertion to run");
  }

  @Test
  void validatesEmbeddedSchematronInRelaxNgCompactSchema() throws Exception {
    write("child.rnc", """
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
    write("schema.rnc", """
        include "child.rnc"
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="schema.rnc" schematypens="http://relaxng.org/ns/structure/1.0"?>
        <root/>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected embedded Schematron in compact syntax to fail");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("root must have an id")),
        "Expected the embedded Schematron assertion to run");
  }

  @Test
  void reportsMalformedXmlUsingSchemaValidatorAfterReadingXmlModel() throws Exception {
    write("schema.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
          <start>
            <element name="root">
              <zeroOrMore>
                <element name="child">
                  <empty/>
                </element>
              </zeroOrMore>
            </element>
          </start>
        </grammar>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="schema.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
        <root><child></root>
        """);

    ValidationResult result = validator().validate(xml);

    assertFalse(result.ok(), "Expected malformed XML to fail validation");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().toLowerCase().contains("mismatch")
        || issue.message().toLowerCase().contains("must be terminated")
        || issue.message().toLowerCase().contains("well-formed")),
        "Expected a parser-level XML syntax error from schema validation");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("`")
        && !issue.message().contains("\"")),
        "Expected malformed XML messages to format quoted tokens with backticks");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.line() != null),
        "Expected malformed XML diagnostics to include a source line");
    assertTrue(result.issues().stream().noneMatch(issue -> issue.message().contains("Validation error:")),
        "Expected malformed XML to be reported directly, not wrapped in a generic validation error");
    assertEquals(1, result.issues().size(), "Expected malformed XML to stop further schema validation");
    assertNotNull(result.issues().getFirst());
  }

  @Test
  void validatesWithFallbackXmlModelRuleWhenInlineDeclarationIsMissing() throws Exception {
    write("styles/schema.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
          <start>
            <element name="root">
              <empty/>
            </element>
          </start>
        </grammar>
        """);
    Path xml = write("styles/document.csl", """
        <?xml version="1.0"?>
        <root/>
        """);

    ValidationResult result = validator(List.of(
        new XmlModelRule(
            temporaryDirectory.resolve("styles"),
            ".csl",
            XmlModelRuleMode.FALLBACK,
            List.of(new XmlModelEntry(
                temporaryDirectory.resolve("styles/schema.rng").toString(),
                ValidationSupport.RELAXNG_NS,
                null,
                null)))))
        .validate(xml);

    assertTrue(result.ok(), "Expected fallback xml-model rule to validate the file");
    assertTrue(result.issues().isEmpty(), "Expected no validation issues from the fallback rule");
  }

  @Test
  void prefersMoreSpecificXmlModelRule() throws Exception {
    write("shared.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
          <start>
            <element name="fallback">
              <empty/>
            </element>
          </start>
        </grammar>
        """);
    write("styles/targeted.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
          <start>
            <element name="root">
              <empty/>
            </element>
          </start>
        </grammar>
        """);
    Path xml = write("styles/document.csl", """
        <?xml version="1.0"?>
        <root/>
        """);

    ValidationResult result = validator(List.of(
        new XmlModelRule(
            null,
            ".csl",
            XmlModelRuleMode.FALLBACK,
            List.of(new XmlModelEntry(
                temporaryDirectory.resolve("shared.rng").toString(),
                ValidationSupport.RELAXNG_NS,
                null,
                null))),
        new XmlModelRule(
            temporaryDirectory.resolve("styles"),
            ".csl",
            XmlModelRuleMode.FALLBACK,
            List.of(new XmlModelEntry(
                temporaryDirectory.resolve("styles/targeted.rng").toString(),
                ValidationSupport.RELAXNG_NS,
                null,
                null)))))
        .validate(xml);

    assertTrue(result.ok(), "Expected the directory-specific fallback rule to win");
    assertTrue(result.issues().isEmpty(), "Expected the more specific fallback rule to validate cleanly");
  }

  @Test
  void rejectsAmbiguousXmlModelRulesWithEqualSpecificity() throws Exception {
    Path xml = write("styles/document.csl", """
        <?xml version="1.0"?>
        <root/>
        """);

    ValidationResult result = validator(List.of(
        new XmlModelRule(
            temporaryDirectory.resolve("styles"),
            ".csl",
            XmlModelRuleMode.FALLBACK,
            List.of(new XmlModelEntry(
                temporaryDirectory.resolve("one.rng").toString(),
                ValidationSupport.RELAXNG_NS,
                null,
                null))),
        new XmlModelRule(
            temporaryDirectory.resolve("styles"),
            ".csl",
            XmlModelRuleMode.REPLACE,
            List.of(new XmlModelEntry(
                temporaryDirectory.resolve("two.rng").toString(),
                ValidationSupport.RELAXNG_NS,
                null,
                null)))))
        .validate(xml);

    assertFalse(result.ok());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("Ambiguous xml-model rules")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("2 rules tie at specificity")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("mode=fallback")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("mode=replace")));
  }

  @Test
  void replaceRuleOverridesInlineDeclarationsAndCanApplyMultipleConfiguredSchemas() throws Exception {
    write("inline.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
          <start>
            <element name="root">
              <empty/>
            </element>
          </start>
        </grammar>
        """);
    write("styles/replacement.rng", """
        <grammar xmlns="http://relaxng.org/ns/structure/1.0">
          <start>
            <element name="root">
              <element name="child">
                <empty/>
              </element>
            </element>
          </start>
        </grammar>
        """);
    write("styles/replacement.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <pattern>
            <rule context="root">
              <assert test="@status = 'ok'">status must be ok</assert>
            </rule>
          </pattern>
        </schema>
        """);
    Path xml = write("styles/document.csl", """
        <?xml version="1.0"?>
        <?xml-model href="../inline.rng" schematypens="http://relaxng.org/ns/structure/1.0"?>
        <root/>
        """);

    ValidationResult result = validator(List.of(
        new XmlModelRule(
            temporaryDirectory.resolve("styles"),
            ".csl",
            XmlModelRuleMode.REPLACE,
            List.of(
                new XmlModelEntry(
                    temporaryDirectory.resolve("styles/replacement.rng").toString(),
                    ValidationSupport.RELAXNG_NS,
                    null,
                    null),
                new XmlModelEntry(
                    temporaryDirectory.resolve("styles/replacement.sch").toString(),
                    ValidationSupport.SCHEMATRON_NS,
                    null,
                    null)))))
        .validate(xml);

    assertFalse(result.ok(), "Expected replacement rule to override inline xml-model declarations");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("child")),
        "Expected replacement Relax NG validation to run");
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("status must be ok")),
        "Expected replacement Schematron validation to run");
  }

  @Test
  void reportsConfiguredSchemaResolutionFailuresWithoutGenericWrapping() throws Exception {
    Path xml = write("styles/document.csl", """
        <?xml version="1.0"?>
        <root/>
        """);

    ValidationResult result = validator(List.of(
        new XmlModelRule(
            temporaryDirectory.resolve("styles"),
            ".csl",
            XmlModelRuleMode.FALLBACK,
            List.of(new XmlModelEntry(
                "missing.rng",
                ValidationSupport.RELAXNG_NS,
                null,
                null)))))
        .validate(xml);

    assertFalse(result.ok(), "Expected missing configured schema to fail validation");
    assertTrue(result.issues().stream()
        .anyMatch(issue -> issue.message().contains("Could not resolve schema reference 'missing.rng'")));
    assertTrue(result.issues().stream()
        .anyMatch(issue -> issue.message().contains(temporaryDirectory.resolve("styles").toString())));
    assertTrue(result.issues().stream().noneMatch(issue -> issue.message().contains("Validation error:")),
        "Expected schema resolution failures to be reported directly");
  }

  private XmlFileValidator validator() {
    return validator(List.of(), false, SchematronSeverityLevel.INFO);
  }

  private XmlFileValidator validator(boolean checkSchematronSchema) {
    return validator(List.of(), checkSchematronSchema, SchematronSeverityLevel.INFO);
  }

  private XmlFileValidator validator(boolean checkSchematronSchema, SchematronSeverityLevel severityThreshold) {
    return validator(List.of(), checkSchematronSchema, severityThreshold);
  }

  private XmlFileValidator validator(List<XmlModelRule> xmlModelRules) {
    return validator(xmlModelRules, false, SchematronSeverityLevel.INFO);
  }

  private XmlFileValidator validator(
      List<XmlModelRule> xmlModelRules,
      boolean checkSchematronSchema,
      SchematronSeverityLevel severityThreshold) {
    return new XmlFileValidator(Map.of(), xmlModelRules, checkSchematronSchema, severityThreshold);
  }

  private Path write(String relativePath, String content) throws IOException {
    Path file = temporaryDirectory.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
    return file;
  }
}
