package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The validation outcome for a single XML file.
 */
final class ValidationResult {
    private final Path file;
    private final boolean ok;
    private final List<ValidationIssue> issues;

    ValidationResult(Path file, boolean ok, List<ValidationIssue> issues) {
        this.file = file;
        this.ok = ok;
        this.issues = List.copyOf(new LinkedHashSet<>(issues));
    }

    static ValidationResult failed(Path file, ValidationIssue issue) {
        return new ValidationResult(file, false, List.of(issue));
    }

    Path file() {
        return file;
    }

    boolean ok() {
        return ok;
    }

    List<ValidationIssue> issues() {
        return issues;
    }
}
