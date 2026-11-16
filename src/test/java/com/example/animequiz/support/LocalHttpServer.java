package com.example.animequiz.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Small HTTP stub used by the importer and poster tests instead of a real network. */
public final class LocalHttpServer implements AutoCloseable {

    private record Stub(int status, String contentType, byte[] body) {}

    private final HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public LocalHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public LocalHttpServer stub(String path, int status, String contentType, byte[] body) {
        stubs.put(path, new Stub(status, contentType, body));
        return this;
    }

    public LocalHttpServer json(String path, String body) {
        return stub(path, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    public LocalHttpServer bytes(String path, String contentType, byte[] body) {
        return stub(path, 200, contentType, body);
    }

    public int hits(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

        Stub stub = stubs.get(path);
        if (stub == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        if (stub.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", stub.contentType());
        }
        exchange.sendResponseHeaders(stub.status(), stub.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(stub.body());
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
