package dev.koradb.sql.ast;

/** {@code CREATE DATABASE <name>} */
public record CreateDatabaseStmt(String databaseName) implements Statement {}
