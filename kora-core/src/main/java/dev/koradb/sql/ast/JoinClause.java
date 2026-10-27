package dev.koradb.sql.ast;

/**
 * {@code [INNER|LEFT [OUTER]] JOIN <tableName> [alias] ON <condition>}.
 *
 * @param alias name the joined rows are qualified with (null = table name)
 * @param left  LEFT OUTER JOIN: unmatched rows of the left side are kept, with NULLs for this table
 */
public record JoinClause(String tableName, Expression onCondition, String alias, boolean left) {

    public JoinClause {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Joined table name must not be blank");
        }
    }

    public JoinClause(String tableName, Expression onCondition) {
        this(tableName, onCondition, null, false);
    }

    /** Name rows of this table are qualified with. */
    public String sourceName() {
        return alias != null ? alias : tableName;
    }
}
