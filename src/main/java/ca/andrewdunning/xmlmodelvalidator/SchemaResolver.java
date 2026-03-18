package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves schema references from aliases, local paths, and remote URLs into concrete files on disk.
 */
final class SchemaResolver {
    private final Map<String, Path> schemaAliases;
    private final RemoteSchemaCache remoteSchemaCache;

    SchemaResolver(Map<String, Path> schemaAliases, RemoteSchemaCache remoteSchemaCache) {
        this.schemaAliases = schemaAliases;
        this.remoteSchemaCache = remoteSchemaCache;
    }

    Path resolve(String href, Path baseDirectory) {
        return resolveSource(href, baseDirectory).path();
    }

    /**
     * Resolves a schema reference against the current document location or configured aliases.
     */
    ResolvedSchemaSource resolveSource(String href, Path baseDirectory) {
        if (schemaAliases.containsKey(href)) {
            Path aliased = schemaAliases.get(href);
            return new ResolvedSchemaSource(aliased, aliased.toUri().toString());
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            try {
                return new ResolvedSchemaSource(remoteSchemaCache.fetch(href), href);
            } catch (IOException | InterruptedException exception) {
                throw new IllegalArgumentException("Could not fetch remote schema URL: " + href, exception);
            }
        }

        Path candidate = baseDirectory.resolve(href).normalize().toAbsolutePath();
        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("Resolved schema path does not exist: " + candidate);
        }
        return new ResolvedSchemaSource(candidate, candidate.toUri().toString());
    }

    /**
     * Resolves nested schema imports/includes using the parent schema's system identifier when one is
     * available.
     */
    ResolvedSchemaSource resolveRelativeToSystemId(String href, String baseSystemId, Path fallbackBaseDirectory) {
        if (schemaAliases.containsKey(href)) {
            Path aliased = schemaAliases.get(href);
            return new ResolvedSchemaSource(aliased, aliased.toUri().toString());
        }

        if (baseSystemId != null && !baseSystemId.isBlank()) {
            URI baseUri = URI.create(baseSystemId);
            URI resolved = baseUri.resolve(href);
            String resolvedString = resolved.toString();
            if (resolvedString.startsWith("http://") || resolvedString.startsWith("https://")) {
                try {
                    return new ResolvedSchemaSource(remoteSchemaCache.fetch(resolvedString), resolvedString);
                } catch (IOException | InterruptedException exception) {
                    throw new IllegalArgumentException("Could not fetch remote schema URL: " + resolvedString,
                            exception);
                }
            }
            if ("file".equalsIgnoreCase(resolved.getScheme())) {
                Path path = Path.of(resolved).toAbsolutePath().normalize();
                if (!Files.exists(path)) {
                    throw new IllegalArgumentException("Resolved schema path does not exist: " + path);
                }
                return new ResolvedSchemaSource(path, path.toUri().toString());
            }
        }

        return resolveSource(href, fallbackBaseDirectory);
    }
}
