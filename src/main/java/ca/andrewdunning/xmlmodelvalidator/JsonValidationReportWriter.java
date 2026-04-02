package ca.andrewdunning.xmlmodelvalidator;

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
 *
 * <p>The writer emits RFC 8259-compatible JSON directly so that the CLI does not
 * depend on a general-purpose JSON library for this narrow use case.
 */
final class JsonValidationReportWriter {
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
        return new JsonOutput().serializeReport(new JsonReport(summary, jsonResults));
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
        return new JsonOutput().serializeReport(new JsonReport(
                new JsonSummary(true, 0, 0, 0, 0, 0.0),
                List.of()));
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
        return new JsonOutput().serializePlan(new JsonPlan(
                inputSource,
                configFile,
                fileExtensions,
                jobs,
                failFast,
                schemaAliasCount,
                rules,
                files.size(),
                files));
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

    /**
     * Emits RFC 8259-compatible JSON for the small fixed report schema used by this application.
     */
    private static final class JsonOutput {
        private final StringBuilder json = new StringBuilder();

        String serializeReport(JsonReport report) {
            beginObject();
            fieldName("summary");
            appendSummary(report.summary());
            separator();
            fieldName("results");
            appendResults(report.results());
            endObject();
            return json.toString();
        }

        String serializePlan(JsonPlan plan) {
            beginObject();
            stringField("inputSource", plan.inputSource());
            separator();
            stringField("configFile", plan.configFile());
            separator();
            stringArrayField("fileExtensions", plan.fileExtensions());
            separator();
            numberField("jobs", plan.jobs());
            separator();
            booleanField("failFast", plan.failFast());
            separator();
            numberField("schemaAliasCount", plan.schemaAliasCount());
            separator();
            stringArrayField("rules", plan.rules());
            separator();
            numberField("fileCount", plan.fileCount());
            separator();
            stringArrayField("files", plan.files());
            endObject();
            return json.toString();
        }

        private void appendSummary(JsonSummary summary) {
            beginObject();
            booleanField("skipped", summary.skipped());
            separator();
            numberField("filesChecked", summary.filesChecked());
            separator();
            numberField("okFiles", summary.okFiles());
            separator();
            numberField("failedFiles", summary.failedFiles());
            separator();
            numberField("warningCount", summary.warningCount());
            separator();
            numberField("elapsedSeconds", summary.elapsedSeconds());
            endObject();
        }

        private void appendResults(List<JsonResult> results) {
            beginArray();
            for (int index = 0; index < results.size(); index++) {
                if (index > 0) {
                    separator();
                }
                appendResult(results.get(index));
            }
            endArray();
        }

        private void appendResult(JsonResult result) {
            beginObject();
            stringField("file", result.file());
            separator();
            booleanField("ok", result.ok());
            separator();
            fieldName("issues");
            appendIssues(result.issues());
            endObject();
        }

        private void appendIssues(List<JsonIssue> issues) {
            beginArray();
            for (int index = 0; index < issues.size(); index++) {
                if (index > 0) {
                    separator();
                }
                appendIssue(issues.get(index));
            }
            endArray();
        }

        private void appendIssue(JsonIssue issue) {
            beginObject();
            stringField("severity", issue.severity());
            separator();
            stringField("message", issue.message());
            separator();
            nullableNumberField("line", issue.line());
            separator();
            nullableNumberField("column", issue.column());
            separator();
            nullableNumberField("endLine", issue.endLine());
            separator();
            nullableNumberField("endColumn", issue.endColumn());
            endObject();
        }

        private void stringArrayField(String name, List<String> values) {
            fieldName(name);
            beginArray();
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) {
                    separator();
                }
                stringValue(values.get(index));
            }
            endArray();
        }

        private void stringField(String name, String value) {
            fieldName(name);
            stringValue(value);
        }

        private void booleanField(String name, boolean value) {
            fieldName(name);
            json.append(value);
        }

        private void numberField(String name, Number value) {
            fieldName(name);
            json.append(value);
        }

        private void nullableNumberField(String name, Integer value) {
            fieldName(name);
            if (value == null) {
                json.append("null");
                return;
            }
            json.append(value);
        }

        private void fieldName(String name) {
            stringValue(name);
            json.append(':');
        }

        private void stringValue(String value) {
            json.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (character < 0x20 || Character.isSurrogate(character)) {
                            json.append(String.format("\\u%04x", (int) character));
                        } else {
                            json.append(character);
                        }
                    }
                }
            }
            json.append('"');
        }

        private void beginObject() {
            json.append('{');
        }

        private void endObject() {
            json.append('}');
        }

        private void beginArray() {
            json.append('[');
        }

        private void endArray() {
            json.append(']');
        }

        private void separator() {
            json.append(',');
        }
    }
}
