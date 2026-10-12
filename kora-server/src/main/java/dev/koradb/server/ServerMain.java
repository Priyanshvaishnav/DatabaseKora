package dev.koradb.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point to launch a containerized or standalone KoraDB node.
 * Supports 12-factor environment variables and command-line flags.
 */
public final class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);
    public static final String VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        for (String arg : args) {
            if ("--help".equalsIgnoreCase(arg)) {
                printHelp();
                return;
            }
            if ("-v".equalsIgnoreCase(arg) || "--version".equalsIgnoreCase(arg)) {
                System.out.println("KoraDB Server version " + VERSION);
                return;
            }
        }

        ServerBootstrapConfig config = ServerBootstrapConfig.fromEnvAndArgs(args);
        ServerInstance instance = new ServerInstance(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, stopping KoraDB...");
            instance.stop();
        }, "koradb-shutdown"));

        try {
            instance.start();

            printBanner(config, instance);

            // Keep main thread alive until shutdown signal
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Fatal server error during startup", e);
            System.exit(1);
        }
    }

    private static void printBanner(ServerBootstrapConfig config, ServerInstance instance) {
        System.out.println("================================================================================");
        System.out.printf("  KoraDB v%s - High-Performance Relational Engine%n", VERSION);
        System.out.printf("  Node Role:          %s%n", config.role());
        System.out.printf("  SQL Server:         %s:%d%n", config.host(), instance.getServer().getPort());

        if (config.role() == dev.koradb.replication.model.NodeRole.PRIMARY && instance.getMaster() != null) {
            System.out.printf("  Replication Server: %s:%d (%s)%n",
                    config.host(), instance.getMaster().getPort(), config.replicationMode());
        } else if (config.role() == dev.koradb.replication.model.NodeRole.REPLICA) {
            System.out.printf("  Tracking Primary:   %s:%d (ID: %s, %s)%n",
                    config.primaryHost(), config.primaryPort(), config.replicaId(), config.replicationMode());
        }

        if (config.httpEnabled() && instance.getHttpServer() != null) {
            System.out.printf("  HTTP REST API:      http://%s:%d/v1/query | /v1/health%n",
                    config.host(), instance.getHttpServer().getPort());
        }

        if (config.metricsEnabled() && instance.getMetricsServer() != null) {
            System.out.printf("  Metrics & Health:   http://%s:%d/metrics | /health%n",
                    config.host(), instance.getMetricsServer().getPort());
        }

        System.out.printf("  Storage:            %s%n",
                config.dataDir() != null ? config.dataDir().toAbsolutePath() : "in-memory");
        System.out.println("================================================================================");
        System.out.println("Press Ctrl+C to terminate cleanly");
    }

    private static void printHelp() {
        System.out.println("""
                Usage: koradb-server [OPTIONS]
                
                Starts a KoraDB database node supporting standalone, primary, or replica modes.
                Configuration can also be supplied via environment variables.
                
                Options:
                  -h, --host <HOST>             Network interface to bind (env: KORA_HOST, default: 127.0.0.1 or 0.0.0.0 in container)
                  -p, --port <PORT>             TCP SQL query port (env: KORA_PORT, default: 8765)
                  -d, --data-dir <PATH>         Path to persistent data directory (env: KORA_DATA_DIR, default: in-memory)
                  --role <ROLE>                 Cluster role: PRIMARY, REPLICA, or STANDALONE (env: KORA_ROLE, default: PRIMARY)
                  --replication-port <PORT>     Replication master port on primary (env: KORA_REPLICATION_PORT, default: 8766)
                  --replication-mode <MODE>     Replication mode: ASYNC or SYNC (env: KORA_REPLICATION_MODE, default: ASYNC)
                  --primary-host <HOST>         Primary hostname/IP for replica (env: KORA_PRIMARY_HOST, default: localhost)
                  --primary-port <PORT>         Primary replication port for replica (env: KORA_PRIMARY_PORT, default: 8766)
                  --replica-id <ID>             Replica node identifier (env: KORA_REPLICA_ID, default: hostname)
                  --http-port <PORT>            HTTP REST API query port (env: KORA_HTTP_PORT, default: 8080)
                  --no-http                     Disable HTTP REST query server
                  --metrics-port <PORT>         Prometheus metrics and health port (env: KORA_METRICS_PORT, default: 9100)
                  --no-metrics                  Disable HTTP Prometheus exporter and health endpoint
                  -v, --version                 Print version information and exit
                  --help                        Show this help message and exit
                """);
    }
}
