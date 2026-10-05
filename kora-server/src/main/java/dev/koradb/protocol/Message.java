package dev.koradb.protocol;

/**
 * Base sealed interface for all KoraDB wire protocol messages.
 */
public sealed interface Message permits
        ConnectMessage,
        ConnectOkMessage,
        QueryMessage,
        QueryResultMessage,
        ErrorMessage,
        PingMessage,
        PongMessage,
        DisconnectMessage {

    MessageType getType();
}
