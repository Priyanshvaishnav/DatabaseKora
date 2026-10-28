package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.catalog.Row;
import dev.koradb.execution.QueryResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small helpers shared by the end-to-end regression tests (one test per report id). */
final class E2e {

    private E2e() {}

    static long count(KoraDB db, String sql) {
        return number(db.execute(sql)).longValue();
    }

    static Number number(QueryResult r) {
        Object v = r.rows().get(0).values().values().iterator().next();
        return (Number) v;
    }

    /** Rows as sorted strings, so two results can be compared as multisets. */
    static List<String> bag(QueryResult r) {
        List<String> out = new ArrayList<>();
        for (Row row : r.rows()) {
            StringBuilder sb = new StringBuilder();
            for (String c : r.columns()) {
                Object v = row.get(c);
                if (v instanceof Double d) v = Math.round(d * 100.0) / 100.0;
                sb.append(v).append('|');
            }
            out.add(sb.toString());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
