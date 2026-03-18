package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidationReporterTest {
    @Test
    void formatsGithubErrorAnnotationWithPreciseLocation() {
        ValidationIssue issue = new ValidationIssue(
            Path.of("document.xml"),
            "Broken <tag>, see details",
            12,
            4,
            12,
            9,
            false
        );

        String annotation = ValidationReporter.formatGithubAnnotation(issue);

        assertTrue(annotation.startsWith("::error "));
        assertTrue(annotation.contains("file=document.xml"));
        assertTrue(annotation.contains("line=12"));
        assertTrue(annotation.contains("endLine=12"));
        assertTrue(annotation.contains("col=4"));
        assertTrue(annotation.contains("endColumn=9"));
        assertTrue(annotation.contains("title=XML Validation"));
        assertTrue(annotation.endsWith("Broken <tag>, see details"));
    }

    @Test
    void formatsGithubWarningAnnotationAndEscapesNewlines() {
        ValidationIssue issue = new ValidationIssue(
            Path.of("nested", "document.xml"),
            "Line one\nLine two",
            8,
            null,
            10,
            null,
            true
        );

        String annotation = ValidationReporter.formatGithubAnnotation(issue);

        assertTrue(annotation.startsWith("::warning "));
        assertTrue(annotation.contains("file=nested/document.xml"));
        assertTrue(annotation.contains("line=8"));
        assertTrue(annotation.contains("endLine=10"));
        assertTrue(annotation.contains("Line one%0ALine two"));
    }
}
