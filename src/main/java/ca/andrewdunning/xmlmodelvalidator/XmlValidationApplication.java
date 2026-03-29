package ca.andrewdunning.xmlmodelvalidator;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command-line entry point for validating XML files in a workspace.
 */
public final class XmlValidationApplication {
    private final ValidationReporter reporter;
    private final XmlFileValidator validator;

    private XmlValidationApplication(Map<String, Path> schemaAliases) {
        this.reporter = new ValidationReporter();
        this.validator = new XmlFileValidator(schemaAliases);
    }

    public static void main(String[] args) throws Exception {
        int exitCode = execute(args, System.out, System.err);
        System.exit(exitCode);
    }

    static int execute(String[] args, PrintStream output, PrintStream error) throws Exception {
        CliCommand command = new CliCommand();
        command.output = output;
        command.error = error;
        CommandLine commandLine = new CommandLine(command);
        commandLine.setOut(new java.io.PrintWriter(output, true));
        commandLine.setErr(new java.io.PrintWriter(error, true));
        return commandLine.execute(args);
    }

    private static String versionString() {
        String version = XmlValidationApplication.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "dev";
        }
        return "xml-model-validator " + version;
    }

    @Command(name = "xml-model-validator", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class, description = "Validate XML files that use xml-model processing instructions")
    private static final class CliCommand implements java.util.concurrent.Callable<Integer> {
        @Option(names = { "-d", "--directory" }, description = "Directory containing matching files to validate recursively")
        private Path directory;

        @Option(names = "--file-list", description = "Path to a newline-delimited file list")
        private Path fileList;

        @Option(
                names = "--file-extensions",
                split = "[,\\s]+",
                defaultValue = ".xml",
                description = "File extensions to discover when scanning directories or file lists; a leading period is optional (default: ${DEFAULT-VALUE})")
        private List<String> fileExtensions = new ArrayList<>();

        @Option(names = "--schema-aliases", description = "Optional path to a schema alias TSV file")
        private Path schemaAliases;

        @Option(names = { "-j",
                "--jobs" }, description = "Number of parallel workers to use (0 = auto)", defaultValue = "0")
        private int jobs;

        @Option(names = "--fail-fast", description = "Stop after the first file that fails validation")
        private boolean failFast;

        @Parameters(arity = "0..*", paramLabel = "FILES", description = "Explicit files to validate")
        private List<Path> explicitFiles = new ArrayList<>();

        private PrintStream output;
        private PrintStream error;

        @Override
        public Integer call() throws Exception {
            ValidationArguments arguments = ValidationArguments.fromCli(
                    directory,
                    fileList,
                    schemaAliases,
                    explicitFiles,
                    fileExtensions,
                    jobs,
                    failFast);

            List<Path> files = arguments.resolveFiles();
            if (files.isEmpty()) {
                error.println("WARNING: No matching files found to validate.");
                return 0;
            }

            XmlValidationApplication application = new XmlValidationApplication(
                    ValidationSupport.loadSchemaAliases(arguments.schemaAliasesFile()));
            return application.run(arguments, files);
        }
    }

    private static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { versionString() };
        }
    }

    private int run(ValidationArguments arguments, List<Path> files) throws Exception {
        int workers = arguments.jobs() > 0
                ? arguments.jobs()
                : Math.max(1, Runtime.getRuntime().availableProcessors());
        Instant start = Instant.now();

        reporter.emitStartup(files, workers, arguments.directoryMode());
        List<ValidationResult> results = validateFiles(arguments, files, workers);
        return reporter.emitSummary(results, Duration.between(start, Instant.now()));
    }

    private List<ValidationResult> validateFiles(
            ValidationArguments arguments,
            List<Path> files,
            int workers) throws Exception {
        if (arguments.failFast()) {
            return validateWithFailFast(files, workers);
        }
        return validateAll(files, workers);
    }

    private List<ValidationResult> validateAll(List<Path> files, int workers) throws Exception {
        try (ValidationExecutor executor = new ValidationExecutor(Executors.newFixedThreadPool(workers), validator)) {
            return executor.validateAll(files);
        }
    }

    private List<ValidationResult> validateWithFailFast(List<Path> files, int workers) throws Exception {
        try (ValidationExecutor executor = new ValidationExecutor(Executors.newFixedThreadPool(workers), validator)) {
            return executor.validateUntilFailure(files);
        }
    }

    /**
     * Executes file validations concurrently while preserving deterministic output
     * ordering.
     */
    private static final class ValidationExecutor implements AutoCloseable {
        private final ExecutorService executorService;
        private final XmlFileValidator validator;

        private ValidationExecutor(ExecutorService executorService, XmlFileValidator validator) {
            this.executorService = executorService;
            this.validator = validator;
        }

        private List<ValidationResult> validateAll(List<Path> files) throws Exception {
            List<ValidationResult> results = new ArrayList<>();
            List<java.util.concurrent.Future<ValidationResult>> futures = new ArrayList<>();
            for (Path file : files) {
                futures.add(executorService.submit(() -> validator.validate(file)));
            }
            for (java.util.concurrent.Future<ValidationResult> future : futures) {
                results.add(future.get());
            }
            results.sort(Comparator.comparing(result -> result.file().toString()));
            return results;
        }

        private List<ValidationResult> validateUntilFailure(List<Path> files) throws Exception {
            List<ValidationResult> results = new ArrayList<>();
            List<java.util.concurrent.Future<ValidationResult>> futures = new ArrayList<>();
            java.util.concurrent.CompletionService<ValidationResult> completionService = new java.util.concurrent.ExecutorCompletionService<>(
                    executorService);

            for (Path file : files) {
                futures.add(completionService.submit(() -> validator.validate(file)));
            }

            boolean failureSeen = false;
            for (int index = 0; index < files.size(); index += 1) {
                ValidationResult result = completionService.take().get();
                results.add(result);
                if (!result.ok()) {
                    failureSeen = true;
                    break;
                }
            }

            if (failureSeen) {
                for (java.util.concurrent.Future<ValidationResult> future : futures) {
                    future.cancel(true);
                }
            } else {
                for (int index = results.size(); index < files.size(); index += 1) {
                    results.add(completionService.take().get());
                }
            }

            results.sort(Comparator.comparing(result -> result.file().toString()));
            return results;
        }

        @Override
        public void close() {
            executorService.shutdownNow();
        }
    }
}
