package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A single validation problem, optionally including precise start and end
 * locations.
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
            boolean warning) {
        this.file = file;
        this.message = normalizeMessage(message);
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ValidationIssue issue)) {
            return false;
        }
        return warning == issue.warning
                && Objects.equals(file, issue.file)
                && Objects.equals(message, issue.message)
                && Objects.equals(line, issue.line)
                && Objects.equals(column, issue.column)
                && Objects.equals(endLine, issue.endLine)
                && Objects.equals(endColumn, issue.endColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, message, line, column, endLine, endColumn, warning);
    }

    private static String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}
