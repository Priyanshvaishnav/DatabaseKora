package dev.koradb.exception;

/**
 * Thrown when the executor encounters a runtime error — e.g. a type cannot
 * be compared, an expression cannot be evaluated, or an operation is unsupported.
 */
public class ExecutionException extends KoraException {

    public ExecutionException(String message) {
        super(message);
    }

    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
