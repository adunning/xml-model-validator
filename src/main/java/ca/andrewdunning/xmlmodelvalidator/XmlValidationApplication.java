package ca.andrewdunning.xmlmodelvalidator;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Command-line entry point for validating XML files in a workspace. */
public final class XmlValidationApplication {
    private final ValidationReporter reporter;
    private final XmlFileValidator validator;

    private XmlValidationApplication(
            Map<String, Path> schemaAliases,
            List<XmlModelRule> xmlModelRules,
            boolean checkSchematronSchema,
            SchematronSeverityLevel schematronSeverityThreshold,
            boolean verbose,
            OutputFormat format,
            PrintStream output,
            PrintStream error) {
        boolean githubActions = "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
        OutputFormat effectiveFormat;
        if (format == null) {
            effectiveFormat = OutputFormat.defaultForEnvironment(githubActions);
        } else {
            effectiveFormat = format;
        }
        this.reporter = new ValidationReporter(effectiveFormat, verbose, output, error);
        this.validator =
                new XmlFileValidator(schemaAliases, xmlModelRules, checkSchematronSchema, schematronSeverityThreshold);
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
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setOut(new java.io.PrintWriter(output, true));
        commandLine.setErr(new java.io.PrintWriter(error, true));
        return commandLine.execute(args);
    }

    @Command(
            name = "xml-model-validator",
            mixinStandardHelpOptions = true,
            versionProvider = VersionProvider.class,
            description = "Validate XML files that use xml-model processing instructions.",
            footerHeading = "%nExamples:%n",
            footer = {
                "  ${COMMAND-FULL-NAME} --directory path/to/xml",
                "  ${COMMAND-FULL-NAME} --plan --directory path/to/xml",
                "  find path/to/xml -name '*.xml' -print | ${COMMAND-FULL-NAME} --files-from -",
                "  ${COMMAND-FULL-NAME} --format json path/to/a.xml path/to/b.xml",
                "  ${COMMAND-FULL-NAME} --directory styles --file-extensions csl --config .xml-validator/config.toml",
                "",
                "Exactly one input source is required: --directory, --files-from, or FILES..."
            })
    private static final class CliCommand implements java.util.concurrent.Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @ArgGroup(exclusive = true, multiplicity = "1", heading = "Input source:%n")
        private InputSourceOptions inputSource;

        @Option(
                names = "--file-extensions",
                split = "[,\\s]+",
                description =
                        "File extensions to discover when scanning directories or file lists; a leading period is optional. Defaults to .xml, and also includes an inline rule extension when one is provided.")
        private List<String> fileExtensions = new ArrayList<>();

        @Option(
                names = "--config",
                description =
                        "Optional path to a TOML validator config file containing schema aliases and xml-model rules")
        private Path configFile;

        @ArgGroup(exclusive = false, multiplicity = "0..1", heading = "Inline xml-model rule options:%n")
        private InlineRuleOptions inlineRule;

        @Option(
                names = {"-j", "--jobs"},
                description = "Number of parallel workers to use (0 = auto)",
                defaultValue = "0")
        private void setJobs(int jobs) {
            if (jobs < 0) {
                throw new ParameterException(spec.commandLine(), "--jobs must be 0 or greater");
            }
            this.jobs = jobs;
        }

        private int jobs;

        @Option(names = "--fail-fast", description = "Stop after the first file that fails validation")
        private boolean failFast;

        @Option(names = "--plan", description = "Print the resolved validation plan without running validation")
        private boolean plan;

        @Option(
                names = "--check-schematron-schema",
                description = "Run SchXslt assembled-schema checks before validating documents that use Schematron")
        private boolean checkSchematronSchema;

        @Option(
                names = "--schematron-severity-threshold",
                description =
                        "Skip Schematron assertions with a severity lower than the threshold. One of: INFO, WARNING, ERROR, FATAL.",
                defaultValue = "INFO")
        private SchematronSeverityLevel schematronSeverityThreshold = SchematronSeverityLevel.INFO;

        @Option(names = "--format", description = "Output format: ${COMPLETION-CANDIDATES}")
        private OutputFormat format;

        @Option(
                names = {"-v", "--verbose"},
                description = "Print progress information and successful validation summaries")
        private boolean verbose;

        private PrintStream output;
        private PrintStream error;

        @Override
        public Integer call() throws Exception {
            Path directory;
            if (inputSource == null) {
                directory = null;
            } else {
                directory = inputSource.directory;
            }
            Path filesFrom;
            if (inputSource == null) {
                filesFrom = null;
            } else {
                filesFrom = inputSource.filesFrom;
            }
            List<Path> explicitFiles;
            if (inputSource == null) {
                explicitFiles = List.of();
            } else {
                explicitFiles = inputSource.explicitFiles;
            }
            ValidationArguments arguments = ValidationArguments.fromCli(
                    directory,
                    filesFrom,
                    configFile,
                    explicitFiles,
                    fileExtensions,
                    inlineRule == null ? null : inlineRule.extension,
                    jobs,
                    failFast);

            if (configFile != null && !Files.exists(arguments.configFile())) {
                throw new ParameterException(
                        spec.commandLine(),
                        "Configured validator config file does not exist: " + arguments.configFile());
            }

            ValidatorConfig config = ValidationSupport.loadConfig(arguments.configFile());
            List<XmlModelRule> xmlModelRules = new ArrayList<>(config.xmlModelRules());
            XmlModelRule resolvedInlineRule = buildInlineRule();
            if (resolvedInlineRule != null) {
                xmlModelRules.add(resolvedInlineRule);
            }
            List<Path> files = arguments.resolveFiles();
            if (plan) {
                return emitPlan(arguments, config, xmlModelRules, files);
            }
            if (files.isEmpty()) {
                error.printf(
                        "ERROR: No matching files found to validate for %s with extensions %s.%n",
                        inputSourceDescription(arguments), String.join(", ", arguments.fileExtensions()));
                return 1;
            }

            XmlValidationApplication application = new XmlValidationApplication(
                    config.schemaAliases(),
                    xmlModelRules,
                    checkSchematronSchema,
                    schematronSeverityThreshold,
                    verbose,
                    format,
                    output,
                    error);
            return application.run(arguments, files);
        }

        private int emitPlan(
                ValidationArguments arguments,
                ValidatorConfig config,
                List<XmlModelRule> xmlModelRules,
                List<Path> files) {
            List<String> normalizedFiles =
                    files.stream().map(ValidationSupport::relativize).toList();
            List<String> ruleDescriptions =
                    xmlModelRules.stream().map(XmlModelRule::describe).toList();
            OutputFormat effectiveFormat;
            if (format == null) {
                effectiveFormat = OutputFormat.TEXT;
            } else {
                effectiveFormat = format;
            }
            if (effectiveFormat == OutputFormat.JSON) {
                JsonValidationReportWriter writer = new JsonValidationReportWriter();
                output.println(writer.writePlan(
                        inputSourceDescription(arguments),
                        ValidationSupport.relativize(arguments.configFile()),
                        arguments.fileExtensions(),
                        arguments.jobs(),
                        arguments.failFast(),
                        checkSchematronSchema,
                        schematronSeverityThreshold.cliValue(),
                        config.schemaAliases().size(),
                        ruleDescriptions,
                        normalizedFiles));
                return 0;
            }

            output.printf("Input source: %s%n", inputSourceDescription(arguments));
            output.printf("Config file: %s%n", ValidationSupport.relativize(arguments.configFile()));
            output.printf("File extensions: %s%n", String.join(", ", arguments.fileExtensions()));
            output.printf("Jobs: %d%n", arguments.jobs());
            output.printf("Fail fast: %s%n", arguments.failFast());
            output.printf("Check Schematron schema: %s%n", checkSchematronSchema);
            output.printf("Schematron severity threshold: %s%n", schematronSeverityThreshold.cliValue());
            output.printf("Schema aliases: %d%n", config.schemaAliases().size());
            output.printf("Rules (%d):%n", ruleDescriptions.size());
            for (String ruleDescription : ruleDescriptions) {
                output.printf("  %s%n", ruleDescription);
            }
            output.printf("Files (%d):%n", normalizedFiles.size());
            for (String file : normalizedFiles) {
                output.printf("  %s%n", file);
            }
            return 0;
        }

        private String inputSourceDescription(ValidationArguments arguments) {
            if (arguments.directory() != null) {
                return "directory:" + ValidationSupport.relativize(arguments.directory());
            }
            if (arguments.filesFrom() != null) {
                return "files-from:" + arguments.filesFrom();
            }
            return "files";
        }

        private XmlModelRule buildInlineRule() throws Exception {
            if (inlineRule == null) {
                return null;
            }
            return inlineRule.toRule();
        }

        private static final class InputSourceOptions {
            @Option(
                    names = {"-d", "--directory"},
                    description = "Directory containing matching files to validate recursively")
            private Path directory;

            @Option(
                    names = "--files-from",
                    description = "Read a newline-delimited file list from PATH, or use - to read from standard input")
            private Path filesFrom;

            @Parameters(arity = "1..*", paramLabel = "FILES", description = "Explicit files to validate")
            private List<Path> explicitFiles = new ArrayList<>();
        }

        private static final class InlineRuleOptions {
            @Option(
                    names = "--rule-mode",
                    description = "Optional inline xml-model rule mode for this run: ${COMPLETION-CANDIDATES}")
            private XmlModelRuleMode mode = XmlModelRuleMode.FALLBACK;

            @Option(names = "--rule-directory", description = "Optional directory scope for an inline xml-model rule")
            private Path directory;

            @Option(
                    names = "--rule-extension",
                    description =
                            "Optional file extension scope for an inline xml-model rule; a leading period is optional")
            private String extension;

            @Option(
                    names = "--xml-model-declaration",
                    required = true,
                    description =
                            "Inline xml-model declaration to apply for the configured rule; repeat for multiple declarations")
            private List<String> declarations = new ArrayList<>();

            private XmlModelRule toRule() throws Exception {
                Path resolvedDirectory;
                if (directory == null) {
                    resolvedDirectory = null;
                } else {
                    resolvedDirectory = ValidationSupport.resolveAgainstWorkspace(directory);
                }
                List<XmlModelEntry> entries = new ArrayList<>();
                for (String declaration : declarations) {
                    XmlModelEntry parsed = XmlModelParser.parseDeclaration(declaration);
                    entries.add(ValidationSupport.createConfiguredXmlModelEntry(
                            parsed.href(), parsed.schemaTypeNamespace(), parsed.type(), parsed.phase()));
                }
                return new XmlModelRule(resolvedDirectory, extension, mode, entries, 1);
            }
        }
    }

    private static final class VersionProvider implements IVersionProvider {
        @Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public String[] getVersion() {
            String version = XmlValidationApplication.class.getPackage().getImplementationVersion();
            if (version == null || version.isBlank()) {
                version = "dev";
            }
            return new String[] {spec.name() + " " + version};
        }
    }

    private int run(ValidationArguments arguments, List<Path> files) throws Exception {
        int workers;
        if (arguments.jobs() > 0) {
            workers = arguments.jobs();
        } else {
            workers = Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        Instant start = Instant.now();

        reporter.emitStartup(files, workers, arguments.directoryMode());
        List<ValidationResult> results = validateFiles(arguments, files, workers);
        return reporter.emitSummary(results, Duration.between(start, Instant.now()));
    }

    private List<ValidationResult> validateFiles(ValidationArguments arguments, List<Path> files, int workers)
            throws Exception {
        if (arguments.failFast()) {
            return validateWithFailFast(files, workers);
        }
        return validateAll(files, workers);
    }

    private List<ValidationResult> validateAll(List<Path> files, int workers) throws Exception {
        try (ValidationExecutor executor = new ValidationExecutor(validator, workers)) {
            return executor.validateAll(files);
        }
    }

    private List<ValidationResult> validateWithFailFast(List<Path> files, int workers) throws Exception {
        try (ValidationExecutor executor = new ValidationExecutor(validator, workers)) {
            return executor.validateUntilFailure(files);
        }
    }

    /** Executes file validations concurrently while preserving deterministic output ordering. */
    private static final class ValidationExecutor implements AutoCloseable {
        private final ExecutorService executorService;
        private final XmlFileValidator validator;
        private final int workers;

        private ValidationExecutor(XmlFileValidator validator, int workers) {
            this.executorService =
                    Executors.newFixedThreadPool(workers, Thread.ofVirtual().factory());
            this.validator = validator;
            this.workers = workers;
        }

        private List<ValidationResult> validateAll(List<Path> files) throws Exception {
            List<ValidationResult> results = new ArrayList<>();
            List<Future<ValidationResult>> futures = new ArrayList<>();
            for (Path file : files) {
                futures.add(executorService.submit(() -> validateFile(file)));
            }
            for (Future<ValidationResult> future : futures) {
                results.add(future.get());
            }
            results.sort(Comparator.comparing(result -> result.file().toString()));
            return results;
        }

        private List<ValidationResult> validateUntilFailure(List<Path> files) throws Exception {
            if (workers == 1) {
                return validateUntilFailureSerially(files);
            }

            List<ValidationResult> results = new ArrayList<>();
            List<Future<ValidationResult>> futures = new ArrayList<>();
            CompletionService<ValidationResult> completionService = new ExecutorCompletionService<>(executorService);
            int submitted = 0;
            int completed = 0;
            int maxInFlight = Math.min(workers, files.size());

            while (submitted < files.size() && submitted < maxInFlight) {
                int fileIndex = submitted;
                futures.add(completionService.submit(() -> validateFile(files.get(fileIndex))));
                submitted += 1;
            }

            boolean failureSeen = false;
            while (completed < submitted) {
                ValidationResult result = completionService.take().get();
                completed += 1;
                results.add(result);
                if (!result.ok()) {
                    failureSeen = true;
                    break;
                }

                if (submitted < files.size()) {
                    int fileIndex = submitted;
                    futures.add(completionService.submit(() -> validateFile(files.get(fileIndex))));
                    submitted += 1;
                }
            }

            if (failureSeen) {
                for (Future<ValidationResult> future : futures) {
                    future.cancel(true);
                }
                results.sort(Comparator.comparing(result -> result.file().toString()));
                return results;
            }

            while (completed < submitted) {
                try {
                    results.add(completionService.take().get());
                    completed += 1;
                    if (submitted < files.size()) {
                        int fileIndex = submitted;
                        futures.add(completionService.submit(() -> validateFile(files.get(fileIndex))));
                        submitted += 1;
                    }
                } catch (CancellationException ignored) {
                    completed += 1;
                }
            }

            results.sort(Comparator.comparing(result -> result.file().toString()));
            return results;
        }

        private List<ValidationResult> validateUntilFailureSerially(List<Path> files) throws InterruptedException {
            List<ValidationResult> results = new ArrayList<>();

            for (Path file : files) {
                ValidationResult result = validateFile(file);
                results.add(result);
                if (!result.ok()) {
                    break;
                }
            }

            return results;
        }

        private ValidationResult validateFile(Path file) throws InterruptedException {
            return validator.validate(file);
        }

        @Override
        public void close() {
            executorService.shutdownNow();
        }
    }
}
