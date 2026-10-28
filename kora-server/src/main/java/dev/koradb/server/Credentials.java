package dev.koradb.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * The user name and password a node requires from clients (TCP protocol, JDBC, CLI, HTTP API)
 * and from replicas. {@link #NONE} means authentication is off.
 *
 * <p>{@link #toString()} never prints the password.
 */
public record Credentials(String user, String password) {

    public static final String DEFAULT_USER = "kora";
    public static final Credentials NONE = new Credentials(DEFAULT_USER, "");

    public Credentials {
        user = (user == null || user.isBlank()) ? DEFAULT_USER : user.trim();
        password = password == null ? "" : password;
    }

    public boolean required() {
        return !password.isEmpty();
    }

    /** Constant-time check of a presented user name and password. */
    public boolean accepts(String presentedUser, String presentedPassword) {
        if (!required()) return true;
        boolean userOk = constantTimeEquals(user, presentedUser == null || presentedUser.isBlank() ? DEFAULT_USER : presentedUser.trim());
        boolean passOk = constantTimeEquals(password, presentedPassword == null ? "" : presentedPassword);
        return userOk & passOk;
    }

    /**
     * Checks an HTTP {@code Authorization} header: {@code Basic base64(user:password)} or
     * {@code Bearer <password>}.
     */
    public boolean acceptsAuthorizationHeader(String header) {
        if (!required()) return true;
        if (header == null) return false;
        String h = header.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return accepts(user, h.substring(7).trim());
        }
        if (h.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return false;
            }
            int colon = decoded.indexOf(':');
            return colon >= 0 && accepts(decoded.substring(0, colon), decoded.substring(colon + 1));
        }
        return false;
    }

    static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                (actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "Credentials[user=" + user + ", password=" + (required() ? "****" : "<none>") + "]";
    }
}
