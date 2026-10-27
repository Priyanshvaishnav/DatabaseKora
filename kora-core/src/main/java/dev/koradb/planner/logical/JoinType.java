package dev.koradb.planner.logical;

/**
 * Logical join semantics.
 */
public enum JoinType {
    INNER,
    /** LEFT OUTER JOIN: unmatched left rows are kept with NULLs for the right side. */
    LEFT,
    CROSS
}
