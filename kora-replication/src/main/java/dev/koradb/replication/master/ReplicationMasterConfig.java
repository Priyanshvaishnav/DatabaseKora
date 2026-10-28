package dev.koradb.replication.master;

import dev.koradb.replication.model.ReplicationMode;

import java.util.Objects;

/**
 * Configuration for the Primary {@link ReplicationMaster}.
 *
 * @param authToken shared secret replicas must present in their handshake; empty means none
 */
public record ReplicationMasterConfig(
        String host,
        int port,
        String primaryId,
        ReplicationMode defaultMode,
        long heartbeatIntervalMs,
        long syncTimeoutMs,
        int batchMaxRecords,
        String authToken
) {

    public static final int DEFAULT_PORT = 8766;
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final long DEFAULT_HEARTBEAT_MS = 1000L;
    public static final long DEFAULT_SYNC_TIMEOUT_MS = 5000L;
    public static final int DEFAULT_BATCH_MAX_RECORDS = 100;

    public ReplicationMasterConfig {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(primaryId, "primaryId must not be null");
        Objects.requireNonNull(defaultMode, "defaultMode must not be null");
        if (authToken == null) authToken = "";
    }

    public ReplicationMasterConfig(String host, int port, String primaryId, ReplicationMode defaultMode,
                                   long heartbeatIntervalMs, long syncTimeoutMs, int batchMaxRecords) {
        this(host, port, primaryId, defaultMode, heartbeatIntervalMs, syncTimeoutMs, batchMaxRecords, "");
    }

    public static ReplicationMasterConfig defaultConfig() {
        return new ReplicationMasterConfig(
                DEFAULT_HOST,
                DEFAULT_PORT,
                "primary-1",
                ReplicationMode.ASYNC,
                DEFAULT_HEARTBEAT_MS,
                DEFAULT_SYNC_TIMEOUT_MS,
                DEFAULT_BATCH_MAX_RECORDS
        );
    }

    public static ReplicationMasterConfig onPort(int port) {
        return new ReplicationMasterConfig(
                DEFAULT_HOST,
                port,
                "primary-1",
                ReplicationMode.ASYNC,
                DEFAULT_HEARTBEAT_MS,
                DEFAULT_SYNC_TIMEOUT_MS,
                DEFAULT_BATCH_MAX_RECORDS
        );
    }

    public ReplicationMasterConfig withAuthToken(String authToken) {
        return new ReplicationMasterConfig(host, port, primaryId, defaultMode, heartbeatIntervalMs, syncTimeoutMs,
                batchMaxRecords, authToken);
    }

    @Override
    public String toString() {
        return "ReplicationMasterConfig[host=" + host + ", port=" + port + ", primaryId=" + primaryId
                + ", defaultMode=" + defaultMode + ", authToken=" + (authToken.isEmpty() ? "<none>" : "****") + "]";
    }
}
