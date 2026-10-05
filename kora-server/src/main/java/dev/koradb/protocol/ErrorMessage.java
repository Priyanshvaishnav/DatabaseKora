package dev.koradb.protocol;

/**
 * Server error response message indicating execution failure, syntax error, or conflict.
 *
 * @param errorCode numeric error classification code
 * @param message   human-readable error description
 */
public record ErrorMessage(
        int errorCode,
        String message
) implements Message {

    public static final int ERR_GENERIC = 1000;
    public static final int ERR_PARSE = 1001;
    public static final int ERR_SCHEMA = 1002;
    public static final int ERR_EXECUTION = 1003;
    public static final int ERR_CONFLICT = 1004;
    public static final int ERR_PROTOCOL = 1005;

    public ErrorMessage {
        if (message == null) message = "Unknown error";
    }

    @Override
    public MessageType getType() {
        return MessageType.ERROR;
    }
}
