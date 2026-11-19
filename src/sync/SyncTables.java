package src.sync;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tables kept in sync with Neon, parents before children, with their local primary keys. */
public final class SyncTables {

    /** table -> primary key column. Order matters: parents first. */
    public static final Map<String, String> PRIMARY_KEYS = new LinkedHashMap<>();

    /**
     * Natural keys: when a pulled row collides with a different local row on this
     * column, they are the same record (e.g. the default "systemadmin" account created
     * on two computers), so the local row adopts the remote sync id instead of duplicating.
     */
    public static final Map<String, String> NATURAL_KEYS = Map.of(
            "users", "username",
            "equipment", "equipment_code",
            "borrow_requests", "request_code");

    static {
        PRIMARY_KEYS.put("users", "user_id");
        PRIMARY_KEYS.put("password_reset_requests", "reset_request_id");
        PRIMARY_KEYS.put("equipment", "equipment_id");
        PRIMARY_KEYS.put("borrow_requests", "request_id");
        PRIMARY_KEYS.put("request_occurrences", "occurrence_id");
        PRIMARY_KEYS.put("request_groups", "group_id");
        PRIMARY_KEYS.put("request_items", "request_item_id");
        PRIMARY_KEYS.put("notifications", "notification_id");
        PRIMARY_KEYS.put("request_history", "history_id");
        PRIMARY_KEYS.put("recovery_codes", "code_id");
        PRIMARY_KEYS.put("security_events", "event_id");
    }

    /** Default accounts get the same sync id on every computer, so they merge. */
    public static String seedUuid(String username) {
        return "seed:" + username.toLowerCase(java.util.Locale.ROOT);
    }

    private SyncTables() {
    }
}
