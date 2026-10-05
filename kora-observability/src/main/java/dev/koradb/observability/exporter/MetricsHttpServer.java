package dev.koradb.observability.exporter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.koradb.observability.metrics.Metric;
import dev.koradb.observability.metrics.MetricsRegistry;
import dev.koradb.observability.metrics.PrometheusFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * Built-in lightweight HTTP server exposing Prometheus metrics, health status, and metadata.
 * Uses Java 21 Virtual Threads and standard library {@link HttpServer} without external dependencies.
 */
public final class MetricsHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsHttpServer.class);

    private final MetricsExporterConfig config;
    private final MetricsRegistry registry;
    private final long startTimeMs = System.currentTimeMillis();
    private HttpServer server;
    private volatile boolean running;

    public MetricsHttpServer(MetricsExporterConfig config, MetricsRegistry registry) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext(config.metricsPath(), this::handleMetrics);
        server.createContext(config.healthPath(), this::handleHealth);
        server.createContext(config.statusPath(), this::handleStatus);

        server.start();
        running = true;
        log.info("KoraDB Prometheus metrics exporter listening on http://{}:{}{}",
                config.host(), getPort(), config.metricsPath());
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (server != null) {
            server.stop(0);
            server = null;
            log.info("KoraDB Prometheus metrics exporter stopped");
        }
    }

    public int getPort() {
        if (server == null) {
            return config.port();
        }
        return server.getAddress().getPort();
    }

    public boolean isRunning() {
        return running;
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        String prometheusText = PrometheusFormatter.format(registry);
        sendResponse(exchange, 200, prometheusText, "text/plain; version=0.0.4; charset=utf-8");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        long uptime = (System.currentTimeMillis() - startTimeMs) / 1000L;
        String json = String.format("{\"status\":\"UP\",\"uptimeSeconds\":%d}", uptime);
        sendResponse(exchange, 200, json, "application/json; charset=utf-8");
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder("{\"metrics\":{");
        boolean first = true;
        for (Metric m : registry.allMetrics()) {
            if (!first) json.append(',');
            json.append('"').append(m.name().toString().replace("\"", "\\\"")).append("\":\"")
                    .append(m.type().name().toLowerCase()).append('"');
            first = false;
        }
        json.append("}}");
        sendResponse(exchange, 200, json.toString(), "application/json; charset=utf-8");
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String responseText, String contentType) throws IOException {
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
