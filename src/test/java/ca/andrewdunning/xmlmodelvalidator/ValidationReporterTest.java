package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
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
                false);

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
    void formatsGithubWarningAnnotationWithNormalizedWhitespace() {
        ValidationIssue issue = new ValidationIssue(
                Path.of("nested", "document.xml"),
                "Line one\nLine two",
                8,
                null,
                10,
                null,
                true);

        String annotation = ValidationReporter.formatGithubAnnotation(issue);

        assertTrue(annotation.startsWith("::warning "));
        assertTrue(annotation.contains("file=nested/document.xml"));
        assertTrue(annotation.contains("line=8"));
        assertTrue(annotation.contains("endLine=10"));
        assertTrue(annotation.contains("Line one Line two"));
    }

    @Test
    void emitsGithubAnnotationsWithoutDuplicateConsoleIssueLines() {
        ValidationReporter reporter = new ValidationReporter(true);
        ValidationResult result = new ValidationResult(
                Path.of("document.xml"),
                false,
                List.of(new ValidationIssue(Path.of("document.xml"), "Line one\n    Line two", 8, null, false)));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            reporter.emitSummary(List.of(result), Duration.ofMillis(500));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String stdoutText = stdout.toString(StandardCharsets.UTF_8);
        String stderrText = stderr.toString(StandardCharsets.UTF_8);

        assertTrue(stdoutText.contains("::error "));
        assertTrue(stdoutText.contains("file=document.xml"));
        assertTrue(stdoutText.contains("title=XML Validation"));
        assertTrue(stdoutText.contains("line=8"));
        assertTrue(stdoutText.contains("::Line one Line two"));
        assertTrue(stdoutText.contains("::error title=XML Validation Summary::1 of 1 file(s) failed validation"));
        assertFalse(stderrText.contains("document.xml, line 8"));
        assertTrue(stderrText.contains("ERROR: 1 file(s) failed validation"));
    }

    @Test
    void formatsGithubErrorAnnotationForMalformedXml() {
        ValidationIssue issue = new ValidationIssue(
                Path.of("collections", "Digby", "MS_Digby_18.xml"),
                "The element type \"origPlace\" must be terminated by the matching end-tag \"</origPlace>\".",
                78,
                21,
                false);

        String annotation = ValidationReporter.formatGithubAnnotation(issue);

        assertTrue(annotation.startsWith("::error "));
        assertTrue(annotation.contains("file=collections/Digby/MS_Digby_18.xml"));
        assertTrue(annotation.contains("line=78"));
        assertTrue(annotation.contains("endLine=78"));
        assertTrue(annotation.contains("col=21"));
        assertTrue(annotation.contains("endColumn=21"));
        assertTrue(annotation.contains("title=XML Validation"));
        assertTrue(annotation.endsWith(
                "The element type \"origPlace\" must be terminated by the matching end-tag \"</origPlace>\"."));
    }
}
