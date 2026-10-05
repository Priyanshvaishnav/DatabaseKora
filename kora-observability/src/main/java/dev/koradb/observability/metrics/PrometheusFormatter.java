package dev.koradb.observability.metrics;

import java.util.*;

/**
 * Formats a collection of metrics into the standard Prometheus exposition text format (0.0.4).
 */
public final class PrometheusFormatter {

    private PrometheusFormatter() {}

    /**
     * Formats all metrics registered in the given registry into Prometheus text format.
     *
     * @param registry the metrics registry
     * @return Prometheus exposition formatted string
     */
    public static String format(MetricsRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        return format(registry.allMetrics());
    }

    /**
     * Formats a collection of metrics into Prometheus text format.
     */
    public static String format(Collection<Metric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "";
        }

        // Group metrics by their base metric name
        Map<String, List<Metric>> grouped = new TreeMap<>();
        for (Metric m : metrics) {
            grouped.computeIfAbsent(m.name().name(), k -> new ArrayList<>()).add(m);
        }

        StringBuilder out = new StringBuilder(1024);
        for (Map.Entry<String, List<Metric>> entry : grouped.entrySet()) {
            String baseName = entry.getKey();
            List<Metric> list = entry.getValue();
            Metric first = list.get(0);

            // Write HELP and TYPE
            if (first.description() != null && !first.description().isBlank()) {
                out.append("# HELP ").append(baseName).append(' ').append(escapeComment(first.description())).append('\n');
            }
            out.append("# TYPE ").append(baseName).append(' ').append(first.type().name().toLowerCase(Locale.ROOT)).append('\n');

            for (Metric m : list) {
                switch (m) {
                    case Counter counter -> formatCounter(out, counter);
                    case Gauge gauge -> formatGauge(out, gauge);
                    case Histogram histogram -> formatHistogram(out, histogram);
                    default -> {}
                }
            }
        }

        return out.toString();
    }

    private static void formatCounter(StringBuilder out, Counter counter) {
        formatSample(out, counter.name().name(), counter.name().labels(), formatDouble(counter.get()));
    }

    private static void formatGauge(StringBuilder out, Gauge gauge) {
        formatSample(out, gauge.name().name(), gauge.name().labels(), formatDouble(gauge.get()));
    }

    private static void formatHistogram(StringBuilder out, Histogram h) {
        String base = h.name().name();
        SortedMap<String, String> labels = h.name().labels();
        double[] upperBounds = h.getUpperBounds();
        long[] bucketCounts = h.getBucketCounts();

        // Output bucket samples
        for (int i = 0; i < upperBounds.length; i++) {
            Map<String, String> bucketLabels = new LinkedHashMap<>(labels);
            bucketLabels.put("le", formatDouble(upperBounds[i]));
            formatSample(out, base + "_bucket", bucketLabels, String.valueOf(bucketCounts[i]));
        }
        // +Inf bucket (equals total count)
        Map<String, String> infLabels = new LinkedHashMap<>(labels);
        infLabels.put("le", "+Inf");
        formatSample(out, base + "_bucket", infLabels, String.valueOf(h.getCount()));

        // Sum and count samples
        formatSample(out, base + "_sum", labels, formatDouble(h.getSum()));
        formatSample(out, base + "_count", labels, String.valueOf(h.getCount()));
    }

    private static void formatSample(StringBuilder out, String name, Map<String, String> labels, String value) {
        out.append(name);
        if (labels != null && !labels.isEmpty()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (!first) out.append(',');
                out.append(e.getKey()).append("=\"").append(escapeLabelValue(e.getValue())).append('"');
                first = false;
            }
            out.append('}');
        }
        out.append(' ').append(value).append('\n');
    }

    private static String formatDouble(double val) {
        if (Double.isNaN(val)) return "NaN";
        if (Double.isInfinite(val)) return val > 0 ? "+Inf" : "-Inf";
        if (val == (long) val) {
            return String.valueOf((long) val);
        }
        return String.valueOf(val);
    }

    private static String escapeComment(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String escapeLabelValue(String val) {
        if (val == null) return "";
        return val.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
