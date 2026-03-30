package ca.andrewdunning.xmlmodelvalidator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable command-line arguments for the validator application.
 */
final class ValidationArguments {
    private static final Path STANDARD_INPUT = Path.of("-");

    private final Path directory;
    private final Path filesFrom;
    private final Path configFile;
    private final List<Path> explicitFiles;
    private final List<String> fileExtensions;
    private final int jobs;
    private final boolean failFast;
    private final boolean directoryMode;

    private ValidationArguments(
            Path directory,
            Path filesFrom,
            Path configFile,
            List<Path> explicitFiles,
            List<String> fileExtensions,
            int jobs,
            boolean failFast,
            boolean directoryMode) {
        this.directory = directory;
        this.filesFrom = filesFrom;
        this.configFile = configFile;
        this.explicitFiles = explicitFiles;
        this.fileExtensions = fileExtensions;
        this.jobs = jobs;
        this.failFast = failFast;
        this.directoryMode = directoryMode;
    }

    /**
     * Builds normalized runtime arguments from CLI option values.
     *
     * Explicit file extensions take precedence over inline rule extensions. When
     * no file extensions are supplied, directory and files-from discovery defaults
     * to {@code .xml} and also includes an inline rule extension when one is
     * provided.
     */
    static ValidationArguments fromCli(
            Path directory,
            Path filesFrom,
            Path configFile,
            List<Path> explicitFiles,
            List<String> fileExtensions,
            String inlineRuleExtension,
            int jobs,
            boolean failFast) {
        Path normalizedDirectory = directory == null
                ? null
                : ValidationSupport.resolveAgainstWorkspace(directory);
        Path normalizedFilesFrom = normalizeFilesFrom(filesFrom);
        Path normalizedConfigFile = configFile == null
                ? ValidationSupport.DEFAULT_CONFIG_FILE
                : ValidationSupport.resolveAgainstWorkspace(configFile);

        List<Path> files = new ArrayList<>();
        for (Path rawFile : explicitFiles) {
            files.add(ValidationSupport.resolveAgainstWorkspace(rawFile));
        }
        List<String> effectiveFileExtensions = fileExtensions;
        if (effectiveFileExtensions == null || effectiveFileExtensions.isEmpty()) {
            effectiveFileExtensions = inlineRuleExtension == null || inlineRuleExtension.isBlank()
                    ? List.of(".xml")
                    : List.of(".xml", inlineRuleExtension);
        }
        List<String> normalizedFileExtensions = normalizeFileExtensions(effectiveFileExtensions);

        boolean directoryMode = normalizedDirectory != null;
        return new ValidationArguments(
                normalizedDirectory,
                normalizedFilesFrom,
                normalizedConfigFile,
                files,
                normalizedFileExtensions,
                jobs,
                failFast,
                directoryMode);
    }

    /**
     * Resolves the effective set of files to validate from the provided
     * directory, files-from source, or explicit paths.
     */
    List<Path> resolveFiles() throws IOException {
        return resolveFiles(System.in);
    }

    List<Path> resolveFiles(InputStream standardInput) throws IOException {
        if (filesFrom != null) {
            try (BufferedReader reader = openFilesFromReader(standardInput)) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .map(Path::of)
                        .map(ValidationSupport::resolveAgainstWorkspace)
                        .filter(this::matchesConfiguredExtension)
                        .sorted()
                        .toList();
            }
        }
        if (directory != null && explicitFiles.isEmpty()) {
            try (var stream = Files.walk(directory)) {
                return stream
                        .filter(path -> Files.isRegularFile(path) && matchesConfiguredExtension(path))
                        .map(path -> path.toAbsolutePath().normalize())
                        .sorted()
                        .toList();
            }
        }
        return explicitFiles.stream()
                .map(ValidationSupport::resolveAgainstWorkspace)
                .sorted()
                .toList();
    }

    private static Path normalizeFilesFrom(Path filesFrom) {
        if (filesFrom == null) {
            return null;
        }
        if (STANDARD_INPUT.equals(filesFrom)) {
            return STANDARD_INPUT;
        }
        return ValidationSupport.resolveAgainstWorkspace(filesFrom);
    }

    private BufferedReader openFilesFromReader(InputStream standardInput) throws IOException {
        if (STANDARD_INPUT.equals(filesFrom)) {
            return new BufferedReader(new InputStreamReader(standardInput, StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(filesFrom, StandardCharsets.UTF_8);
    }

    private static List<String> normalizeFileExtensions(List<String> fileExtensions) {
        if (fileExtensions == null || fileExtensions.isEmpty()) {
            return List.of(".xml");
        }
        return fileExtensions.stream()
                .map(String::trim)
                .filter(extension -> !extension.isEmpty())
                .map(extension -> extension.startsWith(".") ? extension : "." + extension)
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private boolean matchesConfiguredExtension(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileExtensions.stream().anyMatch(filename::endsWith);
    }

    Path configFile() {
        return configFile;
    }

    int jobs() {
        return jobs;
    }

    boolean failFast() {
        return failFast;
    }

    boolean directoryMode() {
        return directoryMode;
    }
}
