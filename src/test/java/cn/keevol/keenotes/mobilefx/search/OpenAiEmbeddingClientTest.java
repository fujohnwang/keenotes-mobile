package cn.keevol.keenotes.mobilefx.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class OpenAiEmbeddingClientTest {
    @Test public void cancellingAnObsoleteQueryReleasesItsHttpCall() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/embeddings", exchange -> {
            entered.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (OpenAiEmbeddingClient client = new OpenAiEmbeddingClient();
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            EmbeddingConfig config = new EmbeddingConfig(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test", "", "", "");
            SearchCancellation cancellation = new SearchCancellation();
            var future = executor.submit(() -> client.embedQuery(config, "obsolete query", cancellation));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            cancellation.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test public void alignsResponseByIndexAndUsesDocumentAndQueryPrefixes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>("{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}");
        server.createContext("/v1/embeddings", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        EmbeddingConfig config = new EmbeddingConfig(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test", "", "document: ", "query: ");
        try (OpenAiEmbeddingClient client = new OpenAiEmbeddingClient()) {
            List<float[]> vectors = client.embedDocuments(config, List.of("first", "second"));
            assertArrayEquals(new float[]{1, 0}, vectors.getFirst(), 0f);
            assertTrue(request.get().contains("document: first"));
            response.set("{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}");
            client.embedQuery(config, "find");
            assertTrue(request.get().contains("query: find"));
            response.set("{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}");
            assertThrows(java.io.IOException.class, () -> client.embedQuery(config, "find"));
            response.set("{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]}");
            assertThrows(java.io.IOException.class, () -> client.embedDocuments(config, List.of("a", "b")));
        } finally { server.stop(0); }
    }
}
