package dev.koradb.mvcc;

/**
 * Thread-local context maintaining the active {@link ReadView} for MVCC snapshot evaluation.
 */
public final class MvccContext {

    private static final ThreadLocal<ReadView> CURRENT_READ_VIEW = new ThreadLocal<>();

    private MvccContext() {}

    /**
     * Returns the active {@link ReadView} for the current execution thread, or {@code null} if none.
     */
    public static ReadView getReadView() {
        return CURRENT_READ_VIEW.get();
    }

    /**
     * Sets the active {@link ReadView} for the current execution thread.
     */
    public static void setReadView(ReadView readView) {
        if (readView != null) {
            CURRENT_READ_VIEW.set(readView);
        } else {
            CURRENT_READ_VIEW.remove();
        }
    }

    /**
     * Clears the active {@link ReadView} for the current execution thread.
     */
    public static void clear() {
        CURRENT_READ_VIEW.remove();
    }
}
