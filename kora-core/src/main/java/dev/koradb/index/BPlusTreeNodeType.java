package dev.koradb.index;

/**
 * Type of a B+ Tree node on disk.
 */
public enum BPlusTreeNodeType {
    LEAF((byte) 1),
    INTERNAL((byte) 2);

    private final byte code;

    BPlusTreeNodeType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static BPlusTreeNodeType fromCode(byte code) {
        return switch (code) {
            case 1 -> LEAF;
            case 2 -> INTERNAL;
            default -> throw new IllegalArgumentException("Unknown BPlusTreeNodeType code: " + code);
        };
    }
}
