package ca.andrewdunning.xmlmodelvalidator;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Formats validation progress, console output, and GitHub Actions annotations.
 */
final class ValidationReporter {
    private final OutputFormat format;
    private final boolean verbose;
    private final PrintStream output;
    private final PrintStream error;
    private final JsonValidationReportWriter jsonReportWriter;

    ValidationReporter(OutputFormat format, boolean verbose, PrintStream output, PrintStream error) {
        this.format = format;
        this.verbose = verbose;
        this.output = output;
        this.error = error;
        this.jsonReportWriter = new JsonValidationReportWriter();
    }

    void emitStartup(List<java.nio.file.Path> files, int workers, boolean directoryMode) {
        if (!verbose || format != OutputFormat.TEXT) {
            return;
        }
        error.printf("INFO: Validating %d file(s) with %d worker(s) ...%n", files.size(), workers);
        if (!directoryMode) {
            error.println("INFO: Files to be validated:");
            for (java.nio.file.Path file : files) {
                error.printf("INFO:   %s%n", ValidationSupport.relativize(file));
            }
        }
    }

    int emitSummary(List<ValidationResult> results, Duration elapsed) {
        Summary summary = summarize(results, elapsed);
        return switch (format) {
            case TEXT -> emitTextSummary(results, summary);
            case GITHUB -> emitGithubSummary(results, summary);
            case JSON -> emitJsonSummary(results, summary);
        };
    }

    private static Summary summarize(List<ValidationResult> results, Duration elapsed) {
        int failedFiles = 0;
        int warningCount = 0;
        for (ValidationResult result : results) {
            if (!result.ok()) {
                failedFiles += 1;
            }
            for (ValidationIssue issue : result.issues()) {
                if (issue.warning()) {
                    warningCount += 1;
                }
            }
        }
        return new Summary(results.size(), failedFiles, warningCount, elapsed);
    }

    private int emitTextSummary(List<ValidationResult> results, Summary summary) {
        for (ValidationResult result : results) {
            for (ValidationIssue issue : result.issues()) {
                emitTextIssue(issue);
            }
        }

        double seconds = summary.elapsed().toMillis() / 1000.0;
        if (summary.failedFiles() == 0) {
            if (verbose) {
                error.printf("INFO: Validated %d file(s): all OK in %.2fs%n", summary.filesChecked(), seconds);
            }
            return 0;
        }

        int okFiles = summary.filesChecked() - summary.failedFiles();
        error.printf("INFO: Validated %d file(s): %d OK, %d failed in %.2fs%n",
                summary.filesChecked(), okFiles, summary.failedFiles(), seconds);
        error.printf("ERROR: %d file(s) failed validation%n", summary.failedFiles());
        return 1;
    }

    private int emitGithubSummary(List<ValidationResult> results, Summary summary) {
        for (ValidationResult result : results) {
            for (ValidationIssue issue : result.issues()) {
                output.println(formatGithubAnnotation(issue));
            }
        }

        double seconds = summary.elapsed().toMillis() / 1000.0;
        if (summary.failedFiles() == 0) {
            output.printf("::notice title=XML Validation::Validated %d file(s) in %.2fs with %d warning(s)%n",
                    summary.filesChecked(), seconds, summary.warningCount());
            return 0;
        }

        int okFiles = summary.filesChecked() - summary.failedFiles();
        error.printf("INFO: Validated %d file(s): %d OK, %d failed in %.2fs%n",
                summary.filesChecked(), okFiles, summary.failedFiles(), seconds);
        error.printf("ERROR: %d file(s) failed validation%n", summary.failedFiles());
        output.printf("::error title=XML Validation Summary::%d of %d file(s) failed validation%n",
                summary.failedFiles(), summary.filesChecked());
        return 1;
    }

    private int emitJsonSummary(List<ValidationResult> results, Summary summary) {
        output.println(jsonReportWriter.write(
                results,
                summary.filesChecked(),
                summary.failedFiles(),
                summary.warningCount(),
                summary.elapsed()));
        return summary.failedFiles() == 0 ? 0 : 1;
    }

    private void emitTextIssue(ValidationIssue issue) {
        String prefix = issue.warning() ? "WARNING" : "ERROR";
        String location = issue.line() != null
                ? String.format("%s, line %d: ", ValidationSupport.relativize(issue.file()), issue.line())
                : ValidationSupport.relativize(issue.file()) + ": ";
        error.printf("%s: %s%s%n", prefix, location, issue.message());
    }

    /**
     * Formats a single validation issue as a GitHub workflow command annotation.
     */
    static String formatGithubAnnotation(ValidationIssue issue) {
        List<String> props = new ArrayList<>();
        props.add("file=" + ValidationSupport.escapeProperty(ValidationSupport.relativize(issue.file())));
        props.add("title=" + ValidationSupport.escapeProperty("XML Validation"));
        if (issue.line() != null) {
            props.add("line=" + issue.line());
        }
        if (issue.endLine() != null) {
            props.add("endLine=" + issue.endLine());
        }
        if (issue.column() != null) {
            props.add("col=" + issue.column());
        }
        if (issue.endColumn() != null) {
            props.add("endColumn=" + issue.endColumn());
        }
        String level = issue.warning() ? "warning" : "error";
        return String.format(
                "::%s %s::%s",
                level,
                String.join(",", props),
                ValidationSupport.escapeMessage(issue.message()));
    }

    private record Summary(int filesChecked, int failedFiles, int warningCount, Duration elapsed) {
    }
}
