package dev.koradb.observability.exporter;

import java.util.Objects;

/**
 * Configuration options for the Prometheus HTTP metrics exporter.
 */
public record MetricsExporterConfig(
        String host,
        int port,
        String metricsPath,
        String healthPath,
        String statusPath
) {
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 9100;
    public static final String DEFAULT_METRICS_PATH = "/metrics";
    public static final String DEFAULT_HEALTH_PATH = "/health";
    public static final String DEFAULT_STATUS_PATH = "/status";

    public MetricsExporterConfig {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(metricsPath, "metricsPath must not be null");
        Objects.requireNonNull(healthPath, "healthPath must not be null");
        Objects.requireNonNull(statusPath, "statusPath must not be null");
    }

    public static MetricsExporterConfig defaultConfig() {
        return new MetricsExporterConfig(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_METRICS_PATH, DEFAULT_HEALTH_PATH, DEFAULT_STATUS_PATH);
    }

    public static MetricsExporterConfig onPort(int port) {
        return new MetricsExporterConfig(DEFAULT_HOST, port, DEFAULT_METRICS_PATH, DEFAULT_HEALTH_PATH, DEFAULT_STATUS_PATH);
    }

    public static MetricsExporterConfig of(String host, int port) {
        return new MetricsExporterConfig(host, port, DEFAULT_METRICS_PATH, DEFAULT_HEALTH_PATH, DEFAULT_STATUS_PATH);
    }
}
