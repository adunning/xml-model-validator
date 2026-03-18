package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

  private XmlFileValidator validator() {
    return new XmlFileValidator(Map.of());
  }

  private Path write(String relativePath, String content) throws IOException {
    Path file = temporaryDirectory.resolve(relativePath);
    Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
    return file;
  }
}
