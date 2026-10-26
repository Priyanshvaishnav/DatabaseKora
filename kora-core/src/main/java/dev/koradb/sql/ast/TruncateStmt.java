package dev.koradb.sql.ast;

/** {@code TRUNCATE [TABLE] <name>}: deletes every row (transactional, like DELETE without WHERE). */
public record TruncateStmt(String tableName) implements Statement {}
