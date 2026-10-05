package dev.koradb.protocol;

/**
 * Client query execution request message.
 *
 * @param sql SQL text to execute
 */
public record QueryMessage(String sql) implements Message {

    public QueryMessage {
        if (sql == null) {
            throw new IllegalArgumentException("SQL query string cannot be null");
        }
    }

    @Override
    public MessageType getType() {
        return MessageType.QUERY;
    }
}
