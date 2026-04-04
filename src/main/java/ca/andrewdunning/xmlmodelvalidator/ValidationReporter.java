package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Formats validation progress, console output, and GitHub Actions annotations.
 */
final class ValidationReporter {
    private static final int STEP_SUMMARY_ISSUE_LIMIT = 20;

    private final OutputFormat format;
    private final boolean verbose;
    private final PrintStream output;
    private final PrintStream error;
    private final JsonValidationReportWriter jsonReportWriter;
    private final Path githubStepSummaryPath;
    private final Path validationSummaryPath;
    private final String selectionContext;
    private final String configContext;
    private final String fileExtensionsContext;
    private final String jsonReportPath;

    ValidationReporter(OutputFormat format, boolean verbose, PrintStream output, PrintStream error) {
        this(format, verbose, output, error, resolveGithubStepSummaryPath(), resolveValidationSummaryPath());
    }

    ValidationReporter(
            OutputFormat format,
            boolean verbose,
            PrintStream output,
            PrintStream error,
            Path githubStepSummaryPath,
            Path validationSummaryPath) {
        this(
                format,
                verbose,
                output,
                error,
                githubStepSummaryPath,
                validationSummaryPath,
                System.getenv("XML_MODEL_VALIDATOR_SUMMARY_SELECTION"),
                System.getenv("XML_MODEL_VALIDATOR_SUMMARY_CONFIG"),
                System.getenv("XML_MODEL_VALIDATOR_SUMMARY_FILE_EXTENSIONS"),
                System.getenv("XML_MODEL_VALIDATOR_SUMMARY_JSON_REPORT_PATH"));
    }

    ValidationReporter(
            OutputFormat format,
            boolean verbose,
            PrintStream output,
            PrintStream error,
            Path githubStepSummaryPath,
            Path validationSummaryPath,
            String selectionContext,
            String configContext,
            String fileExtensionsContext,
            String jsonReportPath) {
        this.format = format;
        this.verbose = verbose;
        this.output = output;
        this.error = error;
        this.jsonReportWriter = new JsonValidationReportWriter();
        this.githubStepSummaryPath = githubStepSummaryPath;
        this.validationSummaryPath = validationSummaryPath;
        this.selectionContext = selectionContext;
        this.configContext = configContext;
        this.fileExtensionsContext = fileExtensionsContext;
        this.jsonReportPath = jsonReportPath;
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
        writeValidationSummary(results, summary);
        return switch (format) {
            case TEXT -> emitTextSummary(results, summary);
            case GITHUB -> emitGithubSummary(results, summary);
            case JSON -> emitJsonSummary(results, summary);
        };
    }

    private static Summary summarize(List<ValidationResult> results, Duration elapsed) {
        int failedFiles = (int) results.stream()
                .filter(result -> !result.ok())
                .count();
        int warningCount = (int) results.stream()
                .flatMap(result -> result.issues().stream())
                .filter(ValidationIssue::warning)
                .count();
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
        writeGithubStepSummary(results, summary);

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

    private void writeGithubStepSummary(List<ValidationResult> results, Summary summary) {
        if (githubStepSummaryPath == null) {
            return;
        }

        StringBuilder markdown = new StringBuilder();
        List<ValidationIssue> issues = results.stream()
                .flatMap(result -> result.issues().stream())
                .toList();
        appendSummaryHeader(markdown, summary);
        appendRunContext(markdown);
        appendJsonReportReference(markdown);
        appendIssues(markdown, issues);

        try {
            Files.writeString(
                    githubStepSummaryPath,
                    markdown.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            error.printf(
                    "WARNING: Could not write GitHub step summary to %s: %s%n",
                    githubStepSummaryPath,
                    exception.getMessage());
        }
    }

    private static void appendSummaryHeader(StringBuilder markdown, Summary summary) {
        markdown.append("## XML Validation\n\n");
        markdown.append(summary.failedFiles() == 0
                ? "Validation completed successfully.\n\n"
                : "Validation found errors.\n\n");
        appendTable(
                markdown,
                List.of("Checked", "Failed", "Warnings", "Duration"),
                List.of(List.of(
                        Integer.toString(summary.filesChecked()),
                        Integer.toString(summary.failedFiles()),
                        Integer.toString(summary.warningCount()),
                        formatElapsed(summary.elapsed()))));
        markdown.append('\n');
    }

    private void appendRunContext(StringBuilder markdown) {
        List<List<String>> rows = new ArrayList<>();
        if (selectionContext != null) {
            rows.add(List.of("Selection", escapeMarkdown(describeSelection(selectionContext))));
        }
        if (configContext != null) {
            rows.add(List.of("Config", '`' + escapeMarkdown(configContext) + '`'));
        }
        if (fileExtensionsContext != null) {
            rows.add(List.of("File extensions", '`' + escapeMarkdown(fileExtensionsContext) + '`'));
        }
        if (rows.isEmpty()) {
            return;
        }

        markdown.append("### Run Context\n\n");
        appendTable(markdown, List.of("Setting", "Value"), rows);
        markdown.append('\n');
    }

    private void appendJsonReportReference(StringBuilder markdown) {
        if (jsonReportPath == null || jsonReportPath.isBlank()) {
            return;
        }
        markdown.append("JSON report: `")
                .append(escapeMarkdown(jsonReportPath))
                .append("`\n\n");
    }

    private static void appendIssues(StringBuilder markdown, List<ValidationIssue> issues) {
        if (issues.isEmpty()) {
            markdown.append("All checked files validated successfully.\n\n");
            return;
        }

        markdown.append("### Issues\n\n");
        appendTable(
                markdown,
                List.of("Severity", "File", "Location", "Message"),
                issues.stream()
                        .limit(STEP_SUMMARY_ISSUE_LIMIT)
                        .map(issue -> List.of(
                                issue.warning() ? "Warning" : "Error",
                                '`' + escapeMarkdown(ValidationSupport.relativize(issue.file())) + '`',
                                formatIssueLocation(issue),
                                escapeMarkdown(issue.message())))
                        .toList());
        markdown.append('\n');
        int omittedIssueCount = issues.size() - STEP_SUMMARY_ISSUE_LIMIT;
        if (omittedIssueCount > 0) {
            markdown.append(omittedIssueCount)
                    .append(" additional issue(s) omitted.\n\n");
        }
    }

    private static void appendTable(StringBuilder markdown, List<String> headers, List<List<String>> rows) {
        markdown.append("| ")
                .append(String.join(" | ", headers))
                .append(" |\n| ");
        markdown.append(
                headers.stream().map(ignored -> "---").reduce((left, right) -> left + " | " + right).orElse("---"));
        markdown.append(" |\n");
        for (List<String> row : rows) {
            markdown.append("| ")
                    .append(String.join(" | ", row))
                    .append(" |\n");
        }
    }

    private static String formatElapsed(Duration elapsed) {
        return String.format("%.2fs", elapsed.toMillis() / 1000.0);
    }

    private static String formatIssueLocation(ValidationIssue issue) {
        if (issue.line() == null) {
            return "-";
        }
        if (issue.column() == null) {
            return "line " + issue.line();
        }
        return "line " + issue.line() + ", col " + issue.column();
    }

    private static String escapeMarkdown(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", " ");
    }

    private static String describeSelection(String selectionContext) {
        if (selectionContext.startsWith("directory:")) {
            return "Directory scan of `" + selectionContext.substring("directory:".length()) + "`";
        }
        if (selectionContext.startsWith("files_from:")) {
            return "File list from `" + selectionContext.substring("files_from:".length()) + "`";
        }
        if (selectionContext.startsWith("changed_files_only:")) {
            return "Changed files only (" + selectionContext.substring("changed_files_only:".length()) + ")";
        }
        if ("files".equals(selectionContext)) {
            return "Explicit file list";
        }
        return selectionContext;
    }

    private static Path resolveGithubStepSummaryPath() {
        String summaryPath = System.getenv("GITHUB_STEP_SUMMARY");
        if (summaryPath == null || summaryPath.isBlank()) {
            return null;
        }
        return Path.of(summaryPath);
    }

    private void writeValidationSummary(List<ValidationResult> results, Summary summary) {
        if (validationSummaryPath == null) {
            return;
        }
        jsonReportWriter.writeToFile(
                validationSummaryPath,
                results,
                summary.filesChecked(),
                summary.failedFiles(),
                summary.warningCount(),
                summary.elapsed());
    }

    private static Path resolveValidationSummaryPath() {
        String summaryPath = System.getenv("XML_MODEL_VALIDATOR_SUMMARY_FILE");
        if (summaryPath == null || summaryPath.isBlank()) {
            return null;
        }
        return Path.of(summaryPath);
    }

    private record Summary(int filesChecked, int failedFiles, int warningCount, Duration elapsed) {
    }
}
