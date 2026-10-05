package dev.koradb.protocol;

/**
 * Server connection response acknowledging successful handshake.
 *
 * @param serverVersion server version string (e.g. "KoraDB/0.1.0-SNAPSHOT")
 * @param connectionId  unique session connection identifier
 * @param message       welcome greeting or status
 */
public record ConnectOkMessage(
        String serverVersion,
        long connectionId,
        String message
) implements Message {

    public ConnectOkMessage {
        if (serverVersion == null) serverVersion = "KoraDB/0.1.0";
        if (message == null) message = "Connected to KoraDB";
    }

    @Override
    public MessageType getType() {
        return MessageType.CONNECT_OK;
    }
}
