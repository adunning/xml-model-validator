package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;

/** A single validation problem, optionally including precise start and end locations. */
record ValidationIssue(
        Path file, String message, Integer line, Integer column, Integer endLine, Integer endColumn, boolean warning) {
    ValidationIssue(Path file, String message, Integer line, Integer column, boolean warning) {
        this(file, message, line, column, line, column, warning);
    }

    ValidationIssue {
        message = normalizeMessage(message);
    }

    private static String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}
