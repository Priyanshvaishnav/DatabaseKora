package dev.koradb.sql.ast;

/**
 * Supported SQL data types in Milestone 1.
 *
 * <p>The Java type used to represent each value at runtime:
 * <ul>
 *   <li>INT       → {@link Integer}</li>
 *   <li>BIGINT    → {@link Long}</li>
 *   <li>BOOLEAN   → {@link Boolean}</li>
 *   <li>VARCHAR   → {@link String}</li>
 *   <li>DOUBLE    → {@link Double}</li>
 *   <li>TIMESTAMP → {@link java.time.LocalDateTime}</li>
 * </ul>
 */
public enum DataType {
    INT,
    BIGINT,
    BOOLEAN,
    VARCHAR,
    DOUBLE,
    TIMESTAMP
}
