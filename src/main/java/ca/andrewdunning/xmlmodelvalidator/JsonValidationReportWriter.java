package ca.andrewdunning.xmlmodelvalidator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
                false,
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

    void writeToFile(
            Path path,
            List<ValidationResult> results,
            int filesChecked,
            int failedFiles,
            int warningCount,
            Duration elapsed) {
        try {
            Files.writeString(
                    path,
                    write(results, filesChecked, failedFiles, warningCount, elapsed),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write JSON validation report to " + path, exception);
        }
    }

    String writeSkippedSummary() {
        try {
            return JSON_MAPPER.writeValueAsString(new JsonReport(
                    new JsonSummary(true, 0, 0, 0, 0, 0.0),
                    List.of()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize skipped JSON validation report", exception);
        }
    }

    String writePlan(
            String inputSource,
            String configFile,
            List<String> fileExtensions,
            int jobs,
            boolean failFast,
            int schemaAliasCount,
            List<String> rules,
            List<String> files) {
        try {
            return JSON_MAPPER.writeValueAsString(new JsonPlan(
                    inputSource,
                    configFile,
                    fileExtensions,
                    jobs,
                    failFast,
                    schemaAliasCount,
                    rules,
                    files.size(),
                    files));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize validation plan", exception);
        }
    }

    private record JsonReport(JsonSummary summary, List<JsonResult> results) {
    }

    private record JsonSummary(
            boolean skipped,
            int filesChecked,
            int okFiles,
            int failedFiles,
            int warningCount,
            double elapsedSeconds) {
    }

    private record JsonResult(String file, boolean ok, List<JsonIssue> issues) {
    }

    private record JsonIssue(String severity, String message, Integer line, Integer column, Integer endLine,
            Integer endColumn) {
    }

    private record JsonPlan(
            String inputSource,
            String configFile,
            List<String> fileExtensions,
            int jobs,
            boolean failFast,
            int schemaAliasCount,
            List<String> rules,
            int fileCount,
            List<String> files) {
    }
}
