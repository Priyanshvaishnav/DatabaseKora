package dev.koradb.exception;

/**
 * Thrown when a catalog operation fails — e.g. table not found,
 * column doesn't exist, type mismatch, duplicate table name.
 */
public class SchemaException extends KoraException {

    public SchemaException(String message) {
        super(message);
    }
}
