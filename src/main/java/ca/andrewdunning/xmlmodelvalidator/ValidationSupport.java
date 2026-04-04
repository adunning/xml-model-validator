package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared constants and utility methods for workspace, cache, and GitHub Actions integration. */
final class ValidationSupport {
    static final String RELAXNG_NS = "http://relaxng.org/ns/structure/1.0";
    static final String SCHEMATRON_NS = "http://purl.oclc.org/dsdl/schematron";
    static final String SVRL_NS = "http://purl.oclc.org/dsdl/svrl";
    static final Path WORKSPACE_ROOT = resolveWorkspaceRoot();
    static final Path CACHE_ROOT = resolveCacheRoot();
    static final Path SCHEMATRON_CACHE_DIR = CACHE_ROOT.resolve("schematron");
    static final Path SCHEMA_DOWNLOAD_CACHE_DIR = CACHE_ROOT.resolve("schema-downloads");
    static final Path DEFAULT_CONFIG_FILE = WORKSPACE_ROOT.resolve(".xml-validator/config.toml");

    private ValidationSupport() {}

    static String escapeMessage(String value) {
        return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    static String escapeProperty(String value) {
        return escapeMessage(value).replace(":", "%3A").replace(",", "%2C");
    }

    static String relativize(Path path) {
        try {
            return WORKSPACE_ROOT.relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException exception) {
            return path.toString();
        }
    }

    static Path resolveAgainstWorkspace(Path path) {
        if (path.isAbsolute()) {
            return path.normalize().toAbsolutePath();
        }
        return WORKSPACE_ROOT.resolve(path).normalize().toAbsolutePath();
    }

    static ValidatorConfig loadConfig(Path configFile) throws IOException {
        return ValidatorConfigParser.parse(configFile);
    }

    static XmlModelEntry createConfiguredXmlModelEntry(
            String href, String schemaTypeNamespace, String type, String phase) {
        return new XmlModelEntry(normalizeConfiguredHref(href), schemaTypeNamespace, type, phase);
    }

    static List<String> extractSchemaLocations(String noNamespaceSchemaLocation, String schemaLocation) {
        List<String> schemaLocations = new ArrayList<>();
        if (noNamespaceSchemaLocation != null && !noNamespaceSchemaLocation.isBlank()) {
            schemaLocations.add(noNamespaceSchemaLocation.trim());
        }
        if (schemaLocation == null || schemaLocation.isBlank()) {
            return List.copyOf(schemaLocations);
        }

        String[] parts = schemaLocation.trim().split("\\s+");
        for (int index = 1; index < parts.length; index += 2) {
            schemaLocations.add(parts[index]);
        }
        return List.copyOf(schemaLocations);
    }

    private static String normalizeConfiguredHref(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        Path path = Path.of(href);
        if (path.isAbsolute()) {
            return path.normalize().toAbsolutePath().toString();
        }
        return WORKSPACE_ROOT.resolve(path).normalize().toAbsolutePath().toString();
    }

    private static Path resolveWorkspaceRoot() {
        String configuredRoot = System.getenv("XML_MODEL_VALIDATOR_WORKSPACE");
        if (configuredRoot == null || configuredRoot.isBlank()) {
            configuredRoot = System.getenv("GITHUB_WORKSPACE");
        }
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(configuredRoot).toAbsolutePath().normalize();
    }

    private static Path resolveCacheRoot() {
        String configuredRoot = System.getenv("XML_MODEL_VALIDATOR_CACHE_HOME");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            Path configuredPath = Path.of(configuredRoot).toAbsolutePath().normalize();
            if (canUseCacheRoot(configuredPath)) {
                return configuredPath;
            }
        }

        String home = System.getenv("HOME");
        if (home != null && !home.isBlank()) {
            Path homeCachePath = Path.of(home, ".cache", "xml-model-validator")
                    .toAbsolutePath()
                    .normalize();
            if (canUseCacheRoot(homeCachePath)) {
                return homeCachePath;
            }
        }

        return WORKSPACE_ROOT
                .resolve(".cache/xml-model-validator")
                .toAbsolutePath()
                .normalize();
    }

    private static boolean canUseCacheRoot(Path path) {
        try {
            Files.createDirectories(path);
            return Files.isWritable(path);
        } catch (IOException exception) {
            return false;
        }
    }
}
