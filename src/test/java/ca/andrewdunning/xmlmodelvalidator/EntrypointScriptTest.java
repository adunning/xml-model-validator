package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntrypointScriptTest {
    private static final Path ENTRYPOINT = Path.of("entrypoint.sh").toAbsolutePath().normalize();

    @TempDir
    Path temporaryDirectory;

    @Test
    void filesInputIsSplitByLineAndPreservesSpaces() throws Exception {
        Path capturedArgs = temporaryDirectory.resolve("args.txt");
        Path githubOutput = temporaryDirectory.resolve("github-output.txt");
        Path jsonReport = temporaryDirectory.resolve("report.json");
        Path environmentRoot = prepareEnvironment(capturedArgs, null);

        Process process = runEntrypoint(
                environmentRoot,
                Map.of(
                        "XML_MODEL_VALIDATOR_INPUT_FILES",
                        "docs/a file.xml\nnested/b.xml",
                        "XML_MODEL_VALIDATOR_INPUT_JOBS",
                        "0",
                        "XML_MODEL_VALIDATOR_INPUT_JSON_REPORT_PATH",
                        jsonReport.toString(),
                        "GITHUB_OUTPUT",
                        githubOutput.toString()));

        assertEquals(0, process.waitFor());
        assertEquals(
                List.of(
                        "-jar",
                        jarPath(environmentRoot).toString(),
                        "-j",
                        "0",
                        "docs/a file.xml",
                        "nested/b.xml"),
                Files.readAllLines(capturedArgs, StandardCharsets.UTF_8));
        String output = Files.readString(githubOutput, StandardCharsets.UTF_8);
        assertTrue(output.contains("skipped=false"));
        assertTrue(output.contains("files_checked=2"));
        assertTrue(output.contains("failed_files=0"));
        assertTrue(output.contains("warning_count=0"));
        assertTrue(output.contains("json_report_path=" + jsonReport));
        String report = Files.readString(jsonReport, StandardCharsets.UTF_8);
        assertTrue(report.contains("\"skipped\":false"));
        assertTrue(report.contains("\"filesChecked\":2"));
    }

    @Test
    void filesFromInputMapsToFilesFromFlag() throws Exception {
        Path capturedArgs = temporaryDirectory.resolve("args.txt");
        Path environmentRoot = prepareEnvironment(capturedArgs, null);

        Process process = runEntrypoint(
                environmentRoot,
                Map.of(
                        "XML_MODEL_VALIDATOR_INPUT_FILES_FROM",
                        "manifest.txt",
                        "XML_MODEL_VALIDATOR_INPUT_JOBS",
                        "0"));

        assertEquals(0, process.waitFor());
        assertEquals(
                List.of(
                        "-jar",
                        jarPath(environmentRoot).toString(),
                        "-j",
                        "0",
                        "--files-from",
                        "manifest.txt"),
                Files.readAllLines(capturedArgs, StandardCharsets.UTF_8));
    }

    @Test
    void xmlModelRuleDirectoryBecomesDefaultSelectionDirectory() throws Exception {
        Path capturedArgs = temporaryDirectory.resolve("args.txt");
        Path environmentRoot = prepareEnvironment(capturedArgs, null);

        Process process = runEntrypoint(
                environmentRoot,
                Map.of(
                        "XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_DIRECTORY",
                        "styles",
                        "XML_MODEL_VALIDATOR_INPUT_JOBS",
                        "0"));

        assertEquals(0, process.waitFor());
        assertEquals(
                List.of(
                        "-jar",
                        jarPath(environmentRoot).toString(),
                        "--rule-directory",
                        "styles",
                        "-j",
                        "0",
                        "--directory",
                        "styles"),
                Files.readAllLines(capturedArgs, StandardCharsets.UTF_8));
    }

    @Test
    void changedFilesOnlyUsesFilesFromForGeneratedManifest() throws Exception {
        Path capturedArgs = temporaryDirectory.resolve("args.txt");
        Path environmentRoot = prepareEnvironment(capturedArgs, "changed/one.xml\nchanged/two.xml\n");

        Process process = runEntrypoint(
                environmentRoot,
                Map.of(
                        "XML_MODEL_VALIDATOR_INPUT_CHANGED_FILES_ONLY",
                        "true",
                        "XML_MODEL_VALIDATOR_INPUT_CHANGED_SOURCE",
                        "git",
                        "XML_MODEL_VALIDATOR_INPUT_JOBS",
                        "0",
                        "GITHUB_EVENT_NAME",
                        "workflow_dispatch",
                        "GITHUB_SHA",
                        "deadbeef"));

        assertEquals(0, process.waitFor());
        Path changedFileList = environmentRoot.resolve("runner-temp/xml-model-validator-changed-files.txt");
        assertEquals(
                List.of(
                        "-jar",
                        jarPath(environmentRoot).toString(),
                        "-j",
                        "0",
                        "--files-from",
                        changedFileList.toString()),
                Files.readAllLines(capturedArgs, StandardCharsets.UTF_8));
        assertEquals(
                List.of("changed/one.xml", "changed/two.xml"),
                Files.readAllLines(changedFileList, StandardCharsets.UTF_8));
    }

    @Test
    void changedFilesOnlyWritesNoticeAndStepSummaryWhenNothingMatches() throws Exception {
        Path capturedArgs = temporaryDirectory.resolve("args.txt");
        Path environmentRoot = prepareEnvironment(capturedArgs, "");
        Path stepSummary = temporaryDirectory.resolve("step-summary.md");
        Path jsonReport = temporaryDirectory.resolve("skipped-report.json");

        Process process = runEntrypoint(
                environmentRoot,
                Map.of(
                        "XML_MODEL_VALIDATOR_INPUT_CHANGED_FILES_ONLY",
                        "true",
                        "XML_MODEL_VALIDATOR_INPUT_CHANGED_SOURCE",
                        "git",
                        "XML_MODEL_VALIDATOR_INPUT_JOBS",
                        "0",
                        "GITHUB_EVENT_NAME",
                        "workflow_dispatch",
                        "GITHUB_SHA",
                        "deadbeef",
                        "XML_MODEL_VALIDATOR_INPUT_JSON_REPORT_PATH",
                        jsonReport.toString(),
                        "GITHUB_STEP_SUMMARY",
                        stepSummary.toString(),
                        "GITHUB_OUTPUT",
                        temporaryDirectory.resolve("github-output.txt").toString()));

        assertEquals(0, process.waitFor());
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(stdout.contains("::notice title=XML Validation::Validation skipped"));
        assertTrue(stderr.contains("no changed files matched the configured extensions"));
        String markdown = Files.readString(stepSummary, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("## XML Validation"));
        assertTrue(markdown.contains("Validation was skipped because no changed files matched the configured extensions."));
        String output = Files.readString(temporaryDirectory.resolve("github-output.txt"), StandardCharsets.UTF_8);
        assertTrue(output.contains("skipped=true"));
        assertTrue(output.contains("files_checked=0"));
        assertTrue(output.contains("failed_files=0"));
        assertTrue(output.contains("warning_count=0"));
        assertTrue(output.contains("json_report_path=" + jsonReport));
        String report = Files.readString(jsonReport, StandardCharsets.UTF_8);
        assertTrue(report.contains("\"skipped\":true"));
    }

    @Test
    void rejectsConflictingSelectionInputs() throws Exception {
        Path capturedArgs = temporaryDirectory.resolve("args.txt");
        Path environmentRoot = prepareEnvironment(capturedArgs, null);

        Process process = runEntrypoint(
                environmentRoot,
                Map.of(
                        "XML_MODEL_VALIDATOR_INPUT_DIRECTORY",
                        "docs",
                        "XML_MODEL_VALIDATOR_INPUT_FILES_FROM",
                        "manifest.txt",
                        "XML_MODEL_VALIDATOR_INPUT_JOBS",
                        "0"));

        assertEquals(1, process.waitFor());
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(stderr.contains("choose only one of directory, files_from, files, or changed_files_only"));
        assertTrue(stderr.contains("directory=docs"));
        assertTrue(stderr.contains("files_from=manifest.txt"));
    }

    private Path prepareEnvironment(Path capturedArgs, String gitOutput) throws IOException {
        Path root = temporaryDirectory.resolve("environment");
        Path bin = root.resolve("bin");
        Files.createDirectories(bin);
        writeExecutable(
                bin.resolve("java"),
                """
                        #!/bin/sh
                        printf '%s\n' "$@" > "${CAPTURE_ARGS}"
                        if [ -n "${XML_MODEL_VALIDATOR_SUMMARY_FILE:-}" ]; then
                          printf '%s\n' '{"summary":{"skipped":false,"filesChecked":2,"okFiles":2,"failedFiles":0,"warningCount":0,"elapsedSeconds":0.25},"results":[]}' > "${XML_MODEL_VALIDATOR_SUMMARY_FILE}"
                        fi
                        """);
        writeExecutable(
                bin.resolve("git"),
                gitOutput == null
                        ? """
                                #!/bin/sh
                                exit 1
                                """
                        : """
                                #!/bin/sh
                                cat <<'EOF'
                                %s
                                EOF
                                """.formatted(gitOutput));
        writeExecutable(
                bin.resolve("jq"),
                """
                        #!/bin/sh
                        expression="$2"
                        file="$3"
                        case "${expression}" in
                          .summary.filesChecked)
                            value='"filesChecked":'
                            ;;
                          .summary.failedFiles)
                            value='"failedFiles":'
                            ;;
                          .summary.warningCount)
                            value='"warningCount":'
                            ;;
                          *)
                            exit 1
                            ;;
                        esac
                        sed -n "s/.*${value}\\([0-9][0-9]*\\).*/\\1/p" "${file}"
                        """);
        Path home = root.resolve("home");
        Path cacheJarDirectory = home.resolve(".cache/xml-model-validator/jar");
        Files.createDirectories(cacheJarDirectory);
        Files.writeString(cacheJarDirectory.resolve("xml-model-validator.jar"), "stub", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("workspace"));
        Files.createDirectories(root.resolve("runner-temp"));
        return root;
    }

    private Process runEntrypoint(Path environmentRoot, Map<String, String> extraEnvironment) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("sh", ENTRYPOINT.toString());
        Map<String, String> environment = builder.environment();
        environment.remove("GITHUB_ACTIONS");
        environment.remove("GITHUB_OUTPUT");
        environment.remove("GITHUB_STEP_SUMMARY");
        environment.put("PATH", environmentRoot.resolve("bin") + ":" + environment.getOrDefault("PATH", ""));
        environment.put("HOME", environmentRoot.resolve("home").toString());
        environment.put("RUNNER_TEMP", environmentRoot.resolve("runner-temp").toString());
        environment.put("XML_MODEL_VALIDATOR_WORKSPACE", environmentRoot.resolve("workspace").toString());
        environment.put("CAPTURE_ARGS", temporaryDirectory.resolve("args.txt").toString());
        environment.putAll(extraEnvironment);
        return builder.start();
    }

    private Path jarPath(Path environmentRoot) {
        return environmentRoot.resolve("home/.cache/xml-model-validator/jar/xml-model-validator.jar");
    }

    private void writeExecutable(Path path, String content) throws IOException {
        Files.writeString(path, content.stripIndent(), StandardCharsets.UTF_8);
        path.toFile().setExecutable(true);
    }
}
