package ca.andrewdunning.xmlmodelvalidator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Immutable command-line arguments for the validator application.
 */
final class ValidationArguments {
    private final Path directory;
    private final Path fileList;
    private final Path schemaAliasesFile;
    private final List<Path> explicitFiles;
    private final int jobs;
    private final boolean failFast;
    private final boolean directoryMode;

    private ValidationArguments(
            Path directory,
            Path fileList,
            Path schemaAliasesFile,
            List<Path> explicitFiles,
            int jobs,
            boolean failFast,
            boolean directoryMode) {
        this.directory = directory;
        this.fileList = fileList;
        this.schemaAliasesFile = schemaAliasesFile;
        this.explicitFiles = explicitFiles;
        this.jobs = jobs;
        this.failFast = failFast;
        this.directoryMode = directoryMode;
    }

    /**
     * Parses CLI flags and positional file arguments into a normalized argument object.
     */
    static ValidationArguments parse(String[] args) {
        Path directory = null;
        Path fileList = null;
        Path schemaAliasesFile = ValidationSupport.DEFAULT_SCHEMA_ALIASES_FILE;
        List<Path> files = new ArrayList<>();
        int jobs = 0;
        boolean failFast = false;

        for (int index = 0; index < args.length; index += 1) {
            String arg = args[index];
            switch (arg) {
                case "-d", "--directory" ->
                    directory = ValidationSupport.resolveAgainstWorkspace(Paths.get(args[++index]));
                case "--file-list" -> fileList = ValidationSupport.resolveAgainstWorkspace(Paths.get(args[++index]));
                case "--schema-aliases" ->
                    schemaAliasesFile = ValidationSupport.resolveAgainstWorkspace(Paths.get(args[++index]));
                case "-j", "--jobs" -> jobs = Integer.parseInt(args[++index]);
                case "--fail-fast" -> failFast = true;
                default -> files.add(ValidationSupport.resolveAgainstWorkspace(Paths.get(arg)));
            }
        }

        boolean directoryMode = directory != null && fileList == null && files.isEmpty();
        return new ValidationArguments(directory, fileList, schemaAliasesFile, files, jobs, failFast, directoryMode);
    }

    /**
     * Resolves the effective set of XML files to validate from the provided directory, file list, or
     * explicit paths.
     */
    List<Path> resolveFiles() throws IOException {
        if (fileList != null) {
            try (BufferedReader reader = Files.newBufferedReader(fileList, StandardCharsets.UTF_8)) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .map(Paths::get)
                        .map(ValidationSupport::resolveAgainstWorkspace)
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        if (directory != null && explicitFiles.isEmpty()) {
            try (var stream = Files.walk(directory)) {
                return stream
                        .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".xml"))
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
