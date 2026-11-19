package src.database;

import src.sync.SyncTables;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Version 3: offline-first sync with Neon.
 *
 * <p>Every synced table gets a {@code sync_uuid} (its id across all computers; local
 * integer ids stay local). Triggers record each local insert, update and delete in
 * {@code sync_outbox}; the sync engine pushes those to Neon when online and applies
 * changes pulled from Neon with {@code sync_control.applying = 1}, so pulled changes
 * are not echoed back. Existing rows are queued so the first sync uploads them.
 */
final class SchemaV3 {

    private static final String MILLIS_NOW = "strftime('%Y-%m-%d %H:%M:%f', 'now', '+8 hours')";
    private static final String NEW_UUID = "lower(hex(randomblob(16)))";

    static final List<String> STATEMENTS = build();

    private static List<String> build() {
        List<String> sql = new ArrayList<>();
        sql.add("CREATE TABLE sync_control (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "applying INTEGER NOT NULL DEFAULT 0)");
        sql.add("INSERT INTO sync_control (id, applying) VALUES (1, 0)");
        sql.add("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT)");
        sql.add("INSERT INTO sync_state (key, value) VALUES ('device_id', " + NEW_UUID + ")");
        sql.add("INSERT INTO sync_state (key, value) VALUES ('pull_cursor', '0')");
        sql.add("""
                CREATE TABLE sync_outbox (
                    outbox_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    row_id INTEGER NOT NULL,
                    row_uuid TEXT,
                    op TEXT NOT NULL CHECK (op IN ('U', 'D')),
                    changed_at TEXT NOT NULL DEFAULT (%s)
                )
                """.formatted(MILLIS_NOW));
        sql.add("""
                CREATE TABLE sync_conflicts (
                    conflict_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    row_uuid TEXT,
                    reason TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (%s)
                )
                """.formatted(Sql.NOW));

        String applyingOff = "(SELECT applying FROM sync_control WHERE id = 1) = 0";
        for (Map.Entry<String, String> entry : SyncTables.PRIMARY_KEYS.entrySet()) {
            String table = entry.getKey();
            String pk = entry.getValue();
            sql.add("ALTER TABLE " + table + " ADD COLUMN sync_uuid TEXT");
            sql.add("UPDATE " + table + " SET sync_uuid = " + NEW_UUID);
            sql.add("CREATE UNIQUE INDEX idx_" + table + "_sync_uuid ON " + table + "(sync_uuid)");
            // Rows created on this computer get a sync id and are queued for upload.
            sql.add("CREATE TRIGGER sync_ins_" + table + " AFTER INSERT ON " + table
                    + " WHEN " + applyingOff + " BEGIN "
                    + "UPDATE " + table + " SET sync_uuid = " + NEW_UUID
                    + " WHERE " + pk + " = NEW." + pk + " AND sync_uuid IS NULL; "
                    + "INSERT INTO sync_outbox (table_name, row_id, op) VALUES ('"
                    + table + "', NEW." + pk + ", 'U'); END");
            sql.add("CREATE TRIGGER sync_upd_" + table + " AFTER UPDATE ON " + table
                    + " WHEN " + applyingOff + " BEGIN "
                    + "INSERT INTO sync_outbox (table_name, row_id, op) VALUES ('"
                    + table + "', NEW." + pk + ", 'U'); END");
            sql.add("CREATE TRIGGER sync_del_" + table + " AFTER DELETE ON " + table
                    + " WHEN " + applyingOff + " BEGIN "
                    + "INSERT INTO sync_outbox (table_name, row_id, row_uuid, op) VALUES ('"
                    + table + "', OLD." + pk + ", OLD.sync_uuid, 'D'); END");
        }
        // Default accounts share one sync id on every computer.
        sql.add("UPDATE users SET sync_uuid = 'seed:' || lower(username) WHERE username IN "
                + "('systemadmin', 'facilitystaff', 'facilityadmin', 'campusdirector')");
        // Queue everything that already exists for the first upload (parents first).
        for (Map.Entry<String, String> entry : SyncTables.PRIMARY_KEYS.entrySet()) {
            sql.add("INSERT INTO sync_outbox (table_name, row_id, op) SELECT '" + entry.getKey()
                    + "', " + entry.getValue() + ", 'U' FROM " + entry.getKey()
                    + " ORDER BY " + entry.getValue());
        }
        // Default accounts upload as "old" so real changes from any computer win.
        sql.add("UPDATE sync_outbox SET changed_at = '2000-01-01 00:00:00.001' WHERE table_name = 'users' "
                + "AND row_id IN (SELECT user_id FROM users WHERE sync_uuid LIKE 'seed:%')");
        return List.copyOf(sql);
    }

    private SchemaV3() {
    }
}
