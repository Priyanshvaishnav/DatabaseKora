package dev.koradb.transaction.undo;

import dev.koradb.catalog.Catalog;

/**
 * Functional undo unit representing a single compensatory action to revert a database mutation during rollback.
 */
@FunctionalInterface
public interface UndoAction {

    /**
     * Executes the undo operation against the database catalog.
     *
     * @param catalog active catalog
     */
    void undo(Catalog catalog);
}
