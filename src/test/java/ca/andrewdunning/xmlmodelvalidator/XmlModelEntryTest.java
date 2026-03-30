package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XmlModelEntryTest {
    @Test
    void recognizesRelaxNgCompactSyntaxByType() {
        XmlModelEntry entry = new XmlModelEntry(
                "schema.bin",
                "",
                "application/relax-ng-compact-syntax",
                "");

        assertTrue(entry.matches(SchemaKind.RELAX_NG));
        assertFalse(entry.supportsEmbeddedSchematron());
    }

    @Test
    void recognizesSchematronByType() {
        XmlModelEntry entry = new XmlModelEntry(
                "schema.bin",
                "",
                "application/schematron+xml",
                "");

        assertTrue(entry.matches(SchemaKind.SCHEMATRON));
    }

    @Test
    void prefersRelaxNgClassificationForCompactSyntaxEvenWithConflictingSchemaNamespace() {
        XmlModelEntry entry = new XmlModelEntry(
                "schema.rnc",
                ValidationSupport.SCHEMATRON_NS,
                "application/relax-ng-compact-syntax",
                "");

        assertTrue(entry.matches(SchemaKind.RELAX_NG));
        assertFalse(entry.matches(SchemaKind.SCHEMATRON));
        assertFalse(entry.supportsEmbeddedSchematron());
    }

    @Test
    void recognizesEmbeddedSchematronOnlyForRelaxNgXmlSyntax() {
        XmlModelEntry entry = new XmlModelEntry(
                "schema.rng",
                ValidationSupport.RELAXNG_NS,
                "application/xml",
                "");

        assertTrue(entry.supportsEmbeddedSchematron());
    }

    @Test
    void normalizesNullableFieldsToEmptyStrings() {
        XmlModelEntry entry = new XmlModelEntry("schema.rng", null, null, null);

        assertTrue(entry.schemaTypeNamespace().isEmpty());
        assertTrue(entry.type().isEmpty());
        assertTrue(entry.phase().isEmpty());
    }
}
