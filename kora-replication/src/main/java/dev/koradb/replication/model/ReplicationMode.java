package dev.koradb.replication.model;

/**
 * Replication synchronization mode between Primary and Replica.
 */
public enum ReplicationMode {
    /**
     * Asynchronous: Primary returns commit immediately after local WAL flush;
     * Replicas stream and apply changes in the background.
     */
    ASYNC((byte) 1),

    /**
     * Synchronous: Primary commit blocks until at least one synchronous replica
     * acknowledges that the commit LSN has been flushed to its local disk.
     */
    SYNC((byte) 2),

    /**
     * Semi-Synchronous: Primary commit blocks until at least one replica
     * acknowledges receiving the commit LSN in memory (relay log).
     */
    SEMI_SYNC((byte) 3);

    private final byte code;

    ReplicationMode(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static ReplicationMode fromCode(byte code) {
        return switch (code) {
            case 1 -> ASYNC;
            case 2 -> SYNC;
            case 3 -> SEMI_SYNC;
            default -> throw new IllegalArgumentException("Unknown ReplicationMode code: " + code);
        };
    }
}
