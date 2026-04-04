package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** The validation outcome for a single XML file. */
record ValidationResult(Path file, boolean ok, List<ValidationIssue> issues) {
    ValidationResult {
        issues = List.copyOf(new LinkedHashSet<>(issues));
    }

    static ValidationResult failed(Path file, ValidationIssue issue) {
        return new ValidationResult(file, false, List.of(issue));
    }
}
