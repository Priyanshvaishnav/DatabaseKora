package dev.koradb.protocol;

/**
 * Client connection initiation message.
 *
 * @param protocolVersion wire protocol version (e.g. 1)
 * @param clientName      client identification string (e.g. "KoraCLI/2.0.1")
 * @param database        default database to connect to (empty string if none)
 * @param user            user name (empty if none); sent after {@code database}, so older peers ignore it
 * @param password        password (empty if none)
 */
public record ConnectMessage(
        int protocolVersion,
        String clientName,
        String database,
        String user,
        String password
) implements Message {

    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public ConnectMessage {
        if (clientName == null) clientName = "KoraClient";
        if (database == null) database = "";
        if (user == null) user = "";
        if (password == null) password = "";
    }

    public ConnectMessage(int protocolVersion, String clientName, String database) {
        this(protocolVersion, clientName, database, "", "");
    }

    @Override
    public MessageType getType() {
        return MessageType.CONNECT;
    }

    @Override
    public String toString() {
        return "ConnectMessage[protocolVersion=" + protocolVersion + ", clientName=" + clientName
                + ", database=" + database + ", user=" + user + ", password=" + (password.isEmpty() ? "" : "****") + "]";
    }
}
