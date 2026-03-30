package ca.andrewdunning.xmlmodelvalidator;

import java.util.Locale;

/**
 * One {@code xml-model} processing instruction interpreted in the subset of attributes used by the
 * validator.
 */
record XmlModelEntry(String href, String schemaTypeNamespace, String type, String phase) {
    XmlModelEntry {
        schemaTypeNamespace = schemaTypeNamespace == null ? "" : schemaTypeNamespace;
        type = type == null ? "" : type;
        phase = phase == null ? "" : phase;
    }

    /**
     * Embedded Schematron is defined for Relax NG XML syntax, not compact syntax.
     */
    boolean supportsEmbeddedSchematron() {
        return isRelaxNg() && !isCompactRelaxNg();
    }

    boolean matches(SchemaKind schemaKind) {
        return switch (schemaKind) {
            case RELAX_NG -> isRelaxNg();
            case SCHEMATRON -> isSchematron();
        };
    }

    private boolean isRelaxNg() {
        return ValidationSupport.RELAXNG_NS.equals(schemaTypeNamespace)
                || hasExtension(".rng")
                || hasExtension(".rnc")
                || type.toLowerCase(Locale.ROOT).contains("relax-ng");
    }

    private boolean isSchematron() {
        if (isRelaxNg()) {
            return false;
        }
        return ValidationSupport.SCHEMATRON_NS.equals(schemaTypeNamespace)
                || hasExtension(".sch")
                || hasExtension(".schematron")
                || type.toLowerCase(Locale.ROOT).contains("schematron");
    }

    private boolean isCompactRelaxNg() {
        return hasExtension(".rnc") || type.toLowerCase(Locale.ROOT).contains("compact");
    }

    private boolean hasExtension(String extension) {
        return href.toLowerCase(Locale.ROOT).endsWith(extension);
    }
}
