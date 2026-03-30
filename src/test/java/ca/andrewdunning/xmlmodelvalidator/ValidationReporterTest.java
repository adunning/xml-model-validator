package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
final class ValidationReporterTest {
    @TempDir
    Path temporaryDirectory;

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
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ValidationReporter reporter = new ValidationReporter(
                OutputFormat.GITHUB,
                false,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        ValidationResult result = new ValidationResult(
                Path.of("document.xml"),
                false,
                List.of(new ValidationIssue(Path.of("document.xml"), "Line one\n    Line two", 8, null, false)));

        reporter.emitSummary(List.of(result), Duration.ofMillis(500));

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
    void writesGithubStepSummaryWhenConfigured() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Path summaryFile = temporaryDirectory.resolve("step-summary.md");
        Path reportFile = temporaryDirectory.resolve("report.json");
        ValidationReporter reporter = new ValidationReporter(
                OutputFormat.GITHUB,
                false,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                summaryFile,
                reportFile,
                "directory:docs",
                ".xml-validator/config.toml",
                ".xml .csl",
                "reports/xml-validation.json");
        ValidationResult result = new ValidationResult(
                Path.of("document.xml"),
                false,
                List.of(new ValidationIssue(Path.of("document.xml"), "Broken <tag>", 12, 4, false)));

        reporter.emitSummary(List.of(result), Duration.ofMillis(500));

        String markdown = Files.readString(summaryFile, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("## XML Validation"));
        assertTrue(markdown.contains("Validation found errors."));
        assertTrue(markdown.contains("| Checked | Failed | Warnings | Duration |"));
        assertTrue(markdown.contains("| 1 | 1 | 0 | 0.50s |"));
        assertTrue(markdown.contains("### Run Context"));
        assertTrue(markdown.contains("| Selection | Directory scan of `docs` |"));
        assertTrue(markdown.contains("| Config | `.xml-validator/config.toml` |"));
        assertTrue(markdown.contains("| File extensions | `.xml .csl` |"));
        assertTrue(markdown.contains("JSON report: `reports/xml-validation.json`"));
        assertTrue(markdown.contains("| Error | `document.xml` | line 12, col 4 | Broken <tag> |"));
        String json = Files.readString(reportFile, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"skipped\":false"));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("ERROR: 1 file(s) failed validation"));
    }

    @Test
    void writesStructuredValidationSummaryWhenConfigured() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Path summaryFile = temporaryDirectory.resolve("summary.json");
        ValidationReporter reporter = new ValidationReporter(
                OutputFormat.TEXT,
                false,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                null,
                summaryFile);
        ValidationResult result = new ValidationResult(
                Path.of("document.xml"),
                false,
                List.of(new ValidationIssue(Path.of("document.xml"), "Broken <tag>", 12, 4, false)));

        reporter.emitSummary(List.of(result), Duration.ofMillis(500));

        String json = Files.readString(summaryFile, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"filesChecked\":1"));
        assertTrue(json.contains("\"failedFiles\":1"));
        assertTrue(json.contains("\"warningCount\":0"));
    }

    @Test
    void emitsJsonSummaryForStructuredConsumers() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ValidationReporter reporter = new ValidationReporter(
                OutputFormat.JSON,
                false,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        ValidationResult result = new ValidationResult(
                Path.of("document.xml"),
                false,
                List.of(new ValidationIssue(Path.of("document.xml"), "Broken <tag>", 12, 4, false)));

        int exitCode = reporter.emitSummary(List.of(result), Duration.ofMillis(500));

        String stdoutText = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode);
        assertTrue(stdoutText.contains("\"summary\""));
        assertTrue(stdoutText.contains("\"failedFiles\":1"));
        assertTrue(stdoutText.contains("\"severity\":\"error\""));
        assertTrue(stdoutText.contains("\"message\":\"Broken <tag>\""));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).isBlank());
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
