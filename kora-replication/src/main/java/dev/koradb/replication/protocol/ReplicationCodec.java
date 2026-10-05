package dev.koradb.replication.protocol;

import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.storage.wal.LogRecord;
import dev.koradb.storage.wal.LogRecordCodec;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Binary serializer and deserializer for KoraDB replication wire protocol messages.
 *
 * <p>Framing format:
 * <pre>
 *   [Magic: 1B (0x52 'R')] [Type: 1B] [Length: 4B] [Payload: NB] [CRC32: 4B]
 * </pre>
 */
public final class ReplicationCodec {

    public static final byte MAGIC_BYTE = 0x52; // 'R' for Replication
    public static final int MAX_FRAME_SIZE = 32 * 1024 * 1024; // 32 MB guardrail

    private ReplicationCodec() {}

    /**
     * Serializes a replication message and writes it to the output stream.
     */
    public static void writeMessage(ReplicationMessage msg, OutputStream out) throws IOException {
        Objects.requireNonNull(msg, "msg must not be null");
        Objects.requireNonNull(out, "out must not be null");

        byte[] payload = encodePayload(msg);

        CRC32 crc = new CRC32();
        crc.update(payload);
        int checksum = (int) crc.getValue();

        DataOutputStream dos = new DataOutputStream(out);
        dos.writeByte(MAGIC_BYTE);
        dos.writeByte(msg.getType().getCode());
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.writeInt(checksum);
        dos.flush();
    }

    /**
     * Reads a replication message from the input stream, or returns null if stream reached EOF cleanly.
     */
    public static ReplicationMessage readMessage(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");

        int firstByte = in.read();
        if (firstByte == -1) {
            return null; // clean EOF
        }

        if ((byte) firstByte != MAGIC_BYTE) {
            throw new ReplicationProtocolException(
                    "Invalid replication magic byte: 0x%02X (expected 0x%02X)".formatted(firstByte, MAGIC_BYTE));
        }

        DataInputStream dis = new DataInputStream(in);
        byte typeCode = dis.readByte();
        ReplicationMessageType type = ReplicationMessageType.fromCode(typeCode);

        int length = dis.readInt();
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new ReplicationProtocolException(
                    "Invalid replication payload length: %d (max: %d)".formatted(length, MAX_FRAME_SIZE));
        }

        byte[] payload = new byte[length];
        dis.readFully(payload);

        int expectedChecksum = dis.readInt();
        CRC32 crc = new CRC32();
        crc.update(payload);
        int actualChecksum = (int) crc.getValue();

        if (expectedChecksum != actualChecksum) {
            throw new ReplicationProtocolException(
                    "Replication frame CRC32 checksum mismatch: expected 0x%08X, got 0x%08X"
                            .formatted(expectedChecksum, actualChecksum));
        }

        return decodePayload(type, payload);
    }

    private static byte[] encodePayload(ReplicationMessage msg) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        switch (msg) {
            case HandshakeRequest req -> {
                dos.writeUTF(req.replicaId());
                dos.writeLong(req.lastFlushedLsn());
                dos.writeByte(req.mode().getCode());
            }
            case HandshakeResponse resp -> {
                dos.writeUTF(resp.primaryId());
                dos.writeLong(resp.currentLsn());
                dos.writeInt(resp.walVersion());
                dos.writeBoolean(resp.accepted());
                dos.writeUTF(resp.message());
            }
            case WalDataMessage data -> {
                dos.writeLong(data.startLsn());
                dos.writeLong(data.endLsn());
                dos.writeLong(data.timestamp());
                dos.writeInt(data.records().size());
                for (LogRecord r : data.records()) {
                    int len = LogRecordCodec.calculateEncodedLength(r);
                    ByteBuffer buf = ByteBuffer.allocate(len);
                    LogRecordCodec.encode(r, buf);
                    dos.writeInt(len);
                    dos.write(buf.array(), 0, len);
                }
            }
            case WalAckMessage ack -> {
                dos.writeUTF(ack.replicaId());
                dos.writeLong(ack.flushedLsn());
                dos.writeLong(ack.appliedLsn());
                dos.writeLong(ack.timestamp());
            }
            case HeartbeatMessage hb -> {
                dos.writeLong(hb.primaryLsn());
                dos.writeLong(hb.timestamp());
            }
            case HeartbeatAckMessage hback -> {
                dos.writeUTF(hback.replicaId());
                dos.writeLong(hback.lastReceivedLsn());
                dos.writeLong(hback.timestamp());
            }
            case SnapshotRequest sreq -> {
                dos.writeUTF(sreq.replicaId());
                dos.writeUTF(sreq.databaseName());
            }
            case SnapshotResponse sresp -> {
                dos.writeUTF(sresp.databaseName());
                dos.writeLong(sresp.snapshotLsn());
                dos.writeInt(sresp.files().size());
                for (Map.Entry<String, byte[]> entry : sresp.files().entrySet()) {
                    dos.writeUTF(entry.getKey());
                    dos.writeInt(entry.getValue().length);
                    dos.write(entry.getValue());
                }
            }
        }

        dos.flush();
        return baos.toByteArray();
    }

    private static ReplicationMessage decodePayload(ReplicationMessageType type, byte[] payload) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        DataInputStream dis = new DataInputStream(bais);

        return switch (type) {
            case HANDSHAKE_REQ -> {
                String replicaId = dis.readUTF();
                long lastFlushedLsn = dis.readLong();
                ReplicationMode mode = ReplicationMode.fromCode(dis.readByte());
                yield new HandshakeRequest(replicaId, lastFlushedLsn, mode);
            }
            case HANDSHAKE_RESP -> {
                String primaryId = dis.readUTF();
                long currentLsn = dis.readLong();
                int walVersion = dis.readInt();
                boolean accepted = dis.readBoolean();
                String message = dis.readUTF();
                yield new HandshakeResponse(primaryId, currentLsn, walVersion, accepted, message);
            }
            case WAL_DATA -> {
                long startLsn = dis.readLong();
                long endLsn = dis.readLong();
                long timestamp = dis.readLong();
                int recordCount = dis.readInt();
                List<LogRecord> records = new ArrayList<>(recordCount);
                for (int i = 0; i < recordCount; i++) {
                    int len = dis.readInt();
                    byte[] rBytes = new byte[len];
                    dis.readFully(rBytes);
                    ByteBuffer buf = ByteBuffer.wrap(rBytes);
                    records.add(LogRecordCodec.decode(buf));
                }
                yield new WalDataMessage(startLsn, endLsn, records, timestamp);
            }
            case WAL_ACK -> {
                String replicaId = dis.readUTF();
                long flushedLsn = dis.readLong();
                long appliedLsn = dis.readLong();
                long timestamp = dis.readLong();
                yield new WalAckMessage(replicaId, flushedLsn, appliedLsn, timestamp);
            }
            case HEARTBEAT -> {
                long primaryLsn = dis.readLong();
                long timestamp = dis.readLong();
                yield new HeartbeatMessage(primaryLsn, timestamp);
            }
            case HEARTBEAT_ACK -> {
                String replicaId = dis.readUTF();
                long lastReceivedLsn = dis.readLong();
                long timestamp = dis.readLong();
                yield new HeartbeatAckMessage(replicaId, lastReceivedLsn, timestamp);
            }
            case SNAPSHOT_REQ -> {
                String replicaId = dis.readUTF();
                String databaseName = dis.readUTF();
                yield new SnapshotRequest(replicaId, databaseName);
            }
            case SNAPSHOT_RESP -> {
                String databaseName = dis.readUTF();
                long snapshotLsn = dis.readLong();
                int fileCount = dis.readInt();
                Map<String, byte[]> files = new HashMap<>();
                for (int i = 0; i < fileCount; i++) {
                    String fileName = dis.readUTF();
                    int fileLen = dis.readInt();
                    byte[] fileData = new byte[fileLen];
                    dis.readFully(fileData);
                    files.put(fileName, fileData);
                }
                yield new SnapshotResponse(databaseName, snapshotLsn, files);
            }
        };
    }
}
