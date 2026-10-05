package dev.koradb.protocol;

/**
 * Message sent by client or server to signal clean graceful disconnection.
 */
public record DisconnectMessage() implements Message {
    @Override
    public MessageType getType() {
        return MessageType.DISCONNECT;
    }
}
