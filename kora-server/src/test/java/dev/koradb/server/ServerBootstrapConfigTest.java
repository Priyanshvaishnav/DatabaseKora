package dev.koradb.server;

import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.model.ReplicationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ServerBootstrapConfigTest {

    @Test
    @DisplayName("Should parse default configuration when no args or environment variables are passed")
    void testDefaultConfig() {
        ServerBootstrapConfig config = ServerBootstrapConfig.fromEnvAndArgs(new String[0]);
        assertThat(config.port()).isEqualTo(8765);
        assertThat(config.role()).isEqualTo(NodeRole.PRIMARY);
        assertThat(config.replicationPort()).isEqualTo(8766);
        assertThat(config.replicationMode()).isEqualTo(ReplicationMode.ASYNC);
        assertThat(config.metricsEnabled()).isTrue();
        assertThat(config.metricsPort()).isEqualTo(9100);
        assertThat(config.httpEnabled()).isTrue();
        assertThat(config.httpPort()).isEqualTo(8080);
    }

    @Test
    @DisplayName("CLI flags should override defaults")
    void testCliFlagsOverride() {
        String[] args = {
                "-h", "192.168.1.100",
                "-p", "9999",
                "-d", "/tmp/koradb-data",
                "--role", "replica",
                "--replication-port", "8888",
                "--replication-mode", "sync",
                "--primary-host", "primary.cluster.local",
                "--primary-port", "8888",
                "--replica-id", "replica-k8s-0",
                "--metrics-port", "9191",
                "--no-metrics",
                "--http-port", "8088",
                "--no-http"
        };

        ServerBootstrapConfig config = ServerBootstrapConfig.fromEnvAndArgs(args);
        assertThat(config.host()).isEqualTo("192.168.1.100");
        assertThat(config.port()).isEqualTo(9999);
        assertThat(config.dataDir()).isEqualTo(Path.of("/tmp/koradb-data"));
        assertThat(config.role()).isEqualTo(NodeRole.REPLICA);
        assertThat(config.replicationPort()).isEqualTo(8888);
        assertThat(config.replicationMode()).isEqualTo(ReplicationMode.SYNC);
        assertThat(config.primaryHost()).isEqualTo("primary.cluster.local");
        assertThat(config.primaryPort()).isEqualTo(8888);
        assertThat(config.replicaId()).isEqualTo("replica-k8s-0");
        assertThat(config.metricsPort()).isEqualTo(9191);
        assertThat(config.metricsEnabled()).isFalse();
        assertThat(config.httpPort()).isEqualTo(8088);
        assertThat(config.httpEnabled()).isFalse();
    }

    @Test
    @DisplayName("Should parse standalone role correctly")
    void testStandaloneRole() {
        String[] args = {"--role", "standalone"};
        ServerBootstrapConfig config = ServerBootstrapConfig.fromEnvAndArgs(args);
        assertThat(config.role()).isEqualTo(NodeRole.STANDALONE);
    }
}
