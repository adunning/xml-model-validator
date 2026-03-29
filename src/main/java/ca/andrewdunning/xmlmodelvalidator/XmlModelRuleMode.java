package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.util.Locale;

/**
 * Controls how a configured rule interacts with inline {@code xml-model}
 * processing instructions.
 */
enum XmlModelRuleMode {
    FALLBACK,
    REPLACE;

    static XmlModelRuleMode parse(String rawValue) throws IOException {
        if (rawValue == null || rawValue.isBlank()) {
            return FALLBACK;
        }
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "fallback" -> FALLBACK;
            case "replace" -> REPLACE;
            default -> throw new IOException("Unsupported xml-model rule mode: " + rawValue);
        };
    }
}
