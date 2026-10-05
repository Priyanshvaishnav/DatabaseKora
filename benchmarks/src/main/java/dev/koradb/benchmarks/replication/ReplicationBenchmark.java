package dev.koradb.benchmarks.replication;

import dev.koradb.replication.protocol.*;
import dev.koradb.storage.wal.CommitLogRecord;
import dev.koradb.storage.wal.LogRecord;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH Microbenchmark measuring replication protocol serialization and batch WAL message encoding/decoding.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class ReplicationBenchmark {

    private byte[] encodedHeartbeatBytes;
    private byte[] encodedWalAckBytes;
    private byte[] encodedWalDataBytes;

    private HeartbeatMessage heartbeat;
    private WalAckMessage walAck;
    private WalDataMessage walData;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        heartbeat = new HeartbeatMessage(123456789L, 50000L);
        ByteArrayOutputStream baosHb = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(heartbeat, baosHb);
        encodedHeartbeatBytes = baosHb.toByteArray();

        walAck = new WalAckMessage("replica-1", 45000L, 44000L, System.currentTimeMillis());
        ByteArrayOutputStream baosAck = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(walAck, baosAck);
        encodedWalAckBytes = baosAck.toByteArray();

        List<LogRecord> batch = new ArrayList<>(20);
        for (int i = 1; i <= 20; i++) {
            batch.add(new CommitLogRecord(i * 100L, (i - 1) * 100L, 1000L + i));
        }
        walData = new WalDataMessage(100L, 2000L, batch, System.currentTimeMillis());
        ByteArrayOutputStream baosData = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(walData, baosData);
        encodedWalDataBytes = baosData.toByteArray();
    }

    @Benchmark
    public byte[] encodeHeartbeat() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
        ReplicationCodec.writeMessage(heartbeat, baos);
        return baos.toByteArray();
    }

    @Benchmark
    public ReplicationMessage decodeHeartbeat() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedHeartbeatBytes);
        return ReplicationCodec.readMessage(bais);
    }

    @Benchmark
    public byte[] encodeWalAck() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(48);
        ReplicationCodec.writeMessage(walAck, baos);
        return baos.toByteArray();
    }

    @Benchmark
    public ReplicationMessage decodeWalAck() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedWalAckBytes);
        return ReplicationCodec.readMessage(bais);
    }

    @Benchmark
    public byte[] encodeWalDataBatch() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        ReplicationCodec.writeMessage(walData, baos);
        return baos.toByteArray();
    }

    @Benchmark
    public ReplicationMessage decodeWalDataBatch() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedWalDataBytes);
        return ReplicationCodec.readMessage(bais);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // No persistent resources to release
    }
}
