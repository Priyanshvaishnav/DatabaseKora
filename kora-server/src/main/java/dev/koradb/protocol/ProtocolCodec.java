package dev.koradb.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes KoraDB binary wire protocol messages.
 *
 * <p>Framing format (6-byte header + variable payload):
 * <pre>
 *   [Magic: 1B (0x4B)][Type: 1B][Length: 4B (int32)][Payload: Length bytes]
 * </pre>
 */
public final class ProtocolCodec {

    public static final byte MAGIC = (byte) 0x4B; // 'K' in ASCII
    public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024; // 16 MB maximum message size

    // Value tag codes for serialized row data
    public static final byte TAG_NULL = 0;
    public static final byte TAG_INT = 1;
    public static final byte TAG_BIGINT = 2;
    public static final byte TAG_BOOLEAN = 3;
    public static final byte TAG_VARCHAR = 4;
    public static final byte TAG_DOUBLE = 5;
    public static final byte TAG_TIMESTAMP = 6;

    private ProtocolCodec() {}

    /**
     * Serializes and writes a protocol message to the output stream.
     *
     * @param out     the target output stream
     * @param message the message to send
     * @throws IOException if network or serialization error occurs
     */
    public static void writeMessage(OutputStream out, Message message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        switch (message) {
            case ConnectMessage m -> {
                dos.writeInt(m.protocolVersion());
                writeString(dos, m.clientName());
                writeString(dos, m.database());
            }
            case ConnectOkMessage m -> {
                writeString(dos, m.serverVersion());
                dos.writeLong(m.connectionId());
                writeString(dos, m.message());
            }
            case QueryMessage m -> {
                writeString(dos, m.sql());
            }
            case QueryResultMessage m -> {
                dos.writeBoolean(m.isQuery());
                dos.writeInt(m.affectedRows());
                writeString(dos, m.message());

                List<String> cols = m.columns();
                dos.writeInt(cols.size());
                for (String col : cols) {
                    writeString(dos, col);
                }

                List<List<Object>> rows = m.rows();
                dos.writeInt(rows.size());
                for (List<Object> row : rows) {
                    for (int i = 0; i < cols.size(); i++) {
                        Object val = i < row.size() ? row.get(i) : null;
                        writeValue(dos, val);
                    }
                }
            }
            case ErrorMessage m -> {
                dos.writeInt(m.errorCode());
                writeString(dos, m.message());
            }
            case PingMessage ignored -> {}
            case PongMessage ignored -> {}
            case DisconnectMessage ignored -> {}
        }

        dos.flush();
        byte[] payload = baos.toByteArray();

        DataOutputStream headerOut = new DataOutputStream(out);
        headerOut.writeByte(MAGIC);
        headerOut.writeByte(message.getType().getCode());
        headerOut.writeInt(payload.length);
        if (payload.length > 0) {
            headerOut.write(payload);
        }
        headerOut.flush();
    }

    /**
     * Reads and decodes a protocol message from the input stream.
     *
     * @param in the input stream
     * @return the decoded {@link Message}, or {@code null} if EOF was reached before any byte
     * @throws IOException       on network I/O failure
     * @throws ProtocolException on bad magic byte or malformed payload
     */
    public static Message readMessage(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);

        int magicByte;
        try {
            magicByte = in.read();
            if (magicByte == -1) {
                return null; // Clean EOF
            }
        } catch (EOFException e) {
            return null;
        }

        if ((byte) magicByte != MAGIC) {
            throw new ProtocolException("Invalid protocol magic byte: 0x" + Integer.toHexString(magicByte & 0xFF));
        }

        byte typeCode = din.readByte();
        int payloadLength = din.readInt();

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE) {
            throw new ProtocolException("Payload length out of bounds: " + payloadLength);
        }

        byte[] payload = in.readNBytes(payloadLength);
        if (payload.length < payloadLength) {
            throw new ProtocolException("Unexpected EOF: expected " + payloadLength + " payload bytes, got " + payload.length);
        }

        DataInputStream pin = new DataInputStream(new ByteArrayInputStream(payload));
        MessageType type = MessageType.fromCode(typeCode);

        return switch (type) {
            case CONNECT -> {
                int version = pin.readInt();
                String clientName = readString(pin);
                String database = readString(pin);
                yield new ConnectMessage(version, clientName, database);
            }
            case CONNECT_OK -> {
                String serverVersion = readString(pin);
                long connectionId = pin.readLong();
                String message = readString(pin);
                yield new ConnectOkMessage(serverVersion, connectionId, message);
            }
            case QUERY -> {
                String sql = readString(pin);
                yield new QueryMessage(sql);
            }
            case QUERY_RESULT -> {
                boolean isQuery = pin.readBoolean();
                int affectedRows = pin.readInt();
                String message = readString(pin);

                int colCount = pin.readInt();
                List<String> cols = new ArrayList<>(colCount);
                for (int i = 0; i < colCount; i++) {
                    cols.add(readString(pin));
                }

                int rowCount = pin.readInt();
                List<List<Object>> rows = new ArrayList<>(rowCount);
                for (int r = 0; r < rowCount; r++) {
                    List<Object> row = new ArrayList<>(colCount);
                    for (int c = 0; c < colCount; c++) {
                        row.add(readValue(pin));
                    }
                    rows.add(row);
                }
                yield new QueryResultMessage(isQuery, affectedRows, message, cols, rows);
            }
            case ERROR -> {
                int errorCode = pin.readInt();
                String message = readString(pin);
                yield new ErrorMessage(errorCode, message);
            }
            case PING -> new PingMessage();
            case PONG -> new PongMessage();
            case DISCONNECT -> new DisconnectMessage();
        };
    }

    private static void writeString(DataOutputStream dos, String str) throws IOException {
        if (str == null) {
            dos.writeInt(-1);
            return;
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private static String readString(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length == -1) {
            return null;
        }
        byte[] bytes = dis.readNBytes(length);
        if (bytes.length < length) {
            throw new ProtocolException("Premature EOF while reading string: expected " + length + ", got " + bytes.length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeValue(DataOutputStream dos, Object val) throws IOException {
        if (val == null) {
            dos.writeByte(TAG_NULL);
        } else if (val instanceof Integer i) {
            dos.writeByte(TAG_INT);
            dos.writeInt(i);
        } else if (val instanceof Long l) {
            dos.writeByte(TAG_BIGINT);
            dos.writeLong(l);
        } else if (val instanceof Boolean b) {
            dos.writeByte(TAG_BOOLEAN);
            dos.writeBoolean(b);
        } else if (val instanceof Double d) {
            dos.writeByte(TAG_DOUBLE);
            dos.writeDouble(d);
        } else if (val instanceof Float f) {
            dos.writeByte(TAG_DOUBLE);
            dos.writeDouble(f.doubleValue());
        } else if (val instanceof LocalDateTime dt) {
            dos.writeByte(TAG_TIMESTAMP);
            writeString(dos, dt.toString());
        } else if (val instanceof String s) {
            dos.writeByte(TAG_VARCHAR);
            writeString(dos, s);
        } else {
            dos.writeByte(TAG_VARCHAR);
            writeString(dos, String.valueOf(val));
        }
    }

    private static Object readValue(DataInputStream dis) throws IOException {
        byte tag = dis.readByte();
        return switch (tag) {
            case TAG_NULL -> null;
            case TAG_INT -> dis.readInt();
            case TAG_BIGINT -> dis.readLong();
            case TAG_BOOLEAN -> dis.readBoolean();
            case TAG_VARCHAR -> readString(dis);
            case TAG_DOUBLE -> dis.readDouble();
            case TAG_TIMESTAMP -> {
                String s = readString(dis);
                yield s != null ? LocalDateTime.parse(s) : null;
            }
            default -> throw new ProtocolException("Unknown value tag: " + tag);
        };
    }
}
