package dev.koradb.replication.protocol;

import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.storage.wal.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicationCodecTest {

    @Test
    @DisplayName("Round-trip serialization of HandshakeRequest and HandshakeResponse")
    void testHandshakeRoundTrip() throws IOException {
        HandshakeRequest req = new HandshakeRequest("replica-1", 1024L, ReplicationMode.SYNC);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(req, baos);

        ReplicationMessage decoded = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decoded).isInstanceOf(HandshakeRequest.class);
        HandshakeRequest decodedReq = (HandshakeRequest) decoded;
        assertThat(decodedReq.replicaId()).isEqualTo("replica-1");
        assertThat(decodedReq.lastFlushedLsn()).isEqualTo(1024L);
        assertThat(decodedReq.mode()).isEqualTo(ReplicationMode.SYNC);

        HandshakeResponse resp = new HandshakeResponse("primary-node", 2048L, 1, true, "Welcome");
        baos.reset();
        ReplicationCodec.writeMessage(resp, baos);

        ReplicationMessage decodedRespMsg = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decodedRespMsg).isInstanceOf(HandshakeResponse.class);
        HandshakeResponse decodedResp = (HandshakeResponse) decodedRespMsg;
        assertThat(decodedResp.primaryId()).isEqualTo("primary-node");
        assertThat(decodedResp.currentLsn()).isEqualTo(2048L);
        assertThat(decodedResp.walVersion()).isEqualTo(1);
        assertThat(decodedResp.accepted()).isTrue();
        assertThat(decodedResp.message()).isEqualTo("Welcome");
    }

    @Test
    @DisplayName("Round-trip serialization of WalDataMessage with multiple log records")
    void testWalDataRoundTrip() throws IOException {
        LogRecord begin = new BeginLogRecord(100L, 0L, 1L);
        LogRecord ddl = new DdlLogRecord(137L, 100L, 1L, "CREATE TABLE accounts (id INT)");
        LogRecord update = new UpdateLogRecord(200L, 137L, 1L, "accounts", 0, 1,
                new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        LogRecord commit = new CommitLogRecord(300L, 200L, 1L);

        WalDataMessage dataMsg = new WalDataMessage(100L, 337L, List.of(begin, ddl, update, commit), System.currentTimeMillis());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(dataMsg, baos);

        ReplicationMessage decoded = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decoded).isInstanceOf(WalDataMessage.class);
        WalDataMessage decodedData = (WalDataMessage) decoded;
        assertThat(decodedData.startLsn()).isEqualTo(100L);
        assertThat(decodedData.endLsn()).isEqualTo(337L);
        assertThat(decodedData.records()).hasSize(4);

        assertThat(decodedData.records().get(0)).isInstanceOf(BeginLogRecord.class);
        assertThat(decodedData.records().get(1)).isInstanceOf(DdlLogRecord.class);
        assertThat(((DdlLogRecord) decodedData.records().get(1)).getSql()).isEqualTo("CREATE TABLE accounts (id INT)");
        assertThat(decodedData.records().get(2)).isInstanceOf(UpdateLogRecord.class);
        assertThat(decodedData.records().get(3)).isInstanceOf(CommitLogRecord.class);
    }

    @Test
    @DisplayName("Round-trip serialization of WalAckMessage")
    void testWalAckRoundTrip() throws IOException {
        WalAckMessage ack = new WalAckMessage("replica-42", 5000L, 4800L, 123456789L);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(ack, baos);

        ReplicationMessage decoded = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decoded).isInstanceOf(WalAckMessage.class);
        WalAckMessage decodedAck = (WalAckMessage) decoded;
        assertThat(decodedAck.replicaId()).isEqualTo("replica-42");
        assertThat(decodedAck.flushedLsn()).isEqualTo(5000L);
        assertThat(decodedAck.appliedLsn()).isEqualTo(4800L);
        assertThat(decodedAck.timestamp()).isEqualTo(123456789L);
    }

    @Test
    @DisplayName("Round-trip serialization of Heartbeat and HeartbeatAck")
    void testHeartbeatRoundTrip() throws IOException {
        HeartbeatMessage hb = new HeartbeatMessage(9999L, 11111L);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(hb, baos);

        ReplicationMessage decodedHb = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decodedHb).isInstanceOf(HeartbeatMessage.class);
        assertThat(((HeartbeatMessage) decodedHb).primaryLsn()).isEqualTo(9999L);

        HeartbeatAckMessage hback = new HeartbeatAckMessage("rep-1", 9990L, 22222L);
        baos.reset();
        ReplicationCodec.writeMessage(hback, baos);

        ReplicationMessage decodedAck = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decodedAck).isInstanceOf(HeartbeatAckMessage.class);
        assertThat(((HeartbeatAckMessage) decodedAck).replicaId()).isEqualTo("rep-1");
        assertThat(((HeartbeatAckMessage) decodedAck).lastReceivedLsn()).isEqualTo(9990L);
    }

    @Test
    @DisplayName("Round-trip serialization of SnapshotRequest and SnapshotResponse")
    void testSnapshotRoundTrip() throws IOException {
        SnapshotRequest req = new SnapshotRequest("replica-1", "finance");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(req, baos);

        ReplicationMessage decodedReq = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decodedReq).isInstanceOf(SnapshotRequest.class);
        assertThat(((SnapshotRequest) decodedReq).databaseName()).isEqualTo("finance");

        SnapshotResponse resp = new SnapshotResponse(
                "finance",
                1234L,
                Map.of(
                        "catalog.meta", "TABLE accounts id:INT".getBytes(StandardCharsets.UTF_8),
                        "accounts.kdb", new byte[]{0x01, 0x02, 0x03, 0x04}
                )
        );
        baos.reset();
        ReplicationCodec.writeMessage(resp, baos);

        ReplicationMessage decodedResp = ReplicationCodec.readMessage(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(decodedResp).isInstanceOf(SnapshotResponse.class);
        SnapshotResponse decodedSnapshot = (SnapshotResponse) decodedResp;
        assertThat(decodedSnapshot.databaseName()).isEqualTo("finance");
        assertThat(decodedSnapshot.snapshotLsn()).isEqualTo(1234L);
        assertThat(decodedSnapshot.files()).hasSize(2);
        assertThat(new String(decodedSnapshot.files().get("catalog.meta"), StandardCharsets.UTF_8))
                .isEqualTo("TABLE accounts id:INT");
        assertThat(decodedSnapshot.files().get("accounts.kdb")).containsExactly(0x01, 0x02, 0x03, 0x04);
    }

    @Test
    @DisplayName("Corruption detected by CRC32 checksum mismatch")
    void testCorruptedChecksumRejected() throws IOException {
        WalAckMessage ack = new WalAckMessage("replica-1", 100L, 100L, 10L);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplicationCodec.writeMessage(ack, baos);

        byte[] raw = baos.toByteArray();
        // Corrupt a payload byte
        raw[8] ^= 0xFF;

        assertThatThrownBy(() -> ReplicationCodec.readMessage(new ByteArrayInputStream(raw)))
                .isInstanceOf(ReplicationProtocolException.class)
                .hasMessageContaining("CRC32 checksum mismatch");
    }

    @Test
    @DisplayName("Invalid magic byte is rejected")
    void testInvalidMagicByteRejected() {
        byte[] invalid = new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x01, 0x02, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> ReplicationCodec.readMessage(new ByteArrayInputStream(invalid)))
                .isInstanceOf(ReplicationProtocolException.class)
                .hasMessageContaining("Invalid replication magic byte");
    }

    @Test
    @DisplayName("Clean EOF returns null")
    void testCleanEof() throws IOException {
        assertThat(ReplicationCodec.readMessage(new ByteArrayInputStream(new byte[0]))).isNull();
    }
}
