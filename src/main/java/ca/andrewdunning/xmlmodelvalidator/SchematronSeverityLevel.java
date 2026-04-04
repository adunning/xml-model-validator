package ca.andrewdunning.xmlmodelvalidator;

import java.util.Locale;

/**
 * Supported SchXslt severity levels for Schematron assertions.
 */
enum SchematronSeverityLevel {
    INFO,
    WARNING,
    ERROR,
    FATAL;

    String cliValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}