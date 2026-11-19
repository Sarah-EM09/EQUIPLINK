package src.util;

/**
 * Shared recovery window for soft-deleted requests and notifications.
 *
 * <p>Deletes never remove the database row immediately: the row is flagged with a
 * deletion timestamp, and both the service layer and the UI use this window to
 * decide whether an Undo is still possible.</p>
 */
public final class UndoPolicy {

    /** Number of minutes a deleted request or notification can be restored. */
    public static final int RECOVERY_MINUTES = 10;

    /**
     * SQL for the start of the recovery window (now minus {@link #RECOVERY_MINUTES},
     * Philippine time); kept here so both stay in sync.
     */
    public static final String RECOVERY_SQL =
            "datetime('now', '+8 hours', '-" + RECOVERY_MINUTES + " minutes')";

    /** Human-readable window used in confirmation and Undo messages. */
    public static final String RECOVERY_LABEL = RECOVERY_MINUTES + " minutes";

    private UndoPolicy() {
    }
}
