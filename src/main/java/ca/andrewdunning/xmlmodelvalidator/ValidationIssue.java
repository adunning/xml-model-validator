package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;

/**
 * A single validation problem, optionally including precise start and end locations.
 */
final class ValidationIssue {
    private final Path file;
    private final String message;
    private final Integer line;
    private final Integer column;
    private final Integer endLine;
    private final Integer endColumn;
    private final boolean warning;

    ValidationIssue(Path file, String message, Integer line, Integer column, boolean warning) {
        this(file, message, line, column, line, column, warning);
    }

    ValidationIssue(
        Path file,
        String message,
        Integer line,
        Integer column,
        Integer endLine,
        Integer endColumn,
        boolean warning
    ) {
        this.file = file;
        this.message = message;
        this.line = line;
        this.column = column;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.warning = warning;
    }

    Path file() {
        return file;
    }

    String message() {
        return message;
    }

    Integer line() {
        return line;
    }

    Integer column() {
        return column;
    }

    Integer endLine() {
        return endLine;
    }

    Integer endColumn() {
        return endColumn;
    }

    boolean warning() {
        return warning;
    }
}
