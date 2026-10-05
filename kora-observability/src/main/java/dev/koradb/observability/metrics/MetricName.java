package dev.koradb.observability.metrics;

import java.util.*;

/**
 * Metric identifier consisting of a metric name and optional key-value labels.
 * Labels are stored in natural key order to ensure consistent equals/hashCode and text rendering.
 */
public final class MetricName implements Comparable<MetricName> {

    private final String name;
    private final SortedMap<String, String> labels;

    public MetricName(String name, Map<String, String> labels) {
        this.name = validateName(name);
        this.labels = labels == null || labels.isEmpty()
                ? Collections.emptySortedMap()
                : Collections.unmodifiableSortedMap(new TreeMap<>(labels));
    }

    public static MetricName of(String name) {
        return new MetricName(name, Collections.emptyMap());
    }

    public static MetricName of(String name, String labelKey, String labelVal) {
        return new MetricName(name, Map.of(labelKey, labelVal));
    }

    public static MetricName of(String name, Map<String, String> labels) {
        return new MetricName(name, labels);
    }

    public static MetricName ofKeyValues(String name, String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return of(name);
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value label pairs must be even in number");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return new MetricName(name, map);
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "Metric name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Metric name must not be blank");
        }
        return name.trim();
    }

    public String name() {
        return name;
    }

    public SortedMap<String, String> labels() {
        return labels;
    }

    public String getLabel(String key) {
        return labels.get(key);
    }

    public boolean hasLabels() {
        return !labels.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MetricName that)) return false;
        return Objects.equals(name, that.name) && Objects.equals(labels, that.labels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, labels);
    }

    @Override
    public int compareTo(MetricName o) {
        int c = this.name.compareTo(o.name);
        if (c != 0) return c;
        return this.labels.toString().compareTo(o.labels.toString());
    }

    @Override
    public String toString() {
        if (labels.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (!first) sb.append(',');
            sb.append(entry.getKey()).append("=\"").append(entry.getValue()).append('"');
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
