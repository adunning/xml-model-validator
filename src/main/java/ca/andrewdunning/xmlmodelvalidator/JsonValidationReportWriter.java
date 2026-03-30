package ca.andrewdunning.xmlmodelvalidator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes validation results to a stable JSON report format.
 */
final class JsonValidationReportWriter {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    String write(List<ValidationResult> results, int filesChecked, int failedFiles, int warningCount, Duration elapsed) {
        JsonSummary summary = new JsonSummary(
                filesChecked,
                filesChecked - failedFiles,
                failedFiles,
                warningCount,
                elapsed.toMillis() / 1000.0);
        List<JsonResult> jsonResults = new ArrayList<>();
        for (ValidationResult result : results) {
            List<JsonIssue> jsonIssues = new ArrayList<>();
            for (ValidationIssue issue : result.issues()) {
                jsonIssues.add(new JsonIssue(
                        issue.warning() ? "warning" : "error",
                        issue.message(),
                        issue.line(),
                        issue.column(),
                        issue.endLine(),
                        issue.endColumn()));
            }
            jsonResults.add(new JsonResult(
                    ValidationSupport.relativize(result.file()),
                    result.ok(),
                    jsonIssues));
        }
        try {
            return JSON_MAPPER.writeValueAsString(new JsonReport(summary, jsonResults));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize JSON validation report", exception);
        }
    }

    private record JsonReport(JsonSummary summary, List<JsonResult> results) {
    }

    private record JsonSummary(int filesChecked, int okFiles, int failedFiles, int warningCount, double elapsedSeconds) {
    }

    private record JsonResult(String file, boolean ok, List<JsonIssue> issues) {
    }

    private record JsonIssue(String severity, String message, Integer line, Integer column, Integer endLine,
            Integer endColumn) {
    }
}
