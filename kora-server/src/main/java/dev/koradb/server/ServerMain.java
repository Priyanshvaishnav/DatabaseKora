package dev.koradb.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line entry point to launch a standalone KoraDB server.
 */
public final class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);
    public static final String VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        String host = KoraServerConfig.DEFAULT_HOST;
        int port = KoraServerConfig.DEFAULT_PORT;
        Path dataDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--host" -> {
                    if (++i < args.length) host = args[i];
                }
                case "-p", "--port" -> {
                    if (++i < args.length) port = Integer.parseInt(args[i]);
                }
                case "-d", "--data-dir" -> {
                    if (++i < args.length) dataDir = Paths.get(args[i]);
                }
                case "--help" -> {
                    printHelp();
                    return;
                }
                case "--version", "-v" -> {
                    System.out.println("KoraDB Server version " + VERSION);
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printHelp();
                    System.exit(1);
                }
            }
        }

        KoraServerConfig config = new KoraServerConfig(host, port, KoraServerConfig.DEFAULT_BACKLOG, dataDir);
        KoraServer server = new KoraServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, stopping server...");
            server.stop();
        }, "koradb-shutdown"));

        try {
            server.start();
            System.out.printf("KoraDB Server v%s listening on %s:%d%n", VERSION, host, server.getPort());
            System.out.println("Press Ctrl+C to terminate");

            // Keep main thread alive
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Fatal server error", e);
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("""
                Usage: koradb-server [OPTIONS]
                
                Options:
                  -h, --host <HOST>      Network host/interface to bind (default: 127.0.0.1)
                  -p, --port <PORT>      TCP port to listen on (default: 8765)
                  -d, --data-dir <PATH>  Persistent database directory path (default: in-memory)
                  -v, --version          Print version information and exit
                  --help                 Show this help message and exit
                """);
    }
}
