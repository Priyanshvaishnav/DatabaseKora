package dev.koradb.replication.protocol;

/**
 * Message types for the KoraDB replication binary wire protocol.
 */
public enum ReplicationMessageType {
    /**
     * Handshake request sent by Replica to Primary.
     */
    HANDSHAKE_REQ((byte) 1),

    /**
     * Handshake response sent by Primary to Replica.
     */
    HANDSHAKE_RESP((byte) 2),

    /**
     * Streamed batch of WAL records from Primary to Replica.
     */
    WAL_DATA((byte) 3),

    /**
     * Acknowledgment from Replica to Primary reporting flushed and applied LSNs.
     */
    WAL_ACK((byte) 4),

    /**
     * Heartbeat message from Primary to Replica to monitor connectivity and lag.
     */
    HEARTBEAT((byte) 5),

    /**
     * Heartbeat response from Replica to Primary.
     */
    HEARTBEAT_ACK((byte) 6),

    /**
     * Request initial snapshot / state transfer for cold-start catch-up.
     */
    SNAPSHOT_REQ((byte) 7),

    /**
     * Snapshot payload containing base files and snapshot LSN.
     */
    SNAPSHOT_RESP((byte) 8);

    private final byte code;

    ReplicationMessageType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static ReplicationMessageType fromCode(byte code) {
        return switch (code) {
            case 1 -> HANDSHAKE_REQ;
            case 2 -> HANDSHAKE_RESP;
            case 3 -> WAL_DATA;
            case 4 -> WAL_ACK;
            case 5 -> HEARTBEAT;
            case 6 -> HEARTBEAT_ACK;
            case 7 -> SNAPSHOT_REQ;
            case 8 -> SNAPSHOT_RESP;
            default -> throw new IllegalArgumentException("Unknown ReplicationMessageType code: " + code);
        };
    }
}
