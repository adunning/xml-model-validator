package ca.andrewdunning.xmlmodelvalidator;

import java.io.BufferedReader;
import java.io.IOException;
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
    private final Path directory;
    private final Path fileList;
    private final Path configFile;
    private final List<Path> explicitFiles;
    private final List<String> fileExtensions;
    private final int jobs;
    private final boolean failFast;
    private final boolean directoryMode;

    private ValidationArguments(
            Path directory,
            Path fileList,
            Path configFile,
            List<Path> explicitFiles,
            List<String> fileExtensions,
            int jobs,
            boolean failFast,
            boolean directoryMode) {
        this.directory = directory;
        this.fileList = fileList;
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
     * no file extensions are supplied, directory and file-list discovery defaults
     * to {@code .xml} and also includes an inline rule extension when one is
     * provided.
     */
    static ValidationArguments fromCli(
            Path directory,
            Path fileList,
            Path configFile,
            List<Path> explicitFiles,
            List<String> fileExtensions,
            String inlineRuleExtension,
            int jobs,
            boolean failFast) {
        Path normalizedDirectory = directory == null
                ? null
                : ValidationSupport.resolveAgainstWorkspace(directory);
        Path normalizedFileList = fileList == null
                ? null
                : ValidationSupport.resolveAgainstWorkspace(fileList);
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

        boolean directoryMode = normalizedDirectory != null && normalizedFileList == null && files.isEmpty();
        return new ValidationArguments(
                normalizedDirectory,
                normalizedFileList,
                normalizedConfigFile,
                files,
                normalizedFileExtensions,
                jobs,
                failFast,
                directoryMode);
    }

    /**
     * Resolves the effective set of files to validate from the provided
     * directory, file list, or explicit paths.
     */
    List<Path> resolveFiles() throws IOException {
        if (fileList != null) {
            try (BufferedReader reader = Files.newBufferedReader(fileList, StandardCharsets.UTF_8)) {
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
