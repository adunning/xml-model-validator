package ca.andrewdunning.xmlmodelvalidator;

import java.util.Locale;

/**
 * One {@code xml-model} processing instruction interpreted in the subset of attributes used by the
 * validator.
 */
final class XmlModelEntry {
    private final String href;
    private final String schemaTypeNamespace;
    private final String type;
    private final String phase;

    XmlModelEntry(String href, String schemaTypeNamespace, String type, String phase) {
        this.href = href;
        this.schemaTypeNamespace = schemaTypeNamespace == null ? "" : schemaTypeNamespace;
        this.type = type == null ? "" : type;
        this.phase = phase == null ? "" : phase;
    }

    String href() {
        return href;
    }

    String schemaTypeNamespace() {
        return schemaTypeNamespace;
    }

    String type() {
        return type;
    }

    String phase() {
        return phase;
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
