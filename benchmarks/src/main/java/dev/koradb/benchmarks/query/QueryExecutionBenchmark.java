package dev.koradb.benchmarks.query;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * JMH Microbenchmark evaluating SQL execution engine operator pipeline:
 * table scans, index scans, hash joins, aggregations, and sorting.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class QueryExecutionBenchmark {

    public static final int PRODUCT_COUNT = 1000;
    public static final int ORDER_COUNT = 500;

    private Path tempDir;
    private KoraDB db;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("kora-query-bench");
        db = new KoraDB(tempDir);

        db.execute("CREATE DATABASE store");
        db.execute("USE store");

        db.execute("CREATE TABLE products (id INT, name VARCHAR, category VARCHAR, price DOUBLE, stock INT)");
        db.execute("CREATE INDEX idx_prod_id ON products (id)");

        String[] categories = {"Electronics", "Books", "Clothing", "Home"};
        for (int i = 1; i <= PRODUCT_COUNT; i++) {
            String cat = categories[i % categories.length];
            double price = 10.0 + (i % 500);
            int stock = 5 + (i % 100);
            db.execute(String.format("INSERT INTO products VALUES (%d, 'Product-%d', '%s', %.2f, %d)",
                    i, i, cat, price, stock));
        }

        db.execute("CREATE TABLE orders (id INT, product_id INT, quantity INT, total DOUBLE)");
        for (int i = 1; i <= ORDER_COUNT; i++) {
            int prodId = 1 + (i % PRODUCT_COUNT);
            int qty = 1 + (i % 5);
            double total = qty * 50.0;
            db.execute(String.format("INSERT INTO orders VALUES (%d, %d, %d, %.2f)",
                    i, prodId, qty, total));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (db != null) {
            try {
                db.close();
            } catch (Exception ignored) {}
        }
        if (tempDir != null) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Benchmark
    public QueryResult pointQueryIndexed() {
        int id = ThreadLocalRandom.current().nextInt(1, PRODUCT_COUNT + 1);
        return db.execute("SELECT * FROM products WHERE id = " + id);
    }

    @Benchmark
    public QueryResult rangeScanFilter() {
        return db.execute("SELECT * FROM products WHERE price > 200.0 AND price < 300.0");
    }

    @Benchmark
    public QueryResult aggregationGroupBy() {
        return db.execute("SELECT category, COUNT(*), SUM(price), AVG(price) FROM products GROUP BY category");
    }

    @Benchmark
    public QueryResult joinQuery() {
        return db.execute("SELECT products.name, orders.quantity, orders.total FROM products JOIN orders ON products.id = orders.product_id WHERE products.price > 100.0");
    }

    @Benchmark
    public QueryResult sortAndLimit() {
        return db.execute("SELECT name, price FROM products ORDER BY price DESC LIMIT 20");
    }
}
