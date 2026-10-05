package dev.koradb.protocol;

/**
 * Message types for the KoraDB binary wire protocol.
 */
public enum MessageType {
    CONNECT((byte) 0x01),
    CONNECT_OK((byte) 0x02),
    QUERY((byte) 0x03),
    QUERY_RESULT((byte) 0x04),
    ERROR((byte) 0x05),
    PING((byte) 0x06),
    PONG((byte) 0x07),
    DISCONNECT((byte) 0x08);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static MessageType fromCode(byte code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown protocol message type code: 0x" + Integer.toHexString(code & 0xFF));
    }
}
