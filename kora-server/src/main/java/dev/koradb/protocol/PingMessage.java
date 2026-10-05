package dev.koradb.protocol;

/**
 * Heartbeat ping message.
 */
public record PingMessage() implements Message {
    @Override
    public MessageType getType() {
        return MessageType.PING;
    }
}
