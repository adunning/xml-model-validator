package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntrypointScriptTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void infersFileExtensionsFromInlineRuleExtensionWhenUnset() throws Exception {
        Path outputFile = temporaryDirectory.resolve("args.txt");
        Path fakeJava = writeExecutable(
                "bin/java",
                """
                        #!/bin/sh
                        printf '%%s\n' "$@" > "%s"
                        """.formatted(outputFile));
        Path cacheHome = temporaryDirectory.resolve("cache");
        Path jarPath = cacheHome.resolve("jar/xml-model-validator.jar");
        Files.createDirectories(jarPath.getParent());
        Files.writeString(jarPath, "stub", StandardCharsets.UTF_8);
        Path workspace = temporaryDirectory.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("document.csl"), "<root/>", StandardCharsets.UTF_8);
        ProcessBuilder processBuilder = new ProcessBuilder("sh", "entrypoint.sh");
        processBuilder.directory(Path.of("").toAbsolutePath().normalize().toFile());
        processBuilder.environment().putAll(Map.of(
                "PATH", fakeJava.getParent().toString() + ":" + System.getenv("PATH"),
                "XML_MODEL_VALIDATOR_CACHE_HOME", cacheHome.toString(),
                "XML_MODEL_VALIDATOR_WORKSPACE", workspace.toString(),
                "RUNNER_TEMP", temporaryDirectory.resolve("runner-temp").toString(),
                "XML_MODEL_VALIDATOR_INPUT_DIRECTORY", workspace.toString(),
                "XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_MODE", "fallback",
                "XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_DIRECTORY", "styles",
                "XML_MODEL_VALIDATOR_INPUT_XML_MODEL_RULE_EXTENSION", "csl",
                "XML_MODEL_VALIDATOR_INPUT_XML_MODEL_DECLARATIONS",
                "href=\"https://example.org/schema.rng\" schematypens=\"http://relaxng.org/ns/structure/1.0\""));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        int exitCode = process.waitFor();
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exitCode == 0, "Expected entrypoint execution to succeed: " + processOutput);
        String arguments = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertTrue(arguments.contains("--file-extensions"));
        assertTrue(arguments.contains("csl"));
    }

    private Path writeExecutable(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.stripIndent(), StandardCharsets.UTF_8);
        file.toFile().setExecutable(true);
        return file;
    }
}
