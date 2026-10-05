package dev.koradb.protocol;

/**
 * Heartbeat pong response message.
 */
public record PongMessage() implements Message {
    @Override
    public MessageType getType() {
        return MessageType.PONG;
    }
}
