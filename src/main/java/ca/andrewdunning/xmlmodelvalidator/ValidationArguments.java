package ca.andrewdunning.xmlmodelvalidator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Immutable command-line arguments for the validator application.
 */
final class ValidationArguments {
    private final Path directory;
    private final Path fileList;
    private final Path schemaAliasesFile;
    private final List<Path> explicitFiles;
    private final List<String> fileExtensions;
    private final int jobs;
    private final boolean failFast;
    private final boolean directoryMode;

    private ValidationArguments(
            Path directory,
            Path fileList,
            Path schemaAliasesFile,
            List<Path> explicitFiles,
            List<String> fileExtensions,
            int jobs,
            boolean failFast,
            boolean directoryMode) {
        this.directory = directory;
        this.fileList = fileList;
        this.schemaAliasesFile = schemaAliasesFile;
        this.explicitFiles = explicitFiles;
        this.fileExtensions = fileExtensions;
        this.jobs = jobs;
        this.failFast = failFast;
        this.directoryMode = directoryMode;
    }

    /**
     * Builds normalized runtime arguments from CLI option values.
     */
    static ValidationArguments fromCli(
            Path directory,
            Path fileList,
            Path schemaAliases,
            List<Path> explicitFiles,
            List<String> fileExtensions,
            int jobs,
            boolean failFast) {
        Path normalizedDirectory = directory == null
                ? null
                : ValidationSupport.resolveAgainstWorkspace(directory);
        Path normalizedFileList = fileList == null
                ? null
                : ValidationSupport.resolveAgainstWorkspace(fileList);
        Path normalizedSchemaAliases = schemaAliases == null
                ? ValidationSupport.DEFAULT_SCHEMA_ALIASES_FILE
                : ValidationSupport.resolveAgainstWorkspace(schemaAliases);

        List<Path> files = new ArrayList<>();
        for (Path rawFile : explicitFiles) {
            files.add(ValidationSupport.resolveAgainstWorkspace(rawFile));
        }
        List<String> normalizedFileExtensions = normalizeFileExtensions(fileExtensions);

        boolean directoryMode = normalizedDirectory != null && normalizedFileList == null && files.isEmpty();
        return new ValidationArguments(
                normalizedDirectory,
                normalizedFileList,
                normalizedSchemaAliases,
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
                        .map(Paths::get)
                        .map(ValidationSupport::resolveAgainstWorkspace)
                        .filter(this::matchesConfiguredExtension)
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        if (directory != null && explicitFiles.isEmpty()) {
            try (var stream = Files.walk(directory)) {
                return stream
                        .filter(path -> Files.isRegularFile(path) && matchesConfiguredExtension(path))
                        .map(path -> path.toAbsolutePath().normalize())
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        return explicitFiles.stream()
                .map(ValidationSupport::resolveAgainstWorkspace)
                .sorted()
                .collect(Collectors.toList());
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

    Path schemaAliasesFile() {
        return schemaAliasesFile;
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
