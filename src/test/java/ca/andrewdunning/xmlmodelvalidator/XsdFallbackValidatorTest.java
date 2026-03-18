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

final class XsdFallbackValidatorTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void validatesNoNamespaceSchemaLocationWhenNoXmlModelIsPresent() throws Exception {
    write("schema.xsd", """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:element name="root">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="child" minOccurs="1" maxOccurs="1"/>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>
        """);
    Path xml = write("document.xml", """
        <root xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:noNamespaceSchemaLocation="schema.xsd"/>
        """);

    ValidationResult result = new XmlFileValidator(Map.of()).validate(xml);

    assertFalse(result.ok());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("child")));
  }

  @Test
  void validatesSchemaLocationWhenNoXmlModelIsPresent() throws Exception {
    write("schema.xsd", """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                   targetNamespace="urn:test"
                   xmlns="urn:test"
                   elementFormDefault="qualified">
          <xs:element name="root">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="child" type="xs:string"/>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>
        """);
    Path xml = write("document.xml", """
        <root xmlns="urn:test"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="urn:test schema.xsd">
          <child>ok</child>
        </root>
        """);

    ValidationResult result = new XmlFileValidator(Map.of()).validate(xml);

    assertTrue(result.ok());
  }

  @Test
  void prefersXmlModelOverXsdFallback() throws Exception {
    write("rules.sch", """
        <schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
          <pattern>
            <rule context="root">
              <assert test="@status = 'ok'">status must be ok</assert>
            </rule>
          </pattern>
        </schema>
        """);
    write("schema.xsd", """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:element name="root">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="child" minOccurs="1" maxOccurs="1"/>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>
        """);
    Path xml = write("document.xml", """
        <?xml version="1.0"?>
        <?xml-model href="rules.sch" schematypens="http://purl.oclc.org/dsdl/schematron"?>
        <root xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:noNamespaceSchemaLocation="schema.xsd"
              status="ok"/>
        """);

    ValidationResult result = new XmlFileValidator(Map.of()).validate(xml);

    assertTrue(result.ok(), "Expected xml-model validation to take precedence over XSD fallback");
  }

  private Path write(String relativePath, String content) throws IOException {
    Path file = temporaryDirectory.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
    return file;
  }
}
