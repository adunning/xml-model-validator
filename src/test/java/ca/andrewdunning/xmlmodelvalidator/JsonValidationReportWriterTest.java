package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonValidationReportWriterTest {
        @Test
        void serializesValidationReportWithStableFieldOrderAndNullLocations() {
                JsonValidationReportWriter writer = new JsonValidationReportWriter();
                ValidationResult result = new ValidationResult(
                                Path.of("document.xml"),
                                false,
                                List.of(new ValidationIssue(Path.of("document.xml"), "Broken value", null, null, null,
                                                null, false)));

                String json = writer.write(List.of(result), 1, 1, 0, Duration.ofMillis(500));

                assertEquals(
                                """
                                                {"summary":{"skipped":false,"filesChecked":1,"okFiles":0,"failedFiles":1,"warningCount":0,"elapsedSeconds":0.5},"results":[{"file":"document.xml","ok":false,"issues":[{"severity":"error","message":"Broken value","line":null,"column":null,"endLine":null,"endColumn":null}]}]}""",
                                json);
        }

        @Test
        void escapesJsonStringsInReportsAndPlans() {
                JsonValidationReportWriter writer = new JsonValidationReportWriter();
                ValidationResult result = new ValidationResult(
                                Path.of("folder", "doc\"name.xml"),
                                false,
                                List.of(new ValidationIssue(
                                                Path.of("folder", "doc\"name.xml"),
                                                "Broken slash \\ and \"quote\"",
                                                3,
                                                7,
                                                false)));

                String reportJson = writer.write(List.of(result), 1, 1, 0, Duration.ZERO);
                String planJson = writer.writePlan(
                                "directory:\"docs\"",
                                "config\tfile.toml",
                                List.of("xml", "tei\"xml"),
                                4,
                                true,
                                true,
                                2,
                                List.of("href=\"schema.rng\""),
                                List.of("folder/doc\"name.xml"));

                assertTrue(reportJson.contains("\"file\":\"folder/doc\\\"name.xml\""));
                assertTrue(reportJson.contains("\"message\":\"Broken slash \\\\ and \\\"quote\\\"\""));
                assertTrue(planJson.contains("\"inputSource\":\"directory:\\\"docs\\\"\""));
                assertTrue(planJson.contains("\"configFile\":\"config\\tfile.toml\""));
                assertTrue(planJson.contains("\"fileExtensions\":[\"xml\",\"tei\\\"xml\"]"));
                assertTrue(planJson.contains("\"checkSchematronSchema\":true"));
                assertTrue(planJson.contains("\"rules\":[\"href=\\\"schema.rng\\\"\"]"));
        }

        @Test
        void escapesSurrogateCodeUnitsToKeepJsonWellFormed() {
                JsonValidationReportWriter writer = new JsonValidationReportWriter();
                String inputSource = "bad" + '\ud800' + "path";

                String json = writer.writePlan(
                                inputSource,
                                "config.toml",
                                List.of("xml"),
                                1,
                                false,
                                false,
                                0,
                                List.of(),
                                List.of("document.xml"));

                assertTrue(json.contains("\"inputSource\":\"bad\\ud800path\""));
        }
}
