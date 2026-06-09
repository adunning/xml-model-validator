package ca.andrewdunning.xmlmodelvalidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RemoteSchemaCacheTest {
    @Test
    void fetchesRemoteSchemaIntoStableCacheFile() throws Exception {
        byte[] body = "<schema/>".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema.rng", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/schema.rng";
            RemoteSchemaCache cache = new RemoteSchemaCache();

            Path first = cache.fetch(url);
            Path second = cache.fetch(url);

            assertEquals(first, second);
            assertEquals("<schema/>", Files.readString(first, StandardCharsets.UTF_8));
            assertTrue(first.getFileName().toString().endsWith("-schema.rng"));
        } finally {
            server.stop(0);
        }
    }
}
