package dev.koradb.benchmarks.network;

import dev.koradb.client.ClientQueryResult;
import dev.koradb.client.KoraClient;
import dev.koradb.protocol.*;
import dev.koradb.server.KoraServer;
import dev.koradb.server.KoraServerConfig;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH Microbenchmark measuring binary wire protocol serialization/deserialization
 * and TCP network client-server round-trip throughput.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class ServerNetworkBenchmark {

    private byte[] encodedQueryMessageBytes;
    private byte[] encodedQueryResultBytes;

    private KoraServer server;
    private KoraClient client;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        // 1. Prepare sample serialized wire protocol messages
        QueryMessage qm = new QueryMessage("SELECT id, name, price FROM items WHERE price > 100.0");
        ByteArrayOutputStream baosQuery = new ByteArrayOutputStream();
        ProtocolCodec.writeMessage(baosQuery, qm);
        encodedQueryMessageBytes = baosQuery.toByteArray();

        List<String> cols = List.of("id", "name", "price", "active");
        List<List<Object>> rows = new ArrayList<>(50);
        for (int i = 1; i <= 50; i++) {
            rows.add(List.of(i, "Item-" + i, 99.95 + i, true));
        }
        QueryResultMessage qrm = QueryResultMessage.rows(cols, rows);
        ByteArrayOutputStream baosResult = new ByteArrayOutputStream();
        ProtocolCodec.writeMessage(baosResult, qrm);
        encodedQueryResultBytes = baosResult.toByteArray();

        // 2. Setup live TCP server and client for network round-trip
        KoraServerConfig config = KoraServerConfig.inMemory(0);
        server = new KoraServer(config);
        server.start();

        client = KoraClient.connect("127.0.0.1", server.getPort());
        client.execute("CREATE DATABASE netbench");
        client.execute("USE netbench");
        client.execute("CREATE TABLE items (id INT, name VARCHAR, price DOUBLE)");
        for (int i = 1; i <= 20; i++) {
            client.execute(String.format("INSERT INTO items VALUES (%d, 'Item-%d', %.2f)", i, i, i * 10.0));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {}
        }
    }

    @Benchmark
    public byte[] encodeQueryMessage() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        ProtocolCodec.writeMessage(baos, new QueryMessage("SELECT id, name, price FROM items WHERE price > 100.0"));
        return baos.toByteArray();
    }

    @Benchmark
    public Message decodeQueryMessage() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedQueryMessageBytes);
        return ProtocolCodec.readMessage(bais);
    }

    @Benchmark
    public Message decodeQueryResult() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedQueryResultBytes);
        return ProtocolCodec.readMessage(bais);
    }

    @Benchmark
    public boolean clientPingRoundTrip() throws IOException {
        return client.ping();
    }

    @Benchmark
    public ClientQueryResult clientQueryRoundTrip() throws IOException {
        return client.execute("SELECT * FROM items WHERE id = 5");
    }
}
