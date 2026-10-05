package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;

/**
 * The standard Volcano (Iterator) Model interface for query execution in KoraDB.
 *
 * <p>Every relational operator in the query plan tree implements this interface.
 * Operators form a pull-based pipeline where execution proceeds tuple-by-tuple:
 * <ul>
 *   <li>{@link #open()} initializes the operator state and opens child operators.</li>
 *   <li>{@link #next()} fetches the next output tuple, returning {@code null} when exhausted.</li>
 *   <li>{@link #close()} cleans up resources and closes child operators.</li>
 *   <li>{@link #getSchema()} returns the schema describing the output tuples.</li>
 * </ul>
 */
public interface Operator extends AutoCloseable {

    /**
     * Initializes the operator state and prepares child operators for iteration.
     */
    void open();

    /**
     * Returns the next tuple from this operator, or {@code null} when no more tuples remain.
     */
    Row next();

    /**
     * Closes this operator and releases all associated resources and child operators.
     */
    @Override
    void close();

    /**
     * Returns the schema of rows produced by this operator.
     */
    Schema getSchema();
}
