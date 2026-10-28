package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.session.Session;

import java.nio.file.Path;

/**
 * Child-process workload for the kill -9 tests: commits rows into an indexed table, leaves a
 * transaction with inserts, updates and deletes of indexed keys open, prints READY and waits
 * to be killed.
 */
public final class CrashWorkload {

    public static void main(String[] args) throws Exception {
        KoraDB db = new KoraDB(Path.of(args[0]));
        db.execute("CREATE DATABASE crash");
        db.execute("USE crash");
        db.execute("CREATE TABLE t (id INT, k INT, v VARCHAR)");
        db.execute("CREATE INDEX t_k ON t (k)");
        for (int i = 0; i < 300; i++) {
            db.execute("INSERT INTO t VALUES (" + i + ", " + (i % 30) + ", 'v" + i + "')");
        }
        db.execute("UPDATE t SET k = 99 WHERE id < 20");           // committed key change
        db.execute("DELETE FROM t WHERE id >= 280");               // committed delete

        Session open = db.createSession();
        db.execute(open, "USE crash");
        db.execute(open, "BEGIN");
        for (int i = 1000; i < 1050; i++) {
            db.execute(open, "INSERT INTO t VALUES (" + i + ", " + (i % 30) + ", 'uncommitted')");
        }
        db.execute(open, "UPDATE t SET k = 77 WHERE id >= 100 AND id < 150");
        db.execute(open, "DELETE FROM t WHERE id >= 200 AND id < 230");
        db.checkpoint();                                           // push dirty pages with uncommitted changes

        System.out.println("READY");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
