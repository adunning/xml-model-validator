package ca.andrewdunning.xmlmodelvalidator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Formats validation progress, console output, and GitHub Actions annotations.
 */
final class ValidationReporter {
    private final boolean githubActions;
    private final boolean verbose;

    ValidationReporter() {
        this("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS")), false);
    }

    ValidationReporter(boolean githubActions, boolean verbose) {
        this.githubActions = githubActions;
        this.verbose = verbose;
    }

    void emitStartup(List<java.nio.file.Path> files, int workers, boolean directoryMode) {
        if (!verbose) {
            return;
        }
        System.err.printf("INFO: Validating %d file(s) with %d worker(s) ...%n", files.size(), workers);
        if (!directoryMode) {
            System.err.println("INFO: Files to be validated:");
            for (java.nio.file.Path file : files) {
                System.err.printf("INFO:   %s%n", ValidationSupport.relativize(file));
            }
        }
    }

    int emitSummary(List<ValidationResult> results, Duration elapsed) {
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
                emitIssue(issue);
            }
        }

        double seconds = elapsed.toMillis() / 1000.0;
        if (failedFiles == 0) {
            if (verbose) {
                System.err.printf("INFO: Validated %d file(s): all OK in %.2fs%n", results.size(), seconds);
            }
            if (githubActions) {
                System.out.printf("::notice title=XML Validation::Validated %d file(s) in %.2fs with %d warning(s)%n",
                        results.size(), seconds, warningCount);
            }
            return 0;
        }

        int okFiles = results.size() - failedFiles;
        System.err.printf("INFO: Validated %d file(s): %d OK, %d failed in %.2fs%n",
                results.size(), okFiles, failedFiles, seconds);
        System.err.printf("ERROR: %d file(s) failed validation%n", failedFiles);
        if (githubActions) {
            System.out.printf("::error title=XML Validation Summary::%d of %d file(s) failed validation%n",
                    failedFiles, results.size());
        }
        return 1;
    }

    private void emitIssue(ValidationIssue issue) {
        if (githubActions) {
            System.out.println(formatGithubAnnotation(issue));
            return;
        }

        String prefix = issue.warning() ? "WARNING" : "ERROR";
        String location = issue.line() != null
                ? String.format("%s, line %d: ", ValidationSupport.relativize(issue.file()), issue.line())
                : ValidationSupport.relativize(issue.file()) + ": ";
        System.err.printf("%s: %s%s%n", prefix, location, issue.message());
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
}
