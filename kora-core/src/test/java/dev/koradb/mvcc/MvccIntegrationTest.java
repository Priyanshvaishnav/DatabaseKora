package dev.koradb.mvcc;

import dev.koradb.KoraDB;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Table;
import dev.koradb.execution.QueryResult;
import dev.koradb.session.Session;
import dev.koradb.transaction.IsolationLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Milestone 10: Multi-Version Concurrency Control (MVCC)")
class MvccIntegrationTest {

    private KoraDB db;

    @BeforeEach
    void setUp() {
        db = new KoraDB();
        db.execute("CREATE DATABASE testdb");
        db.execute("USE testdb");
        db.execute("CREATE TABLE accounts (id INT, name VARCHAR, balance DOUBLE)");
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Non-blocking reads: active uncommitted writer never blocks readers and uncommitted writes are invisible")
    void testNonBlockingReadsDuringActiveWriter() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 100.0)");

        Session writerSession = db.createSession();
        Session readerSession = db.createSession();
        readerSession.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);

        // Writer begins and inserts Bob
        db.execute(writerSession, "BEGIN");
        db.execute(writerSession, "INSERT INTO accounts VALUES (2, 'Bob', 200.0)");

        // Reader begins in SNAPSHOT isolation — must NOT block and must NOT see uncommitted Bob
        db.execute(readerSession, "BEGIN");

        QueryResult res1 = db.execute(readerSession, "SELECT * FROM accounts");
        assertEquals(1, res1.rows().size(), "Reader should only see Alice, not uncommitted Bob");
        assertEquals("Alice", res1.rows().getFirst().get("name"));

        // Writer commits
        db.execute(writerSession, "COMMIT");

        // Reader under SNAPSHOT still sees only Alice (snapshot taken at first read)
        QueryResult res2 = db.execute(readerSession, "SELECT * FROM accounts");
        assertEquals(1, res2.rows().size(), "Snapshot isolation must preserve snapshot taken earlier");

        db.execute(readerSession, "COMMIT");

        // New transaction sees both Alice and Bob
        QueryResult res3 = db.execute("SELECT * FROM accounts");
        assertEquals(2, res3.rows().size());
    }

    @Test
    @DisplayName("Snapshot Isolation / Repeatable Read: readers see a frozen snapshot despite concurrent committed updates")
    void testSnapshotIsolationRepeatableRead() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 100.0)");

        Session readerSession = db.createSession();
        readerSession.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        db.execute(readerSession, "BEGIN");

        // Initial read: Alice = 100.0
        QueryResult r1 = db.execute(readerSession, "SELECT balance FROM accounts WHERE id = 1");
        assertEquals(100.0, (Double) r1.rows().getFirst().get("balance"));

        // Concurrent session updates Alice and commits
        Session writerSession = db.createSession();
        db.execute(writerSession, "BEGIN");
        db.execute(writerSession, "UPDATE accounts SET balance = 250.0 WHERE id = 1");
        db.execute(writerSession, "COMMIT");

        // Reader session reads again: MUST still see Alice = 100.0 under Snapshot Isolation
        QueryResult r2 = db.execute(readerSession, "SELECT balance FROM accounts WHERE id = 1");
        assertEquals(100.0, (Double) r2.rows().getFirst().get("balance"),
                "Snapshot isolation must guarantee repeatable read of 100.0");

        db.execute(readerSession, "COMMIT");

        // After commit, new read sees 250.0
        QueryResult r3 = db.execute("SELECT balance FROM accounts WHERE id = 1");
        assertEquals(250.0, (Double) r3.rows().getFirst().get("balance"));
    }

    @Test
    @DisplayName("Read Committed: statement-level read views observe newly committed updates")
    void testReadCommittedSeesNewStatements() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 100.0)");

        Session session1 = db.createSession();
        session1.setDefaultIsolationLevel(IsolationLevel.READ_COMMITTED);
        db.execute(session1, "BEGIN");

        QueryResult r1 = db.execute(session1, "SELECT balance FROM accounts WHERE id = 1");
        assertEquals(100.0, (Double) r1.rows().getFirst().get("balance"));

        // Concurrent writer updates and commits
        Session session2 = db.createSession();
        db.execute(session2, "BEGIN");
        db.execute(session2, "UPDATE accounts SET balance = 500.0 WHERE id = 1");
        db.execute(session2, "COMMIT");

        // Under READ_COMMITTED, next statement sees newly committed value
        QueryResult r2 = db.execute(session1, "SELECT balance FROM accounts WHERE id = 1");
        assertEquals(500.0, (Double) r2.rows().getFirst().get("balance"),
                "Read Committed should observe changes committed between statements");

        db.execute(session1, "COMMIT");
    }

    @Test
    @DisplayName("Write-write conflict detection: concurrent modification of same row throws WriteConflictException")
    void testWriteConflictFirstCommitterWins() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 100.0)");

        Session s1 = db.createSession();
        Session s2 = db.createSession();

        s1.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        s2.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);

        db.execute(s1, "BEGIN");
        db.execute(s2, "BEGIN");

        // s1 updates Alice
        db.execute(s1, "UPDATE accounts SET balance = 150.0 WHERE id = 1");

        // s2 attempts to update the same row -> WriteConflictException
        assertThrows(WriteConflictException.class, () -> {
            db.execute(s2, "UPDATE accounts SET balance = 200.0 WHERE id = 1");
        });

        // s2 should have been aborted on conflict
        assertFalse(s2.hasActiveTransaction());

        // s1 successfully commits
        db.execute(s1, "COMMIT");

        QueryResult res = db.execute("SELECT balance FROM accounts WHERE id = 1");
        assertEquals(150.0, (Double) res.rows().getFirst().get("balance"));
    }

    @Test
    @DisplayName("Rollback restores visibility: aborted updates or deletes reset xmax")
    void testRollbackRestoresVisibility() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 100.0)");

        Session s1 = db.createSession();
        s1.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        db.execute(s1, "BEGIN");

        // Delete Alice in s1
        db.execute(s1, "DELETE FROM accounts WHERE id = 1");

        // Alice is no longer visible inside s1
        QueryResult insideS1 = db.execute(s1, "SELECT * FROM accounts WHERE id = 1");
        assertEquals(0, insideS1.rows().size(), "Inside deleting txn, row should be deleted");

        // Concurrent reader under autocommit still sees Alice because s1 is uncommitted
        QueryResult concurrent = db.execute("SELECT * FROM accounts WHERE id = 1");
        assertEquals(1, concurrent.rows().size(), "Concurrent reader must still see row while deletion is uncommitted");

        // s1 rolls back
        db.execute(s1, "ROLLBACK");

        // Alice is restored and visible inside s1 and to all transactions
        QueryResult r2 = db.execute(s1, "SELECT * FROM accounts WHERE id = 1");
        assertEquals(1, r2.rows().size());
        assertEquals(100.0, (Double) r2.rows().getFirst().get("balance"));
    }

    @Test
    @DisplayName("Vacuum engine: prunes dead tuple versions when no active transactions need them")
    void testVacuumReclaimsDeadVersions() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 100.0)");

        // Perform multiple updates, generating older dead versions
        for (int i = 1; i <= 5; i++) {
            Session s = db.createSession();
            db.execute(s, "BEGIN");
            db.execute(s, "UPDATE accounts SET balance = " + (100.0 + i * 10) + " WHERE id = 1");
            db.execute(s, "COMMIT");
        }

        Table table = db.getCatalog().getTable("accounts");
        List<Row> allVersionsBefore = table.getAllVersions();
        // 1 initial insert + 5 updates = 6 total versions stored
        assertEquals(6, allVersionsBefore.size());

        // Vacuum with no active transactions
        int reclaimed = db.vacuum();
        assertEquals(5, reclaimed, "5 dead versions should be reclaimed");

        List<Row> allVersionsAfter = table.getAllVersions();
        assertEquals(1, allVersionsAfter.size(), "Only the single live version should remain");
        assertEquals(150.0, (Double) allVersionsAfter.getFirst().get("balance"));
    }

    @Test
    @DisplayName("DiskTable MVCC: non-blocking reads and vacuum work on persistent disk storage")
    void testDiskTableMvcc(@TempDir Path tempDir) {
        KoraDB diskDb = new KoraDB(tempDir);
        try {
            diskDb.execute("CREATE DATABASE persistent_db");
            diskDb.execute("USE persistent_db");
            diskDb.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE)");
            diskDb.execute("INSERT INTO products VALUES (1, 'Laptop', 1000.0)");

            Session writer = diskDb.createSession();
            Session reader = diskDb.createSession();

            writer.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
            reader.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);

            diskDb.execute(writer, "BEGIN");
            diskDb.execute(writer, "UPDATE products SET price = 1200.0 WHERE id = 1");

            // Reader in SNAPSHOT mode sees original price 1000.0 without blocking
            diskDb.execute(reader, "BEGIN");
            QueryResult r1 = diskDb.execute(reader, "SELECT price FROM products WHERE id = 1");
            assertEquals(1000.0, (Double) r1.rows().getFirst().get("price"));

            diskDb.execute(writer, "COMMIT");

            // Reader still sees 1000.0
            QueryResult r2 = diskDb.execute(reader, "SELECT price FROM products WHERE id = 1");
            assertEquals(1000.0, (Double) r2.rows().getFirst().get("price"));

            diskDb.execute(reader, "COMMIT");

            // Vacuum on disk
            int reclaimed = diskDb.vacuum();
            assertEquals(1, reclaimed, "Old version of product on disk should be vacuumed");

            QueryResult r3 = diskDb.execute("SELECT price FROM products WHERE id = 1");
            assertEquals(1200.0, (Double) r3.rows().getFirst().get("price"));
        } finally {
            diskDb.close();
        }
    }

    @Test
    @DisplayName("IndexScan with MVCC: index lookups filter invisible tuple versions properly")
    void testIndexScanMvccVisibility(@TempDir Path tempDir) {
        KoraDB diskDb = new KoraDB(tempDir);
        try {
            diskDb.execute("CREATE DATABASE index_db");
            diskDb.execute("USE index_db");
            diskDb.execute("CREATE TABLE users (id INT, username VARCHAR, age INT)");
            diskDb.execute("CREATE INDEX idx_users_id ON users (id)");
            diskDb.execute("INSERT INTO users VALUES (42, 'charlie', 30)");

            Session s1 = diskDb.createSession();
            Session s2 = diskDb.createSession();

            s1.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
            s2.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);

            diskDb.execute(s1, "BEGIN");
            diskDb.execute(s1, "UPDATE users SET age = 31 WHERE id = 42");

            diskDb.execute(s2, "BEGIN");

            // Index scan for id = 42 in s2
            QueryResult r = diskDb.execute(s2, "SELECT age FROM users WHERE id = 42");
            assertEquals(1, r.rows().size());
            assertEquals(30, (Integer) r.rows().getFirst().get("age"),
                    "Index scan in s2 must read the snapshot visible version (age 30)");

            diskDb.execute(s1, "COMMIT");
            diskDb.execute(s2, "COMMIT");

            QueryResult rCommitted = diskDb.execute("SELECT age FROM users WHERE id = 42");
            assertEquals(31, (Integer) rCommitted.rows().getFirst().get("age"));
        } finally {
            diskDb.close();
        }
    }
}
