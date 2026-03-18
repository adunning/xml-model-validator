package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared constants and utility methods for workspace, cache, and GitHub Actions integration.
 */
final class ValidationSupport {
    static final String RELAXNG_NS = "http://relaxng.org/ns/structure/1.0";
    static final String SCHEMATRON_NS = "http://purl.oclc.org/dsdl/schematron";
    static final String SVRL_NS = "http://purl.oclc.org/dsdl/svrl";
    static final Path WORKSPACE_ROOT = resolveWorkspaceRoot();
    static final Path CACHE_ROOT = resolveCacheRoot();
    static final Path SCHEMATRON_CACHE_DIR = CACHE_ROOT.resolve("schematron");
    static final Path SCHEMA_DOWNLOAD_CACHE_DIR = CACHE_ROOT.resolve("schema-downloads");
    static final Path DEFAULT_SCHEMA_ALIASES_FILE = WORKSPACE_ROOT.resolve(".xml-validator/schema-aliases.tsv");

    private ValidationSupport() {
    }

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

    static Map<String, Path> loadSchemaAliases(Path aliasesFile) throws IOException {
        Map<String, Path> aliases = new HashMap<>();
        if (!Files.exists(aliasesFile)) {
            return aliases;
        }
        for (String rawLine : Files.readAllLines(aliasesFile, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            if (parts.length != 2) {
                throw new IOException("Invalid schema alias line in " + aliasesFile + ": " + rawLine);
            }
            aliases.put(parts[0], resolveAliasPath(aliasesFile, parts[1]));
        }
        return aliases;
    }

    private static Path resolveAliasPath(Path aliasesFile, String aliasPath) {
        Path path = Paths.get(aliasPath);
        if (path.isAbsolute()) {
            return path.normalize().toAbsolutePath();
        }
        Path parent = aliasesFile.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return resolveAgainstWorkspace(path);
        }
        return parent.resolve(path).normalize().toAbsolutePath();
    }

    private static Path resolveWorkspaceRoot() {
        String configuredRoot = System.getenv("XML_MODEL_VALIDATOR_WORKSPACE");
        if (configuredRoot == null || configuredRoot.isBlank()) {
            configuredRoot = System.getenv("GITHUB_WORKSPACE");
        }
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        return Paths.get(configuredRoot).toAbsolutePath().normalize();
    }

    private static Path resolveCacheRoot() {
        String configuredRoot = System.getenv("XML_MODEL_VALIDATOR_CACHE_HOME");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Paths.get(configuredRoot).toAbsolutePath().normalize();
        }

        String home = System.getenv("HOME");
        if (home != null && !home.isBlank()) {
            return Paths.get(home, ".cache", "xml-model-validator").toAbsolutePath().normalize();
        }

        return WORKSPACE_ROOT.resolve(".cache/xml-model-validator").toAbsolutePath().normalize();
    }
}
