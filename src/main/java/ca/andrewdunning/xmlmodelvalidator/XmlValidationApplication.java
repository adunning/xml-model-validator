package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        ValidationArguments arguments = ValidationArguments.parse(args);
        List<Path> files = arguments.resolveFiles();
        if (files.isEmpty()) {
            System.err.println("WARNING: No XML files found to validate.");
            return;
        }

        XmlValidationApplication application = new XmlValidationApplication(
                ValidationSupport.loadSchemaAliases(arguments.schemaAliasesFile()));
        int exitCode = application.run(arguments, files);
        System.exit(exitCode);
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
     * Executes file validations concurrently while preserving deterministic output ordering.
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
