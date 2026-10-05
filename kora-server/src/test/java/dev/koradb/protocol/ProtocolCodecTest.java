package dev.koradb.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolCodecTest {

    @Test
    @DisplayName("Should encode and decode ConnectMessage and ConnectOkMessage")
    void testConnectMessages() throws IOException {
        ConnectMessage connect = new ConnectMessage(1, "TestClient", "finance");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtocolCodec.writeMessage(out, connect);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Message decoded = ProtocolCodec.readMessage(in);

        assertThat(decoded).isInstanceOf(ConnectMessage.class);
        ConnectMessage cm = (ConnectMessage) decoded;
        assertThat(cm.protocolVersion()).isEqualTo(1);
        assertThat(cm.clientName()).isEqualTo("TestClient");
        assertThat(cm.database()).isEqualTo("finance");

        ConnectOkMessage connectOk = new ConnectOkMessage("KoraDB/0.1.0", 42L, "Ready");
        out.reset();
        ProtocolCodec.writeMessage(out, connectOk);

        in = new ByteArrayInputStream(out.toByteArray());
        decoded = ProtocolCodec.readMessage(in);
        assertThat(decoded).isInstanceOf(ConnectOkMessage.class);
        ConnectOkMessage cok = (ConnectOkMessage) decoded;
        assertThat(cok.serverVersion()).isEqualTo("KoraDB/0.1.0");
        assertThat(cok.connectionId()).isEqualTo(42L);
        assertThat(cok.message()).isEqualTo("Ready");
    }

    @Test
    @DisplayName("Should encode and decode QueryMessage")
    void testQueryMessage() throws IOException {
        QueryMessage query = new QueryMessage("SELECT id, name FROM accounts WHERE balance > 1000.0;");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtocolCodec.writeMessage(out, query);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Message decoded = ProtocolCodec.readMessage(in);

        assertThat(decoded).isInstanceOf(QueryMessage.class);
        QueryMessage qm = (QueryMessage) decoded;
        assertThat(qm.sql()).isEqualTo("SELECT id, name FROM accounts WHERE balance > 1000.0;");
    }

    @Test
    @DisplayName("Should encode and decode QueryResultMessage with diverse data types and nulls")
    void testQueryResultWithDiverseData() throws IOException {
        List<String> cols = List.of("id", "name", "balance", "active", "created_at", "notes");
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 12, 30, 0);

        List<List<Object>> rows = List.of(
                Arrays.asList(1, "Alice", 5000.5, true, now, null),
                Arrays.asList(2, "Bob", 3000.0, false, null, "VIP Customer")
        );

        QueryResultMessage qrm = QueryResultMessage.rows(cols, rows);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtocolCodec.writeMessage(out, qrm);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Message decoded = ProtocolCodec.readMessage(in);

        assertThat(decoded).isInstanceOf(QueryResultMessage.class);
        QueryResultMessage res = (QueryResultMessage) decoded;
        assertThat(res.isQuery()).isTrue();
        assertThat(res.columns()).containsExactly("id", "name", "balance", "active", "created_at", "notes");
        assertThat(res.rows()).hasSize(2);

        List<Object> row1 = res.rows().get(0);
        assertThat(row1.get(0)).isEqualTo(1);
        assertThat(row1.get(1)).isEqualTo("Alice");
        assertThat(row1.get(2)).isEqualTo(5000.5);
        assertThat(row1.get(3)).isEqualTo(true);
        assertThat(row1.get(4)).isEqualTo(now);
        assertThat(row1.get(5)).isNull();

        List<Object> row2 = res.rows().get(1);
        assertThat(row2.get(0)).isEqualTo(2);
        assertThat(row2.get(1)).isEqualTo("Bob");
        assertThat(row2.get(2)).isEqualTo(3000.0);
        assertThat(row2.get(3)).isEqualTo(false);
        assertThat(row2.get(4)).isNull();
        assertThat(row2.get(5)).isEqualTo("VIP Customer");
    }

    @Test
    @DisplayName("Should encode and decode DDL/DML QueryResultMessage")
    void testDdlDmlResults() throws IOException {
        QueryResultMessage dml = QueryResultMessage.dml(3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtocolCodec.writeMessage(out, dml);

        Message decoded = ProtocolCodec.readMessage(new ByteArrayInputStream(out.toByteArray()));
        assertThat(decoded).isInstanceOf(QueryResultMessage.class);
        QueryResultMessage dmlDecoded = (QueryResultMessage) decoded;
        assertThat(dmlDecoded.isQuery()).isFalse();
        assertThat(dmlDecoded.affectedRows()).isEqualTo(3);

        QueryResultMessage ddl = QueryResultMessage.ddl();
        out.reset();
        ProtocolCodec.writeMessage(out, ddl);
        decoded = ProtocolCodec.readMessage(new ByteArrayInputStream(out.toByteArray()));
        QueryResultMessage ddlDecoded = (QueryResultMessage) decoded;
        assertThat(ddlDecoded.isQuery()).isFalse();
        assertThat(ddlDecoded.affectedRows()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should encode and decode ErrorMessage, Ping, Pong, Disconnect")
    void testControlMessages() throws IOException {
        ErrorMessage err = new ErrorMessage(ErrorMessage.ERR_PARSE, "Syntax error at or near 'FORM'");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtocolCodec.writeMessage(out, err);

        Message decoded = ProtocolCodec.readMessage(new ByteArrayInputStream(out.toByteArray()));
        assertThat(decoded).isInstanceOf(ErrorMessage.class);
        ErrorMessage errDecoded = (ErrorMessage) decoded;
        assertThat(errDecoded.errorCode()).isEqualTo(ErrorMessage.ERR_PARSE);
        assertThat(errDecoded.message()).isEqualTo("Syntax error at or near 'FORM'");

        // Ping
        out.reset();
        ProtocolCodec.writeMessage(out, new PingMessage());
        decoded = ProtocolCodec.readMessage(new ByteArrayInputStream(out.toByteArray()));
        assertThat(decoded).isInstanceOf(PingMessage.class);

        // Pong
        out.reset();
        ProtocolCodec.writeMessage(out, new PongMessage());
        decoded = ProtocolCodec.readMessage(new ByteArrayInputStream(out.toByteArray()));
        assertThat(decoded).isInstanceOf(PongMessage.class);

        // Disconnect
        out.reset();
        ProtocolCodec.writeMessage(out, new DisconnectMessage());
        decoded = ProtocolCodec.readMessage(new ByteArrayInputStream(out.toByteArray()));
        assertThat(decoded).isInstanceOf(DisconnectMessage.class);
    }

    @Test
    @DisplayName("Should throw ProtocolException on invalid magic byte")
    void testInvalidMagic() {
        byte[] corrupted = new byte[] { (byte) 0xFF, 0x01, 0x00, 0x00, 0x00, 0x00 };
        assertThatThrownBy(() -> ProtocolCodec.readMessage(new ByteArrayInputStream(corrupted)))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Invalid protocol magic byte");
    }

    @Test
    @DisplayName("Should return null on clean EOF")
    void testCleanEof() throws IOException {
        ByteArrayInputStream empty = new ByteArrayInputStream(new byte[0]);
        Message msg = ProtocolCodec.readMessage(empty);
        assertThat(msg).isNull();
    }
}
