package dev.koradb.replication.model;

/**
 * Operating role of a database node in a replicated cluster.
 */
public enum NodeRole {
    /**
     * Accepts read-write transactions, appends to local WAL, and streams WAL to replicas.
     */
    PRIMARY,

    /**
     * Rejects mutations, serves read-only queries with Snapshot Isolation, and replays incoming WAL streams.
     */
    REPLICA,

    /**
     * Standalone single node with replication disabled.
     */
    STANDALONE
}
