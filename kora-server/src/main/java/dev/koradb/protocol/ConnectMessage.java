package dev.koradb.protocol;

/**
 * Client connection initiation message.
 *
 * @param protocolVersion wire protocol version (e.g. 1)
 * @param clientName      client identification string (e.g. "KoraCLI/0.1.0")
 * @param database        default database to connect to (empty string if none)
 */
public record ConnectMessage(
        int protocolVersion,
        String clientName,
        String database
) implements Message {

    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public ConnectMessage {
        if (clientName == null) clientName = "KoraClient";
        if (database == null) database = "";
    }

    @Override
    public MessageType getType() {
        return MessageType.CONNECT;
    }
}
