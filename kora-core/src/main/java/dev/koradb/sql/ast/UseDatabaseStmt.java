package dev.koradb.sql.ast;

/** {@code USE <databaseName>} — switches the active database for the session. */
public record UseDatabaseStmt(String databaseName) implements Statement {}
