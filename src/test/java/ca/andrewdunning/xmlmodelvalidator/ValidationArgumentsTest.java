package ca.andrewdunning.xmlmodelvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidationArgumentsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesDirectoryRecursivelyForXmlFilesOnlyByDefault() throws Exception {
        Files.writeString(temporaryDirectory.resolve("a.xml"), "<root/>", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("a.csl"), "<root/>", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("b.txt"), "ignore", StandardCharsets.UTF_8);
        Files.createDirectories(temporaryDirectory.resolve("nested"));
        Files.writeString(temporaryDirectory.resolve("nested/c.xml"), "<root/>", StandardCharsets.UTF_8);

        ValidationArguments arguments = ValidationArguments.fromCli(
                temporaryDirectory,
                null,
                null,
                List.of(),
                List.of(),
                null,
                0,
                false);

        List<Path> files = arguments.resolveFiles();

        assertEquals(
                List.of(
                        temporaryDirectory.resolve("a.xml").toAbsolutePath().normalize(),
                        temporaryDirectory.resolve("nested/c.xml").toAbsolutePath().normalize()),
                files);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void resolvesFilesFromManifestSources(boolean useStandardInput) throws Exception {
        Path listed = temporaryDirectory.resolve("listed.xml");
        Files.writeString(listed, "<root/>", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("other.xml"), "<root/>", StandardCharsets.UTF_8);
        Path fileList = useStandardInput
                ? Path.of("-")
                : temporaryDirectory.resolve("files.txt");
        if (!useStandardInput) {
            Files.writeString(fileList, listed.toString() + "\n", StandardCharsets.UTF_8);
        }

        ValidationArguments arguments = ValidationArguments.fromCli(
                useStandardInput ? null : temporaryDirectory,
                fileList,
                null,
                List.of(),
                List.of(),
                null,
                0,
                false);

        List<Path> files = arguments.resolveFiles(new ByteArrayInputStream(
                (listed + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));

        assertEquals(List.of(listed.toAbsolutePath().normalize()), files);
    }

    @Test
    void sortsExplicitFiles() {
        Path first = temporaryDirectory.resolve("a.xml");
        Path second = temporaryDirectory.resolve("z.xml");

        ValidationArguments arguments = ValidationArguments.fromCli(
                null,
                null,
                null,
                List.of(second, first),
                List.of(),
                null,
                0,
                false);

        List<Path> files;
        try {
            files = arguments.resolveFiles();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }

        assertAll(
                () -> assertEquals(
                        List.of(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
                        files),
                () -> assertTrue(arguments.configFile().isAbsolute()));
    }

    @Test
    void resolvesDirectoryRecursivelyForConfiguredExtensions() throws Exception {
        Files.writeString(temporaryDirectory.resolve("a.csl"), "<root/>", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("b.xml"), "<root/>", StandardCharsets.UTF_8);
        Files.createDirectories(temporaryDirectory.resolve("nested"));
        Files.writeString(temporaryDirectory.resolve("nested/c.CSL"), "<root/>", StandardCharsets.UTF_8);

        ValidationArguments arguments = ValidationArguments.fromCli(
                temporaryDirectory,
                null,
                null,
                List.of(),
                List.of("csl"),
                null,
                0,
                false);

        List<Path> files = arguments.resolveFiles();

        assertEquals(
                List.of(
                        temporaryDirectory.resolve("a.csl").toAbsolutePath().normalize(),
                        temporaryDirectory.resolve("nested/c.CSL").toAbsolutePath().normalize()),
                files);
    }

    @Test
    void filtersFileListByConfiguredExtensions() throws Exception {
        Path included = temporaryDirectory.resolve("included.csl");
        Path excluded = temporaryDirectory.resolve("excluded.xml");
        Files.writeString(included, "<root/>", StandardCharsets.UTF_8);
        Files.writeString(excluded, "<root/>", StandardCharsets.UTF_8);
        Path fileList = temporaryDirectory.resolve("files.txt");
        Files.writeString(
                fileList,
                included + "\n" + excluded + "\n",
                StandardCharsets.UTF_8);

        ValidationArguments arguments = ValidationArguments.fromCli(
                null,
                fileList,
                null,
                List.of(),
                List.of(".csl"),
                null,
                0,
                false);

        List<Path> files = arguments.resolveFiles();

        assertEquals(List.of(included.toAbsolutePath().normalize()), files);
    }

    @ParameterizedTest
    @ValueSource(strings = { "csl", ".csl" })
    void normalizesConfiguredExtensionsForDirectoryDiscovery(String extension) throws Exception {
        Files.writeString(temporaryDirectory.resolve("a.csl"), "<root/>", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("b.xml"), "<root/>", StandardCharsets.UTF_8);

        ValidationArguments arguments = ValidationArguments.fromCli(
                temporaryDirectory,
                null,
                null,
                List.of(),
                List.of(extension),
                null,
                0,
                false);

        List<Path> files = arguments.resolveFiles();

        assertEquals(
                List.of(temporaryDirectory.resolve("a.csl").toAbsolutePath().normalize()),
                files);
    }

    @Test
    void resolvesConfiguredConfigFileAgainstWorkspace() {
        Path configFile = temporaryDirectory.resolve("config/config.toml");

        ValidationArguments arguments = ValidationArguments.fromCli(
                null,
                null,
                configFile,
                List.of(),
                List.of(),
                null,
                0,
                false);

        assertEquals(
                configFile.toAbsolutePath().normalize(),
                arguments.configFile());
    }

    @Test
    void addsInlineRuleExtensionToDefaultXmlDiscoveryWhenUnset() throws Exception {
        Files.writeString(temporaryDirectory.resolve("a.csl"), "<root/>", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("b.xml"), "<root/>", StandardCharsets.UTF_8);

        ValidationArguments arguments = ValidationArguments.fromCli(
                temporaryDirectory,
                null,
                null,
                List.of(),
                List.of(),
                "csl",
                0,
                false);

        List<Path> files = arguments.resolveFiles();

        assertEquals(
                List.of(
                        temporaryDirectory.resolve("a.csl").toAbsolutePath().normalize(),
                        temporaryDirectory.resolve("b.xml").toAbsolutePath().normalize()),
                files);
    }

    @Test
    void prefersExplicitFileExtensionsOverInlineRuleExtensionInference() throws Exception {
        Files.writeString(temporaryDirectory.resolve("a.csl"), "<root/>", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("b.xml"), "<root/>", StandardCharsets.UTF_8);

        ValidationArguments arguments = ValidationArguments.fromCli(
                temporaryDirectory,
                null,
                null,
                List.of(),
                List.of("xml"),
                "csl",
                0,
                false);

        List<Path> files = arguments.resolveFiles();

        assertEquals(
                List.of(temporaryDirectory.resolve("b.xml").toAbsolutePath().normalize()),
                files);
    }
}
