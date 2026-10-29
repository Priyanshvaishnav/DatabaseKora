import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual load and correctness test for KoraDB through the JDBC driver.
 *
 * <p>Generates a deterministic data set (fixed seed), loads it, then checks every answer the
 * database gives against values computed independently in Java. Run it against a running server:
 *
 * <pre>
 *   java -cp kora-jdbc/target/koradb-jdbc.jar tools/manual-test/JdbcLoadTest.java \
 *        --url jdbc:koradb://127.0.0.1:8765/loadtest --user kora --password test123 --orders 100000
 * </pre>
 *
 * Phases: load, verify, index, mutate, rollback, concurrency. After killing and restarting the
 * server, run again with {@code --verify-only} (same --customers/--orders/--seed) to check that
 * every committed change survived the crash.
 */
public class JdbcLoadTest {

    static String url = "jdbc:koradb://127.0.0.1:8765/loadtest", user = "", password = "";
    static int customers = 5_000, orders = 50_000, threads = 8, batch = 500;
    static long seed = 42;
    static boolean verifyOnly = false;

    static final String[] CITIES = {"Pune", "Delhi", "Mumbai", "Goa", "O'Hare", "Zürich", "New York", "Chennai"};
    static final String[] STATUS = {"pending", "shipped", "delivered", "cancelled"};

    record Customer(int id, String name, String city, int age, double credit, boolean active, LocalDateTime joined) {}
    record Order(long id, int cid, double amount, String status, int qty) {}

    static final List<Customer> CUSTOMERS = new ArrayList<>();
    static final List<Order> ORDERS = new ArrayList<>();
    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = args[++i];
                case "--user" -> user = args[++i];
                case "--password" -> password = args[++i];
                case "--customers" -> customers = Integer.parseInt(args[++i]);
                case "--orders" -> orders = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--batch" -> batch = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--verify-only" -> verifyOnly = true;
                default -> { System.err.println("Unknown option " + args[i]); System.exit(2); }
            }
        }
        generate();
        System.out.printf("KoraDB JDBC load test: %s, %,d customers, %,d orders, seed %d%n", url, customers, orders, seed);

        try (Connection c = connect()) {
            if (!verifyOnly) {
                phase("1. Load", () -> load(c));
                phase("2. Verify loaded data", () -> verifyBase(c, false));
                phase("3. Index", () -> indexes(c));
                phase("4. Updates and deletes", () -> mutate(c));
                phase("5. Rollback", () -> rollback(c));
                phase("6. Concurrent transfers", JdbcLoadTest::concurrency);
            }
            phase("Final state", () -> verifyFinal(c));
        }
        System.out.printf("%nRESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ data

    static void generate() {
        Random r = new Random(seed);
        for (int i = 1; i <= customers; i++) {
            CUSTOMERS.add(new Customer(i, "cust" + i, CITIES[r.nextInt(CITIES.length)], 18 + r.nextInt(60),
                    Math.round(r.nextDouble() * 100_000) / 100.0, r.nextBoolean(),
                    LocalDateTime.of(2015 + r.nextInt(10), 1 + r.nextInt(12), 1 + r.nextInt(28), r.nextInt(24), r.nextInt(60))));
        }
        for (long i = 1; i <= orders; i++) {
            // skewed: a tenth of the customers get about half of the orders (many index duplicates)
            int cid = r.nextBoolean() ? 1 + r.nextInt(Math.max(1, customers / 10)) : 1 + r.nextInt(customers);
            ORDERS.add(new Order(i, cid, Math.round(r.nextDouble() * 50_000) / 100.0, STATUS[r.nextInt(STATUS.length)], 1 + r.nextInt(9)));
        }
    }

    /** Applies the same changes as {@link #mutate} to the in-memory copy. */
    static List<Order> ordersAfterMutations() {
        List<Order> out = new ArrayList<>();
        for (Order o : ORDERS) {
            if (o.status().equals("cancelled") && o.amount() < 100) continue;              // DELETE
            String st = o.status().equals("pending") && o.amount() > 400 ? "shipped" : o.status();
            int qty = o.cid() % 7 == 0 ? o.qty() + 1 : o.qty();                            // UPDATE qty = qty + 1
            out.add(new Order(o.id(), o.cid(), o.amount(), st, qty));
        }
        return out;
    }

    // ------------------------------------------------------------------ phases

    static void load(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("DROP TABLE IF EXISTS customers");
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR NOT NULL, city VARCHAR, age INT, "
                    + "credit DOUBLE, active BOOLEAN, joined TIMESTAMP)");
            s.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, cid INT, amount DOUBLE, status VARCHAR, qty INT)");
        }
        // customers through PreparedStatement batches (exercises every setter), orders through
        // multi-row INSERTs; both in explicit transactions of --batch rows.
        long t0 = System.nanoTime();
        c.setAutoCommit(false);
        try {
            loadRows(c, t0);
        } catch (Exception e) {
            c.rollback();                      // leave the connection usable for the next phases
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static void loadRows(Connection c, long t0) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO customers VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int n = 0;
            for (Customer cu : CUSTOMERS) {
                ps.setInt(1, cu.id());
                ps.setString(2, cu.name());
                ps.setString(3, cu.city());
                ps.setInt(4, cu.age());
                ps.setDouble(5, cu.credit());
                ps.setBoolean(6, cu.active());
                ps.setTimestamp(7, Timestamp.valueOf(cu.joined()));
                ps.addBatch();
                if (++n % batch == 0) { ps.executeBatch(); c.commit(); }
            }
            ps.executeBatch();
            c.commit();
        }
        report("customers loaded", true, rate(customers, t0));

        t0 = System.nanoTime();
        try (Statement s = c.createStatement()) {
            for (int from = 0; from < ORDERS.size(); from += batch) {
                StringBuilder sb = new StringBuilder("INSERT INTO orders VALUES ");
                for (int i = from; i < Math.min(from + batch, ORDERS.size()); i++) {
                    Order o = ORDERS.get(i);
                    if (i > from) sb.append(", ");
                    sb.append('(').append(o.id()).append(", ").append(o.cid()).append(", ").append(o.amount())
                            .append(", '").append(o.status()).append("', ").append(o.qty()).append(')');
                }
                s.executeUpdate(sb.toString());
                c.commit();
                if ((from / batch) % 20 == 19) System.out.printf("   ... %,d orders%n", from + batch);
            }
        }
        report("orders loaded", true, rate(orders, t0));
    }

    static void verifyBase(Connection c, boolean indexed) throws Exception {
        check(c, "COUNT(*) customers", "SELECT COUNT(*) FROM customers", customers);
        check(c, "COUNT(*) orders", "SELECT COUNT(*) FROM orders", orders);
        check(c, "SUM(credit)", "SELECT SUM(credit) FROM customers", CUSTOMERS.stream().mapToDouble(Customer::credit).sum());
        check(c, "SUM(amount)", "SELECT SUM(amount) FROM orders", ORDERS.stream().mapToDouble(Order::amount).sum());
        check(c, "MIN/MAX age", "SELECT MIN(age) FROM customers", CUSTOMERS.stream().mapToInt(Customer::age).min().orElse(0));

        Map<String, Long> byCity = new TreeMap<>();
        CUSTOMERS.forEach(cu -> byCity.merge(cu.city(), 1L, Long::sum));
        checkGroups(c, "GROUP BY city (incl. quote and UTF-8)", "SELECT city, COUNT(*) FROM customers GROUP BY city", byCity);

        Map<String, Long> byStatus = new TreeMap<>();
        ORDERS.forEach(o -> byStatus.merge(o.status(), 1L, Long::sum));
        checkGroups(c, "GROUP BY status", "SELECT status, COUNT(*) FROM orders GROUP BY status", byStatus);

        check(c, "JOIN orders x customers", "SELECT COUNT(*) FROM orders o JOIN customers c ON o.cid = c.id", orders);
        double puneAmount = ORDERS.stream().filter(o -> CUSTOMERS.get(o.cid() - 1).city().equals("Pune")).mapToDouble(Order::amount).sum();
        check(c, "JOIN + WHERE + SUM", "SELECT SUM(o.amount) FROM orders o JOIN customers c ON o.cid = c.id WHERE c.city = 'Pune'", puneAmount);

        List<Long> top5 = ORDERS.stream().sorted(Comparator.comparingDouble(Order::amount).reversed().thenComparingLong(Order::id))
                .limit(5).map(Order::id).toList();
        List<Long> got = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM orders ORDER BY amount DESC, id ASC LIMIT 5")) {
            while (rs.next()) got.add(rs.getLong(1));
        }
        report("ORDER BY amount DESC LIMIT 5", got.equals(top5), got + " expected " + top5);

        // typed round trip of one row through ResultSet getters
        Customer cu = CUSTOMERS.get(customers / 2);
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM customers WHERE id = ?")) {
            ps.setInt(1, cu.id());
            try (ResultSet rs = ps.executeQuery()) {
                boolean ok = rs.next() && rs.getString("name").equals(cu.name()) && rs.getString("city").equals(cu.city())
                        && rs.getInt("age") == cu.age() && Math.abs(rs.getDouble("credit") - cu.credit()) < 1e-9
                        && rs.getBoolean("active") == cu.active()
                        && rs.getTimestamp("joined").toLocalDateTime().equals(cu.joined());
                report("PreparedStatement + typed getters round trip", ok, "customer " + cu.id());
            }
        }
    }

    static void indexes(Connection c) throws Exception {
        long t0 = System.nanoTime();
        try (Statement s = c.createStatement()) {
            s.execute("CREATE INDEX idx_orders_cid ON orders (cid)");
            s.execute("CREATE INDEX idx_customers_city ON customers (city)");
        }
        report("CREATE INDEX x2", true, ms(t0));

        Map<Integer, Long> perCid = new HashMap<>();
        ORDERS.forEach(o -> perCid.merge(o.cid(), 1L, Long::sum));
        Random r = new Random(seed + 1);
        int wrong = 0;
        long t1 = System.nanoTime();
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM orders WHERE cid = ?")) {
            for (int i = 0; i < 200; i++) {
                int cid = i < 20 ? 1 + i : 1 + r.nextInt(customers);      // the first 20 are the heaviest keys
                ps.setInt(1, cid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) != perCid.getOrDefault(cid, 0L)) wrong++;
                }
            }
        }
        report("indexed lookups for 200 customers match", wrong == 0, wrong + " wrong, avg "
                + (System.nanoTime() - t1) / 200 / 1_000_000.0 + " ms/query");
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("EXPLAIN SELECT * FROM orders WHERE cid = 7")) {
            StringBuilder plan = new StringBuilder();
            while (rs.next()) plan.append(rs.getString(1)).append('\n');
            report("EXPLAIN uses the index", plan.toString().contains("IndexScan"), "");
        }
        verifyBase(c, true);   // every answer again, now that indexes exist
    }

    static void mutate(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            long expectDel = ORDERS.stream().filter(o -> o.status().equals("cancelled") && o.amount() < 100).count();
            report("DELETE count", s.executeUpdate("DELETE FROM orders WHERE status = 'cancelled' AND amount < 100") == expectDel, "");
            long expectUpd = ORDERS.stream().filter(o -> o.status().equals("pending") && o.amount() > 400
                    && !(o.status().equals("cancelled") && o.amount() < 100)).count();
            report("UPDATE status count", s.executeUpdate("UPDATE orders SET status = 'shipped' WHERE status = 'pending' AND amount > 400") == expectUpd, "");
            s.executeUpdate("UPDATE orders SET qty = qty + 1 WHERE cid % 7 = 0");
        }
        verifyOrders(c, ordersAfterMutations(), "after DML");
    }

    static void rollback(Connection c) throws Exception {
        long before = ordersAfterMutations().size();
        long keptInTxn = ordersAfterMutations().stream().filter(o -> o.id() <= 10).count() + 1;
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM orders WHERE id > 10");
            s.executeUpdate("UPDATE customers SET credit = 0");
            s.executeUpdate("INSERT INTO orders VALUES (999999999, 1, 1.0, 'ghost', 1)");
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM orders")) {
                rs.next();
                report("transaction sees its own changes", rs.getLong(1) == keptInTxn, "");
            }
            c.rollback();
        } finally {
            c.setAutoCommit(true);
        }
        check(c, "ROLLBACK restored orders", "SELECT COUNT(*) FROM orders", before);
        check(c, "ROLLBACK restored credit", "SELECT SUM(credit) FROM customers", CUSTOMERS.stream().mapToDouble(Customer::credit).sum());
        check(c, "ROLLBACK removed the insert", "SELECT COUNT(*) FROM orders WHERE status = 'ghost'", 0);
    }

    static void concurrency() throws Exception {
        int perThread = 50, accounts = Math.min(50, customers);
        AtomicInteger committed = new AtomicInteger(), conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            long s = seed * 100 + t;
            fs.add(pool.submit(() -> {
                Random r = new Random(s);
                try (Connection c = connect(); Statement st = c.createStatement()) {
                    c.setAutoCommit(false);
                    for (int i = 0; i < perThread; i++) {
                        int from = 1 + r.nextInt(accounts), to = 1 + (from + r.nextInt(accounts - 1)) % accounts;
                        while (true) {
                            try {
                                st.executeUpdate("UPDATE customers SET credit = credit - 1.5 WHERE id = " + from);
                                st.executeUpdate("UPDATE customers SET credit = credit + 1.5 WHERE id = " + to);
                                c.commit();
                                committed.incrementAndGet();
                                break;
                            } catch (SQLException e) {
                                conflicts.incrementAndGet();
                                c.rollback();
                                Thread.sleep(r.nextInt(5));
                            }
                        }
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(10, TimeUnit.MINUTES);
        pool.shutdown();
        report(threads + " threads x " + perThread + " transfers all committed", committed.get() == threads * perThread,
                committed + " committed, " + conflicts + " conflicts retried, " + ms(t0));
    }

    static void verifyFinal(Connection c) throws Exception {
        check(c, "customers present", "SELECT COUNT(*) FROM customers", customers);
        // transfers move credit between customers, so only the total is known
        check(c, "total credit unchanged", "SELECT SUM(credit) FROM customers", CUSTOMERS.stream().mapToDouble(Customer::credit).sum());
        verifyOrders(c, ordersAfterMutations(), "final");
    }

    static void verifyOrders(Connection c, List<Order> expected, String when) throws Exception {
        check(c, "orders COUNT " + when, "SELECT COUNT(*) FROM orders", expected.size());
        check(c, "orders SUM(qty) " + when, "SELECT SUM(qty) FROM orders", expected.stream().mapToLong(Order::qty).sum());
        Map<String, Long> byStatus = new TreeMap<>();
        expected.forEach(o -> byStatus.merge(o.status(), 1L, Long::sum));
        checkGroups(c, "GROUP BY status " + when, "SELECT status, COUNT(*) FROM orders GROUP BY status", byStatus);
        long heavy = expected.stream().filter(o -> o.cid() == 1).count();
        check(c, "lookup cid = 1 " + when, "SELECT COUNT(*) FROM orders WHERE cid = 1", heavy);
    }

    // ------------------------------------------------------------------ helpers

    static Connection connect() throws SQLException {
        return user.isEmpty() ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
    }

    interface Step { void run() throws Exception; }

    static void phase(String name, Step step) {
        System.out.println("\n== " + name);
        try {
            step.run();
        } catch (Exception e) {
            report(name + " threw", false, e.toString());
        }
    }

    static void check(Connection c, String what, String sql, double expected) throws SQLException {
        long t0 = System.nanoTime();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            double got = rs.getDouble(1);
            boolean ok = Math.abs(got - expected) <= Math.max(1e-6, Math.abs(expected) * 1e-9);
            report(what, ok, (ok ? "" : "got " + got + " expected " + expected + ", ") + ms(t0));
        }
    }

    static void checkGroups(Connection c, String what, String sql, Map<String, Long> expected) throws SQLException {
        Map<String, Long> got = new TreeMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) got.put(rs.getString(1), rs.getLong(2));
        }
        report(what, got.equals(expected), got.equals(expected) ? got.size() + " groups" : "got " + got + " expected " + expected);
    }

    static void report(String what, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.printf("  %s  %-55s %s%n", ok ? "PASS" : "FAIL", what, detail);
    }

    static String ms(long t0) { return (System.nanoTime() - t0) / 1_000_000 + " ms"; }

    static String rate(int rows, long t0) {
        double s = (System.nanoTime() - t0) / 1e9;
        return String.format("%,d rows in %.1f s (%,.0f rows/s)", rows, s, rows / s);
    }
}
