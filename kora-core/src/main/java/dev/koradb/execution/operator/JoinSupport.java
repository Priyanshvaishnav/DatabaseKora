package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Column naming shared by the join operators and the logical join. */
public final class JoinSupport {

    private JoinSupport() {}

    /** Left columns, then right columns; a right name already taken gets "_2" (then "_3", ...). */
    public static Schema mergeSchemas(Schema left, Schema right) {
        List<ColumnDef> cols = new ArrayList<>(left.columns());
        Set<String> taken = new HashSet<>();
        for (ColumnDef c : left.columns()) taken.add(c.name().toLowerCase());
        for (ColumnDef c : right.columns()) {
            String name = uniqueName(c.name(), taken);
            taken.add(name.toLowerCase());
            cols.add(name.equals(c.name()) ? c : new ColumnDef(name, c.dataType()));
        }
        return new Schema(cols);
    }

    /**
     * Merges a left and a right row. Qualified keys ("orders.id") are kept as they are; an
     * unqualified right key that is already used is renamed like in {@link #mergeSchemas}.
     */
    public static Row mergeRows(Row left, Row right) {
        Map<String, Object> map = new LinkedHashMap<>(left.values());
        Set<String> taken = new HashSet<>();
        for (String k : map.keySet()) taken.add(k.toLowerCase());
        for (Map.Entry<String, Object> e : right.values().entrySet()) {
            String k = e.getKey();
            if (k.contains(".")) {
                map.put(k, e.getValue());
            } else {
                String name = uniqueName(k, taken);
                taken.add(name.toLowerCase());
                map.put(name, e.getValue());
            }
        }
        return new Row(map);
    }

    /** A right-side row of NULLs (LEFT JOIN without a match): every column, plain and qualified. */
    public static Row nullRow(Schema rightSchema, List<String> qualifiers) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (ColumnDef c : rightSchema.columns()) {
            map.put(c.name(), null);
            for (String q : qualifiers) map.put(q + "." + c.name(), null);
        }
        return new Row(map);
    }

    /** Hash key for an equi-join value: integers of any width compare equal. */
    static Object hashKey(Object v) {
        if (v instanceof Integer i) return (long) i;
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) return d.longValue();
        return v;
    }

    private static String uniqueName(String name, Set<String> taken) {
        if (!taken.contains(name.toLowerCase())) return name;
        for (int n = 2; ; n++) {
            String candidate = name + "_" + n;
            if (!taken.contains(candidate.toLowerCase())) return candidate;
        }
    }
}
