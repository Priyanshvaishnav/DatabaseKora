package dev.koradb.exception;

/**
 * Thrown when the storage engine encounters an I/O error or an invalid
 * page/slot access (e.g., out-of-bounds slot index, deleted slot read,
 * insufficient page space).
 *
 * <p>In Milestone 8 (WAL) this will be extended to include log-write failures.
 */
public class StorageException extends KoraException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
