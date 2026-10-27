package dev.koradb.ai.provider;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline natural-language to SQL translation that only uses tables, columns and values that exist.
 *
 * <p>The schema comes from the system prompt ({@code CREATE TABLE ...} lines and optional
 * {@code -- values of t.c: 'a', 'b'} lines). Words are matched to tables (singular/plural and a few
 * synonyms such as users → customers), to columns (by name, {@code customer} → {@code customer_id},
 * and concepts such as "older" → age, "cost" → price) and to known column values ("pending" →
 * {@code status = 'pending'}). When the request cannot be mapped onto the schema, a
 * {@code -- Could not translate} comment is returned instead of guessed table or column names.
 */
final class SchemaAwareTranslator {

    record Column(String name, String type) {
        boolean numeric() { return type.equals("INT") || type.equals("BIGINT") || type.equals("DOUBLE"); }
        boolean text() { return type.equals("VARCHAR"); }
        boolean timestamp() { return type.equals("TIMESTAMP"); }
    }

    record Table(String name, List<Column> columns) {
        Column column(String n) {
            for (Column c : columns) if (c.name().equalsIgnoreCase(n)) return c;
            return null;
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Map<String, List<String>> values = new HashMap<>();   // "table.column" -> values

    private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE\\s+([A-Za-z0-9_]+)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUES_LINE = Pattern.compile("--\\s*values of\\s+([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+):\\s*(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("'((?:[^']|'')*)'");

    /** Parses the schema part of a system prompt; returns null when it holds no tables. */
    static SchemaAwareTranslator fromPrompt(String systemPrompt) {
        if (systemPrompt == null) return null;
        SchemaAwareTranslator t = new SchemaAwareTranslator();
        Matcher m = CREATE_TABLE.matcher(systemPrompt);
        while (m.find()) {
            String name = m.group(1);
            if (name.equalsIgnoreCase("table") || name.contains("[")) continue;   // dialect rules text
            List<Column> cols = new ArrayList<>();
            boolean ok = true;
            for (String part : m.group(2).split(",")) {
                String[] p = part.trim().split("\\s+");
                if (p.length < 2 || !p[0].matches("[A-Za-z_][A-Za-z0-9_]*")) { ok = false; break; }
                cols.add(new Column(p[0], p[1].toUpperCase(Locale.ROOT)));
            }
            if (ok && !cols.isEmpty()) t.tables.put(name.toLowerCase(Locale.ROOT), new Table(name, cols));
        }
        for (String line : systemPrompt.split("\n")) {
            Matcher v = VALUES_LINE.matcher(line.trim());
            if (v.matches()) {
                List<String> vals = new ArrayList<>();
                Matcher q = QUOTED.matcher(v.group(3));
                while (q.find()) vals.add(q.group(1).replace("''", "'"));
                t.values.put((v.group(1) + "." + v.group(2)).toLowerCase(Locale.ROOT), vals);
            }
        }
        return t.tables.isEmpty() ? null : t;
    }

    // ── vocabulary ───────────────────────────────────────────────────────────────────────────

    private static final Set<String> STOP = Set.of(
            "show", "list", "get", "find", "fetch", "select", "give", "me", "display", "return", "what", "which",
            "who", "is", "are", "was", "were", "the", "a", "an", "all", "every", "each", "any", "of", "in", "on",
            "for", "from", "with", "whose", "where", "that", "there", "have", "has", "had", "and", "by", "to",
            "how", "many", "much", "number", "count", "please", "than", "more", "less", "at", "least", "most",
            "do", "does", "my", "our", "their", "be", "it", "its", "this", "these", "those", "i", "we", "you",
            "rows", "records", "entries", "data", "table", "tables", "total", "sum", "average", "avg", "mean",
            "maximum", "minimum", "max", "min", "highest", "lowest", "top", "first", "delete", "remove",
            "after", "before", "since", "until", "under", "over", "above", "below", "equal", "equals", "not");

    /** Groups of words that name the same kind of entity (table synonyms). */
    private static final List<Set<String>> TABLE_SYNONYMS = List.of(
            Set.of("user", "customer", "client", "member", "account", "person", "people", "buyer", "shopper"),
            Set.of("product", "item", "article", "good", "sku"),
            Set.of("order", "purchase", "sale", "transaction"),
            Set.of("employee", "staff", "worker", "personnel"));

    /** Concept words → column name fragments (in preference order). */
    private static final Map<String, List<String>> CONCEPTS = new LinkedHashMap<>();
    static {
        List<String> age = List.of("age", "years");
        for (String w : List.of("old", "older", "oldest", "young", "younger", "youngest", "age", "aged")) CONCEPTS.put(w, age);
        List<String> price = List.of("price", "cost", "unit_price", "amount");
        for (String w : List.of("cost", "costs", "price", "priced", "cheap", "cheaper", "cheapest", "expensive", "pricier", "pricey")) CONCEPTS.put(w, price);
        List<String> amount = List.of("amount", "total", "revenue", "value", "price");
        for (String w : List.of("revenue", "sales", "amount", "spent", "spend", "worth", "value", "income")) CONCEPTS.put(w, amount);
        List<String> stock = List.of("stock", "quantity", "qty", "inventory", "units");
        for (String w : List.of("stock", "inventory", "quantity", "qty", "units")) CONCEPTS.put(w, stock);
        List<String> salary = List.of("salary", "pay", "wage", "income");
        for (String w : List.of("salary", "paid", "earn", "earns", "earning", "wage", "wages")) CONCEPTS.put(w, salary);
        List<String> location = List.of("city", "location", "town", "country", "state", "region", "place");
        for (String w : List.of("city", "town", "location", "located", "lives", "live", "living", "based")) CONCEPTS.put(w, location);
        List<String> name = List.of("name", "title", "full_name", "username");
        for (String w : List.of("named", "called", "titled")) CONCEPTS.put(w, name);
    }

    /** Comparison phrases (longest first) → SQL operator. */
    private static final String[][] OPS = {
            {"greater than or equal to", ">="}, {"less than or equal to", "<="},
            {"more expensive than", ">"}, {"no more than", "<="}, {"not more than", "<="}, {"no less than", ">="},
            {"greater than", ">"}, {"more than", ">"}, {"larger than", ">"}, {"bigger than", ">"}, {"higher than", ">"},
            {"older than", ">"}, {"pricier than", ">"}, {"less than", "<"}, {"fewer than", "<"}, {"cheaper than", "<"},
            {"lower than", "<"}, {"smaller than", "<"}, {"younger than", "<"}, {"at least", ">="}, {"at most", "<="},
            {"not equal to", "!="}, {"equal to", "="}, {"named", "="}, {"called", "="}, {"titled", "="}, {"equals", "="}, {"above", ">"}, {"over", ">"}, {"exceeding", ">"},
            {"below", "<"}, {"under", "<"}, {"after", ">"}, {"since", ">="}, {"before", "<"},
            {">=", ">="}, {"<=", "<="}, {"!=", "!="}, {"<>", "!="}, {"=", "="}, {">", ">"}, {"<", "<"}, {"is", "="}};

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    // ── translation ──────────────────────────────────────────────────────────────────────────

    String translate(String original) {
        String text = original.trim().replaceAll("[?.!;]+$", "").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> words = words(lower);

        Table table = findTable(words);
        if (table == null) table = tableFromColumns(words);
        if (table == null) {
            return "-- Could not translate: no table in this database matches \"" + text + "\". Tables: "
                    + String.join(", ", tables.values().stream().map(Table::name).toList());
        }

        boolean delete = lower.startsWith("delete ") || lower.startsWith("remove ");
        List<String> conds = new ArrayList<>();
        Set<String> usedCols = new HashSet<>();
        String error = conditions(text, lower, table, conds, usedCols);
        if (error != null) return "-- Could not translate: " + error;

        if (delete) {
            if (conds.isEmpty() && !words.contains("all")) {
                return "-- Could not translate: refusing to generate DELETE without a condition (say \"delete all ...\" to delete every row)";
            }
            return "DELETE FROM " + table.name() + where(conds) + ";";
        }

        // aggregates, optionally per group ("count products by category", "average price per category")
        String agg = aggregate(lower, words, table, usedCols);
        if (agg != null) {
            Matcher by = Pattern.compile("\\b(?:by|per|for each|each)\\s+([a-z_][a-z0-9_]*)").matcher(lower);
            while (by.find()) {
                Column g = columnFor(by.group(1), table, true);
                if (g != null) {
                    return "SELECT " + g.name() + ", " + agg + " FROM " + table.name() + where(conds) + " GROUP BY " + g.name() + ";";
                }
            }
            return "SELECT " + agg + " FROM " + table.name() + where(conds) + ";";
        }

        // superlatives: "most expensive product", "oldest customer", "top 3 products by price"
        String order = superlative(lower, words, table);
        if (order != null) return "SELECT * FROM " + table.name() + where(conds) + order + ";";

        return "SELECT * FROM " + table.name() + where(conds) + ";";
    }

    private static String where(List<String> conds) {
        return conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
    }

    private static List<String> words(String lower) {
        List<String> out = new ArrayList<>();
        for (String w : lower.split("[^a-z0-9_]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private Table findTable(List<String> words) {
        for (String w : words) {
            Table t = tableFor(w);
            if (t != null) return t;
        }
        for (String w : words) {            // synonyms only after direct matches
            if (STOP.contains(w)) continue;
            String stem = singular(w);
            for (Set<String> group : TABLE_SYNONYMS) {
                if (!group.contains(stem)) continue;
                for (Table t : tables.values()) {
                    if (group.contains(singular(t.name().toLowerCase(Locale.ROOT)))) return t;
                }
            }
        }
        return null;
    }

    /** When no table is named: the only table that has a column mentioned in the request. */
    private Table tableFromColumns(List<String> words) {
        Table found = null;
        for (Table t : tables.values()) {
            boolean has = false;
            for (String w : words) {
                if (STOP.contains(w) || CONCEPTS.containsKey(w)) continue;
                if (t.column(w) != null || t.column(singular(w)) != null) { has = true; break; }
            }
            if (!has) {          // concepts ("salary", "paid") only when nothing else matched
                for (String w : words) {
                    if (CONCEPTS.containsKey(w) && conceptColumn(CONCEPTS.get(w), t) != null) { has = true; break; }
                }
            }
            if (has) {
                if (found != null) return null;   // ambiguous
                found = t;
            }
        }
        return found;
    }

    /** The identifying column of a table ("id", "employee_id", "emp_id", first *_id column). */
    private static Column idColumn(Table t) {
        Column c = t.column("id");
        if (c != null) return c;
        c = t.column(singular(t.name().toLowerCase(Locale.ROOT)) + "_id");
        if (c != null) return c;
        Column first = t.columns().get(0);
        return first.name().toLowerCase(Locale.ROOT).endsWith("id") && first.numeric() ? first : null;
    }

    private Table tableFor(String w) {
        Table t = tables.get(w);
        if (t != null) return t;
        String s = singular(w);
        for (Table c : tables.values()) {
            if (singular(c.name().toLowerCase(Locale.ROOT)).equals(s)) return c;
        }
        return null;
    }

    static String singular(String w) {
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("sses")) return w.substring(0, w.length() - 2);
        if (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) return w.substring(0, w.length() - 1);
        return w;
    }

    /** Column named by {@code word} in {@code table}: exact, singular, {@code word_id}, or concept. */
    private Column columnFor(String word, Table table, boolean allowConcept) {
        if (word == null || word.isEmpty()) return null;
        Column c = table.column(word);
        if (c != null) return c;
        c = table.column(singular(word));
        if (c != null) return c;
        c = table.column(singular(word) + "_id");
        if (c != null) return c;
        if (allowConcept && CONCEPTS.containsKey(word)) return conceptColumn(CONCEPTS.get(word), table);
        return null;
    }

    private static Column conceptColumn(List<String> fragments, Table table) {
        for (String f : fragments) {
            Column c = table.column(f);
            if (c != null) return c;
        }
        for (String f : fragments) {
            for (Column c : table.columns()) if (c.name().toLowerCase(Locale.ROOT).contains(f)) return c;
        }
        return null;
    }

    /** Collects WHERE predicates; returns an error message when part of the request cannot be mapped. */
    private String conditions(String text, String lower, Table table, List<String> conds, Set<String> usedCols) {
        // 1. comparisons ("older than 50", "price < 20", "status is shipped", "after 2024")
        boolean[] consumed = new boolean[lower.length()];
        for (String[] op : OPS) {
            int from = 0;
            while (true) {
                int idx = indexOfPhrase(lower, op[0], from);
                if (idx < 0) break;
                from = idx + op[0].length();
                if (consumed[idx]) continue;
                String before = lower.substring(0, idx).trim();
                String afterRaw = text.substring(idx + op[0].length()).trim();
                String cond = comparison(op[0], op[1], before, afterRaw, table, usedCols);
                if (cond == null) continue;
                if (cond.startsWith("!")) return cond.substring(1);
                conds.add(cond);
                Arrays.fill(consumed, Math.max(0, idx - 1), Math.min(consumed.length, idx + op[0].length() + 1 + valueSpan(afterRaw)), true);
            }
        }

        // 2. known values mentioned anywhere ("pending", "New York", "books")
        for (Column c : table.columns()) {
            if (usedCols.contains(c.name())) continue;
            List<String> vals = new ArrayList<>(values.getOrDefault((table.name() + "." + c.name()).toLowerCase(Locale.ROOT), List.of()));
            vals.sort((a, b) -> b.length() - a.length());
            for (String v : vals) {
                int idx = indexOfPhrase(lower, v.toLowerCase(Locale.ROOT), 0);
                if (idx >= 0 && !consumed[idx]) {
                    conds.add(c.name() + " = " + quote(v));
                    usedCols.add(c.name());
                    break;
                }
            }
        }

        // boolean columns: "active employees" / "inactive employees" / "not active"
        List<String> ws = words(lower);
        for (Column c : table.columns()) {
            if (!c.type().equals("BOOLEAN") || usedCols.contains(c.name())) continue;
            String n = c.name().toLowerCase(Locale.ROOT);
            int i = ws.indexOf(n);
            if (ws.contains("in" + n) || ws.contains("non" + n) || ws.contains("un" + n) || (i > 0 && ws.get(i - 1).equals("not"))) {
                conds.add(c.name() + " = false");
                usedCols.add(c.name());
            } else if (i >= 0) {
                conds.add(c.name() + " = true");
                usedCols.add(c.name());
            }
        }

        // 3. "<entity> <number>" ("orders for customer 7" -> customer_id = 7)
        Matcher m = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\s+(?:#|no\\.?\\s*|number\\s+)?(-?\\d+)\\b").matcher(lower);
        while (m.find()) {
            if (consumed[m.start()]) continue;
            Column c = columnFor(m.group(1), table, false);
            if (c == null && tableFor(m.group(1)) == table) c = idColumn(table);   // "employee 7"
            if (c != null && c.numeric() && !usedCols.contains(c.name())) {
                conds.add(c.name() + " = " + m.group(2));
                usedCols.add(c.name());
            }
        }

        // 4. "in <Place>" with no known values: location-like column
        Matcher in = Pattern.compile("\\bin\\s+([A-Z][\\p{L}]+(?:\\s+[A-Z][\\p{L}]+)*)").matcher(text);
        while (in.find()) {
            if (consumed[in.start()]) continue;
            Column c = conceptColumn(CONCEPTS.get("city"), table);
            if (c != null && c.text() && !usedCols.contains(c.name())) {
                conds.add(c.name() + " = " + quote(in.group(1)));
                usedCols.add(c.name());
            }
        }
        return null;
    }

    /** One comparison, or null when {@code op} does not form one here; "!msg" for an error. */
    private String comparison(String phrase, String sqlOp, String before, String afterRaw, Table table, Set<String> usedCols) {
        String value = valueAfter(afterRaw);
        if (value == null) return null;
        boolean numeric = NUMBER.matcher(value).matches();
        List<String> beforeWords = words(before);

        // the column: the last word before the phrase that names a column
        Column col = null;
        for (int i = beforeWords.size() - 1; i >= 0 && i >= beforeWords.size() - 3; i--) {
            String w = beforeWords.get(i);
            if (tableFor(w) == table && table.column(w) == null) break;
            col = columnFor(w, table, true);
            if (col != null) break;
        }
        // or the concept carried by the phrase itself ("older than" -> age, "cheaper than" -> price)
        if (col == null) {
            for (String w : phrase.split(" ")) {
                if (CONCEPTS.containsKey(w)) { col = conceptColumn(CONCEPTS.get(w), table); break; }
            }
        }
        // "after 2024" / "since 2020": a year or date column
        if (col == null && numeric && (phrase.equals("after") || phrase.equals("before") || phrase.equals("since"))) {
            col = yearColumn(beforeWords, table);
        }
        if (col == null) {
            if (phrase.equals("is") || phrase.equals("over") || phrase.equals("under") || phrase.equals("after")
                    || phrase.equals("before") || phrase.equals("since") || phrase.equals("above") || phrase.equals("below")) {
                return null;   // ordinary words, not necessarily comparisons
            }
            return numeric ? "!no column of " + table.name() + " matches the comparison \"" + phrase + " " + value + "\"" : null;
        }
        if (usedCols.contains(col.name()) && phrase.equals("is")) return null;

        String literal;
        if (col.numeric()) {
            if (!numeric) return null;
            literal = value;
        } else if (col.timestamp() && numeric && value.matches("\\d{4}")) {
            literal = sqlOp.startsWith(">") ? quote(value + (sqlOp.equals(">") ? "-12-31 23:59:59" : "-01-01 00:00:00"))
                                            : quote(value + "-01-01 00:00:00");
        } else if (col.text()) {
            literal = quote(knownValue(table, col, value));
        } else {
            literal = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") ? value.toLowerCase(Locale.ROOT) : quote(value);
        }
        usedCols.add(col.name());
        return col.name() + " " + sqlOp + " " + literal;
    }

    private Column yearColumn(List<String> beforeWords, Table table) {
        Column best = null;
        int bestScore = -1;
        for (Column c : table.columns()) {
            String n = c.name().toLowerCase(Locale.ROOT);
            if (!(n.contains("year") || n.contains("date") || n.endsWith("_at") || c.timestamp())) continue;
            int score = 0;
            for (String w : beforeWords) {
                if (w.length() >= 4 && n.contains(w.substring(0, 4))) score++;
            }
            if (score > bestScore) { best = c; bestScore = score; }
        }
        return best;
    }

    /** The value after a comparison phrase: a quoted string, a number, or the following words. */
    private static String valueAfter(String afterRaw) {
        if (afterRaw.isEmpty()) return null;
        if (afterRaw.startsWith("'")) {
            int end = afterRaw.indexOf('\'', 1);
            return end > 0 ? afterRaw.substring(1, end) : null;
        }
        Matcher n = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\b").matcher(afterRaw);
        if (n.find()) return n.group(1);
        StringBuilder sb = new StringBuilder();
        for (String w : afterRaw.split("\\s+")) {
            String bare = w.replaceAll("[^\\p{L}\\p{N}_\\-]", "");
            if (bare.isEmpty() || STOP.contains(bare.toLowerCase(Locale.ROOT)) || bare.equalsIgnoreCase("and") || bare.equalsIgnoreCase("or")) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(bare);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int valueSpan(String afterRaw) {
        String v = valueAfter(afterRaw);
        return v == null ? 0 : Math.min(afterRaw.length(), v.length() + 2);
    }

    /** Uses the stored spelling of a value when it is known ("new york" -> "New York"). */
    private String knownValue(Table table, Column col, String value) {
        for (String v : values.getOrDefault((table.name() + "." + col.name()).toLowerCase(Locale.ROOT), List.of())) {
            if (v.equalsIgnoreCase(value)) return v;
        }
        return value;
    }

    private String aggregate(String lower, List<String> words, Table table, Set<String> usedCols) {
        if (lower.startsWith("how many") || lower.startsWith("count ") || lower.startsWith("number of")
                || lower.contains("how many ")) {
            return "COUNT(*)";
        }
        String fn = null;
        if (words.contains("total") || words.contains("sum")) fn = "SUM";
        else if (words.contains("average") || words.contains("avg") || words.contains("mean")) fn = "AVG";
        else if (!asksForRow(lower, words, table)) {
            if (lower.matches(".*\\b(maximum|max|highest|largest|biggest)\\s+[a-z_]+\\b.*")) fn = "MAX";
            else if (lower.matches(".*\\b(minimum|min|lowest|smallest)\\s+[a-z_]+\\b.*")) fn = "MIN";
        }
        if (fn == null) return null;
        // the measured column: first word naming a numeric column that is not a filter
        for (String w : words) {
            Column c = columnFor(w, table, true);
            if (c != null && c.numeric() && !usedCols.contains(c.name())) return fn + "(" + c.name() + ")";
        }
        for (String w : words) {
            Column c = columnFor(w, table, true);
            if (c != null && c.numeric()) return fn + "(" + c.name() + ")";
        }
        if (fn.equals("SUM")) {
            Column c = conceptColumn(CONCEPTS.get("revenue"), table);
            if (c != null && c.numeric()) return "SUM(" + c.name() + ")";
        }
        return null;
    }

    /**
     * "highest paid employee", "who has the lowest salary", "product with the highest price" ask
     * for the row; "highest salary" / "maximum price of products" ask for the value.
     */
    private boolean asksForRow(String lower, List<String> words, Table table) {
        if (words.isEmpty()) return false;
        if (List.of("who", "which").contains(words.get(0))) return true;
        if (lower.matches(".*\\b(with|whose|having|has)\\s+(the\\s+)?(maximum|max|highest|largest|biggest|minimum|min|lowest|smallest)\\b.*")) return true;
        Matcher m = Pattern.compile("\\b(maximum|max|highest|largest|biggest|minimum|min|lowest|smallest)\\b").matcher(lower);
        if (!m.find()) return false;
        // a table word right after the superlative phrase ("highest paid employee") asks for the row;
        // "max age from customers" / "lowest price of products" ask for the value
        List<String> after = words(lower.substring(m.end()));
        for (int i = 0; i < after.size(); i++) {
            if (tableFor(after.get(i)) == table) {
                return i == 0 || !List.of("from", "of", "in", "among", "across", "for").contains(after.get(i - 1));
            }
        }
        return false;
    }

    private static final Pattern SUPERLATIVE = Pattern.compile(
            "\\b(?:the\\s+)?(?:(most|least)\\s+([a-z]+)|(cheapest|priciest|oldest|youngest|newest|latest|highest|lowest|largest|smallest|biggest))\\b");

    private String superlative(String lower, List<String> words, Table table) {
        int limit = 1;
        Matcher top = Pattern.compile("\\b(?:top|first)\\s+(\\d+)\\b").matcher(lower);
        boolean hasTop = top.find();
        if (hasTop) limit = Integer.parseInt(top.group(1));
        Matcher by = Pattern.compile("\\bby\\s+([a-z_]+)(?:\\s+(asc|desc))?").matcher(lower);
        if (hasTop && by.find()) {
            Column c = columnFor(by.group(1), table, true);
            if (c != null) return " ORDER BY " + c.name() + ("asc".equals(by.group(2)) ? " ASC" : " DESC") + " LIMIT " + limit;
        }
        Matcher m = SUPERLATIVE.matcher(lower);
        if (!m.find()) return null;
        String word;
        boolean desc;
        if (m.group(1) != null) {
            word = m.group(2);
            desc = m.group(1).equals("most");
            if (word.equals("expensive")) word = "price";
        } else {
            word = m.group(3);
            desc = !List.of("cheapest", "youngest", "lowest", "smallest").contains(word);
            word = switch (word) {
                case "cheapest", "priciest" -> "price";
                case "oldest", "youngest" -> "age";
                case "newest", "latest" -> "date";
                default -> null;     // "highest price" -> the next word names the column
            };
            if (word == null) {
                int i = lower.indexOf(m.group(3)) + m.group(3).length();
                List<String> next = words(lower.substring(i));
                word = next.isEmpty() ? null : next.get(0);
            }
        }
        Column c = word == null ? null : columnFor(word, table, true);
        if (c == null && "date".equals(word)) c = yearColumn(words, table);
        if (c == null) return null;
        return " ORDER BY " + c.name() + (desc ? " DESC" : " ASC") + " LIMIT " + limit;
    }

    private static int indexOfPhrase(String lower, String phrase, int from) {
        boolean wordy = Character.isLetterOrDigit(phrase.charAt(0));
        int idx = lower.indexOf(phrase, from);
        while (idx >= 0) {
            boolean startOk = !wordy || idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            int end = idx + phrase.length();
            boolean endOk = !wordy || end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (startOk && endOk) return idx;
            idx = lower.indexOf(phrase, idx + 1);
        }
        return -1;
    }

    static String quote(String v) {
        return "'" + v.replace("'", "''") + "'";
    }
}
