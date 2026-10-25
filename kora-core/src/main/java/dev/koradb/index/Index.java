package dev.koradb.index;

import dev.koradb.sql.ast.DataType;
import dev.koradb.storage.RecordId;

import java.util.List;
import java.util.Optional;

/**
 * Common interface for all database indexes in KoraDB.
 */
public interface Index extends AutoCloseable {

    /** Returns the index name (e.g. "idx_users_id"). */
    String getName();

    /** Returns the name of the table this index belongs to. */
    String getTableName();

    /** Returns the column indexed. */
    String getColumnName();

    /** Returns the data type of the indexed column. */
    DataType getKeyType();

    /** Returns true if this is a unique index. */
    boolean isUnique();

    /**
     * Inserts an entry into the index.
     *
     * @param key the search key value
     * @param rid the RecordId of the row containing this key
     */
    void insert(IndexKey key, RecordId rid);

    /**
     * Inserts an entry without enforcing uniqueness. The executor calls this after doing its
     * own MVCC-aware duplicate check (entries pointing at dead row versions are not duplicates).
     */
    default void insertUnchecked(IndexKey key, RecordId rid) {
        insert(key, rid);
    }

    /**
     * Finds a single matching RecordId for the given key.
     */
    Optional<RecordId> search(IndexKey key);

    /**
     * Finds all matching RecordIds for the given key (for non-unique indexes).
     */
    List<RecordId> searchAll(IndexKey key);

    /**
     * Performs a range scan between {@code low} and {@code high}.
     * Pass {@code null} for unbounded ends.
     */
    List<RecordId> rangeScan(IndexKey low, IndexKey high, boolean lowInclusive, boolean highInclusive);

    /**
     * Removes an entry from the index.
     */
    boolean delete(IndexKey key, RecordId rid);

    /**
     * Flushes any cached dirty pages to disk.
     */
    void flush();

    @Override
    void close();
}
