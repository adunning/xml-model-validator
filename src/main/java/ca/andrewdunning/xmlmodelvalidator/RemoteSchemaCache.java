package ca.andrewdunning.xmlmodelvalidator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Downloads remote schemas once and stores them in a stable local cache directory. */
final class RemoteSchemaCache {
    private final HttpClient client;

    RemoteSchemaCache() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** Fetches a remote schema into the cache if it is not already present. */
    synchronized Path fetch(String url) throws IOException, InterruptedException {
        Files.createDirectories(ValidationSupport.SCHEMA_DOWNLOAD_CACHE_DIR);
        URI uri = URI.create(url);
        String filename = cacheFilename(uri);
        Path destination = ValidationSupport.SCHEMA_DOWNLOAD_CACHE_DIR.resolve(filename);
        if (Files.exists(destination)) {
            return destination;
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "xml-model-validator/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Could not fetch schema " + url + ": HTTP " + response.statusCode());
        }

        Files.write(destination, response.body());
        return destination;
    }

    private static String cacheFilename(URI uri) {
        String path;
        if (uri.getPath() == null) {
            path = "";
        } else {
            path = uri.getPath();
        }
        String basename =
                Path.of(path.isBlank() ? "schema.xml" : path).getFileName().toString();
        return sha256(uri.toString()).substring(0, 16) + "-" + basename;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
