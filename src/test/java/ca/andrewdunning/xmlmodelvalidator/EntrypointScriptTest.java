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
        Path environmentRoot = prepareEnvironment(capturedArgs, null);

        Process process = runEntrypoint(
                environmentRoot,
                Map.of(
                        "XML_MODEL_VALIDATOR_INPUT_FILES",
                        "docs/a file.xml\nnested/b.xml",
                        "XML_MODEL_VALIDATOR_INPUT_JOBS",
                        "0"));

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
