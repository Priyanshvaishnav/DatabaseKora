package dev.koradb.sql;

import dev.koradb.sql.ast.*;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.parser.Parser;

import java.util.Optional;

/**
 * Decides whether SQL may run on a read-only node (replica). Shared by the TCP server, the HTTP
 * API and {@code ReplicationReplica}, so the three cannot drift apart.
 *
 * <p>The switch is exhaustive over the sealed {@link Statement} hierarchy without a default
 * branch: adding a statement type does not compile until it is classified here.
 */
public final class ReadOnlyCheck {

    private ReadOnlyCheck() {}

    /** True if executing {@code stmt} cannot change data, schema or the set of databases. */
    public static boolean isReadOnly(Statement stmt) {
        return switch (stmt) {
            case SelectStmt s -> true;
            case ShowStmt s -> true;
            case UseDatabaseStmt s -> true;
            case BeginStmt s -> true;
            case CommitStmt s -> true;
            case RollbackStmt s -> true;
            // EXPLAIN only plans; EXPLAIN ANALYZE runs the statement.
            case ExplainStmt s -> !s.analyze() || isReadOnly(s.statement());
            case InsertStmt s -> false;
            case UpdateStmt s -> false;
            case DeleteStmt s -> false;
            case TruncateStmt s -> false;
            case CreateTableStmt s -> false;
            case DropTableStmt s -> false;
            case AlterTableStmt s -> false;
            case CreateIndexStmt s -> false;
            case DropIndexStmt s -> false;
            case CreateDatabaseStmt s -> false;
            case DropDatabaseStmt s -> false;
        };
    }

    /**
     * Returns the first statement of {@code sql} (which may hold several, separated by
     * semicolons) that is not read-only. Every statement is checked, because the engine
     * executes all of them.
     *
     * @throws dev.koradb.exception.ParseException if {@code sql} does not parse; the engine
     *         would reject it the same way, so callers may let it run to report the error
     */
    public static Optional<Statement> firstWrite(String sql) {
        for (Statement stmt : new Parser(new Lexer(sql).tokenize()).parseAll()) {
            if (!isReadOnly(stmt)) {
                return Optional.of(stmt);
            }
        }
        return Optional.empty();
    }
}
