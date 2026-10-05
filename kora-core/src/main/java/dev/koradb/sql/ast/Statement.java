package dev.koradb.sql.ast;

/**
 * Root sealed interface for all SQL statement AST nodes.
 *
 * <p>Using a sealed interface with Java 21 allows exhaustive pattern matching
 * via {@code switch}, so the compiler guarantees every statement type is
 * handled. Adding a new statement type requires updating every switch.
 *
 * <p>Milestone 1 permits:
 * <ul>
 *   <li>{@link CreateDatabaseStmt}</li>
 *   <li>{@link DropDatabaseStmt}</li>
 *   <li>{@link UseDatabaseStmt}</li>
 *   <li>{@link CreateTableStmt}</li>
 *   <li>{@link DropTableStmt}</li>
 *   <li>{@link InsertStmt}</li>
 *   <li>{@link SelectStmt}</li>
 *   <li>{@link UpdateStmt}</li>
 *   <li>{@link DeleteStmt}</li>
 * </ul>
 */
public sealed interface Statement
        permits CreateDatabaseStmt,
                DropDatabaseStmt,
                UseDatabaseStmt,
                CreateTableStmt,
                DropTableStmt,
                InsertStmt,
                SelectStmt,
                UpdateStmt,
                DeleteStmt,
                CreateIndexStmt,
                DropIndexStmt,
                ExplainStmt {
}
