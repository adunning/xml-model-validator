package ca.andrewdunning.xmlmodelvalidator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loaded repository configuration for schema aliases and xml-model rules.
 */
record ValidatorConfig(Map<String, Path> schemaAliases, List<XmlModelRule> xmlModelRules) {
    ValidatorConfig {
        schemaAliases = Map.copyOf(schemaAliases);
        xmlModelRules = List.copyOf(xmlModelRules);
    }
}
