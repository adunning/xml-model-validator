package ca.andrewdunning.xmlmodelvalidator;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates the TOML validator configuration file.
 */
final class ValidatorConfigParser {
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("schema_aliases", "xml_model_rules");
    private static final Set<String> RULE_KEYS = Set.of("directory", "extension", "mode", "declarations");
    private static final Set<String> DECLARATION_KEYS = Set.of("href", "schematypens", "type", "phase");

    private ValidatorConfigParser() {
    }

    static ValidatorConfig parse(Path configFile) throws IOException {
        if (!Files.exists(configFile)) {
            return new ValidatorConfig(Map.of(), List.of());
        }

        TomlParseResult parsed = Toml.parse(configFile);
        if (parsed.hasErrors()) {
            String errors = String.join("; ", parsed.errors().stream()
                    .map(Object::toString)
                    .toList());
            throw new IOException("Invalid validator config file " + configFile + ": " + errors);
        }

        rejectUnknownKeys(parsed.keySet(), TOP_LEVEL_KEYS, "top-level config", configFile);
        Map<String, Path> schemaAliases = parseSchemaAliases(parsed.getTable("schema_aliases"), configFile);
        List<XmlModelRule> xmlModelRules = parseXmlModelRules(parsed.getArray("xml_model_rules"), configFile);
        return new ValidatorConfig(schemaAliases, xmlModelRules);
    }

    private static Map<String, Path> parseSchemaAliases(TomlTable aliasesTable, Path configFile) throws IOException {
        if (aliasesTable == null) {
            return Map.of();
        }
        Map<String, Path> aliases = new HashMap<>();
        for (Map.Entry<String, Object> entry : aliasesTable.toMap().entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof String aliasPath) || aliasPath.isBlank()) {
                throw new IOException("Schema alias " + key + " in " + configFile + " must map to a non-blank path");
            }
            aliases.put(key, resolveConfigRelativePath(configFile, aliasPath));
        }
        return aliases;
    }

    private static List<XmlModelRule> parseXmlModelRules(TomlArray rulesArray, Path configFile) throws IOException {
        List<XmlModelRule> rules = new ArrayList<>();
        if (rulesArray == null) {
            return rules;
        }
        for (int index = 0; index < rulesArray.size(); index += 1) {
            TomlTable ruleTable = rulesArray.getTable(index);
            if (ruleTable == null) {
                throw new IOException("Invalid xml-model rule at index " + index + " in " + configFile);
            }
            rejectUnknownKeys(ruleTable.keySet(), RULE_KEYS, "xml-model rule " + index, configFile);
            Path directory = resolveRuleDirectory(ruleTable.getString("directory"));
            String extension = ruleTable.getString("extension");
            XmlModelRuleMode mode = XmlModelRuleMode.parse(ruleTable.getString("mode"));
            TomlArray declarationsArray = ruleTable.getArray("declarations");
            if (declarationsArray == null || declarationsArray.isEmpty()) {
                throw new IOException("Xml-model rule at index " + index + " in " + configFile + " must declare at least one entry");
            }
            List<XmlModelEntry> entries = new ArrayList<>();
            for (int declarationIndex = 0; declarationIndex < declarationsArray.size(); declarationIndex += 1) {
                TomlTable declaration = declarationsArray.getTable(declarationIndex);
                if (declaration == null) {
                    throw new IOException("Invalid declaration at index " + declarationIndex + " in xml-model rule " + index + " of " + configFile);
                }
                rejectUnknownKeys(
                        declaration.keySet(),
                        DECLARATION_KEYS,
                        "xml-model declaration " + declarationIndex + " in rule " + index,
                        configFile);
                entries.add(ValidationSupport.createConfiguredXmlModelEntry(
                        requiredTomlString(declaration, "href", configFile, index, declarationIndex),
                        declaration.getString("schematypens"),
                        declaration.getString("type"),
                        declaration.getString("phase")));
            }
            rules.add(new XmlModelRule(directory, extension, mode, entries));
        }
        return rules;
    }

    private static void rejectUnknownKeys(
            Set<String> actualKeys,
            Set<String> allowedKeys,
            String context,
            Path configFile) throws IOException {
        List<String> unknownKeys = actualKeys.stream()
                .filter(key -> !allowedKeys.contains(key))
                .sorted()
                .toList();
        if (!unknownKeys.isEmpty()) {
            throw new IOException(
                    "Unsupported key(s) in " + context + " of " + configFile + ": " + String.join(", ", unknownKeys));
        }
    }

    private static String requiredTomlString(
            TomlTable table,
            String key,
            Path configFile,
            int ruleIndex,
            int declarationIndex) throws IOException {
        String value = table.getString(key);
        if (value == null || value.isBlank()) {
            throw new IOException(
                    "Xml-model declaration "
                            + declarationIndex
                            + " in rule "
                            + ruleIndex
                            + " of "
                            + configFile
                            + " must define a non-blank "
                            + key);
        }
        return value;
    }

    private static Path resolveConfigRelativePath(Path configFile, String configuredPath) {
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize().toAbsolutePath();
        }
        Path parent = configFile.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return ValidationSupport.resolveAgainstWorkspace(path);
        }
        return parent.resolve(path).normalize().toAbsolutePath();
    }

    private static Path resolveRuleDirectory(String configuredDirectory) {
        if (configuredDirectory == null) {
            return null;
        }
        String trimmed = configuredDirectory.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Path path = Path.of(trimmed);
        if (path.isAbsolute()) {
            return path.normalize().toAbsolutePath();
        }
        return ValidationSupport.resolveAgainstWorkspace(path);
    }
}
