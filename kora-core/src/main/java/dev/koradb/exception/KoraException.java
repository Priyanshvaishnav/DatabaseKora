package dev.koradb.exception;

/**
 * Root checked exception for all KoraDB errors.
 *
 * <p>Subclasses are used to distinguish error categories:
 * <ul>
 *   <li>{@link ParseException}     — SQL syntax error</li>
 *   <li>{@link SchemaException}    — table/column not found, type mismatch</li>
 *   <li>{@link ExecutionException} — runtime error during query execution</li>
 * </ul>
 *
 * <p>We deliberately use a checked exception hierarchy so callers are forced
 * to handle or propagate errors explicitly — appropriate for a database engine
 * where silent failures are unacceptable.
 */
public class KoraException extends RuntimeException {

    public KoraException(String message) {
        super(message);
    }

    public KoraException(String message, Throwable cause) {
        super(message, cause);
    }
}
