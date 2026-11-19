package src.sync;

import src.database.Database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two-way sync between the local SQLite database and Neon.
 *
 * <p><b>Push</b>: rows recorded in {@code sync_outbox} are uploaded to Neon's
 * {@code sync_rows} as JSON keyed by (table, sync_uuid). Foreign keys travel as the
 * parent's sync_uuid, so local integer ids never leave the computer. Last writer wins
 * by change time. Pushes are serialized with an advisory lock, so Neon's {@code seq}
 * order matches commit order and pulls never skip a change.
 *
 * <p><b>Pull</b>: rows with {@code seq} after this computer's cursor are applied locally
 * in one transaction with {@code sync_control.applying = 1} (not echoed back), parents
 * first. Rows with unsent local edits are skipped; those edits win or lose when they
 * are pushed. Anything that cannot be applied is recorded in {@code sync_conflicts}.
 */
public final class SyncEngine {

    private static final long PUSH_LOCK = 0x45514C4E4BL; // "EQLNK"

    private final String jdbcUrl;
    private final Map<String, List<String[]>> foreignKeys = new HashMap<>();
    private final Map<String, Set<String>> columns = new HashMap<>();

    public SyncEngine(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public SyncResult syncOnce() throws Exception {
        try (Connection remote = DriverManager.getConnection(jdbcUrl);
             Connection local = Database.getConnection()) {
            int pushed = push(local, remote);
            int[] pulled = pull(local, remote);
            return new SyncResult(pushed, pulled[0], pulled[1]);
        }
    }

    // ---- push --------------------------------------------------------------------

    private record Change(long outboxId, String table, long rowId, String uuid, String op, String changedAt) {
    }

    int push(Connection local, Connection remote) throws Exception {
        List<Change> changes = new ArrayList<>();
        try (Statement statement = local.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT outbox_id, table_name, row_id, row_uuid, op, changed_at "
                             + "FROM sync_outbox ORDER BY outbox_id")) {
            while (result.next()) {
                changes.add(new Change(result.getLong(1), result.getString(2), result.getLong(3),
                        result.getString(4), result.getString(5), result.getString(6)));
            }
        }
        if (changes.isEmpty()) {
            return 0;
        }
        long maxId = changes.get(changes.size() - 1).outboxId();
        // Keep the last change per row, in parent-first table order.
        Map<String, Change> latest = new LinkedHashMap<>();
        for (Change change : changes) {
            String key = change.table() + ":" + ("D".equals(change.op()) ? "u" + change.uuid() : "i" + change.rowId());
            latest.remove(key);
            latest.put(key, change);
        }
        String device = deviceId(local);
        int sent = 0;
        remote.setAutoCommit(false);
        try (PreparedStatement lock = remote.prepareStatement("SELECT pg_advisory_xact_lock(?)");
             PreparedStatement upsert = remote.prepareStatement("""
                     INSERT INTO sync_rows (table_name, row_uuid, data, deleted, changed_at, origin, seq)
                     VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, nextval('sync_seq'))
                     ON CONFLICT (table_name, row_uuid) DO UPDATE
                     SET data = EXCLUDED.data, deleted = EXCLUDED.deleted,
                         changed_at = EXCLUDED.changed_at, origin = EXCLUDED.origin, seq = EXCLUDED.seq
                     WHERE sync_rows.changed_at <= EXCLUDED.changed_at
                     """)) {
            lock.setLong(1, PUSH_LOCK);
            lock.execute();
            for (String table : SyncTables.PRIMARY_KEYS.keySet()) {
                for (Change change : latest.values()) {
                    if (!change.table().equals(table)) {
                        continue;
                    }
                    String uuid;
                    String json;
                    boolean deleted = "D".equals(change.op());
                    if (deleted) {
                        uuid = change.uuid();
                        json = null;
                    } else {
                        Map<String, Object> row = readRow(local, table, change.rowId());
                        if (row == null) {
                            continue; // deleted afterwards; its delete entry covers it
                        }
                        uuid = (String) row.remove("sync_uuid");
                        json = Json.write(row);
                    }
                    if (uuid == null) {
                        continue;
                    }
                    upsert.setString(1, table);
                    upsert.setString(2, uuid);
                    upsert.setString(3, json);
                    upsert.setBoolean(4, deleted);
                    upsert.setString(5, change.changedAt());
                    upsert.setString(6, device);
                    upsert.addBatch();
                    sent++;
                }
            }
            upsert.executeBatch();
            try (PreparedStatement seen = remote.prepareStatement("""
                    INSERT INTO sync_devices (device_id, last_seen) VALUES (?, now())
                    ON CONFLICT (device_id) DO UPDATE SET last_seen = now()
                    """)) {
                seen.setString(1, device);
                seen.executeUpdate();
            }
            remote.commit();
        } catch (Exception exception) {
            remote.rollback();
            throw exception;
        } finally {
            remote.setAutoCommit(true);
        }
        try (PreparedStatement clear = local.prepareStatement(
                "DELETE FROM sync_outbox WHERE outbox_id <= ?")) {
            clear.setLong(1, maxId);
            clear.executeUpdate();
        }
        return sent;
    }

    /** The row as JSON-ready values, with foreign keys replaced by parent sync ids. */
    private Map<String, Object> readRow(Connection local, String table, long rowId) throws SQLException {
        String pk = SyncTables.PRIMARY_KEYS.get(table);
        Map<String, Object> row = new LinkedHashMap<>();
        try (PreparedStatement statement = local.prepareStatement(
                "SELECT * FROM " + table + " WHERE " + pk + " = ?")) {
            statement.setLong(1, rowId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                for (int c = 1; c <= result.getMetaData().getColumnCount(); c++) {
                    String name = result.getMetaData().getColumnName(c);
                    if (!name.equals(pk)) {
                        row.put(name, result.getObject(c));
                    }
                }
            }
        }
        for (String[] fk : foreignKeys(local, table)) {
            Object value = row.get(fk[1]);
            if (value != null) {
                row.put(fk[1], uuidOf(local, fk[0], fk[2], ((Number) value).longValue()));
            }
        }
        return row;
    }

    // ---- pull --------------------------------------------------------------------

    private record Remote(String table, String uuid, String data, boolean deleted, long seq) {
    }

    int[] pull(Connection local, Connection remote) throws Exception {
        long cursor = Long.parseLong(state(local, "pull_cursor", "0"));
        List<Remote> rows = new ArrayList<>();
        try (PreparedStatement statement = remote.prepareStatement(
                "SELECT table_name, row_uuid, data::text, deleted, seq FROM sync_rows "
                        + "WHERE seq > ? ORDER BY seq")) {
            statement.setLong(1, cursor);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Remote(result.getString(1), result.getString(2), result.getString(3),
                            result.getBoolean(4), result.getLong(5)));
                }
            }
        }
        if (rows.isEmpty()) {
            return new int[] { 0, 0 };
        }
        long newCursor = rows.get(rows.size() - 1).seq();
        int applied = 0;
        int conflicts = 0;
        local.setAutoCommit(false);
        try (Statement statement = local.createStatement()) {
            statement.execute("UPDATE sync_control SET applying = 1 WHERE id = 1");
            statement.execute("PRAGMA defer_foreign_keys = ON");
            Set<String> pending = pendingKeys(local);
            List<String> order = new ArrayList<>(SyncTables.PRIMARY_KEYS.keySet());
            // Upserts parents first, then deletes children first.
            for (String table : order) {
                for (Remote row : rows) {
                    if (row.table().equals(table) && !row.deleted()
                            && !pending.contains(table + ":" + row.uuid())) {
                        String problem = upsertLocal(local, row);
                        if (problem == null) {
                            applied++;
                        } else {
                            conflict(local, table, row.uuid(), problem);
                            conflicts++;
                        }
                    }
                }
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                String table = order.get(i);
                for (Remote row : rows) {
                    if (row.table().equals(table) && row.deleted()
                            && !pending.contains(table + ":" + row.uuid())) {
                        try (PreparedStatement delete = local.prepareStatement(
                                "DELETE FROM " + table + " WHERE sync_uuid = ?")) {
                            delete.setString(1, row.uuid());
                            applied += delete.executeUpdate();
                        }
                    }
                }
            }
            conflicts += removeOrphans(local);
            statement.execute("UPDATE sync_control SET applying = 0 WHERE id = 1");
            setState(local, "pull_cursor", Long.toString(newCursor));
            local.commit();
        } catch (Exception exception) {
            local.rollback();
            throw exception;
        } finally {
            local.setAutoCommit(true);
        }
        return new int[] { applied, conflicts };
    }

    /** Applies one remote row; returns null on success or the reason it was skipped. */
    private String upsertLocal(Connection local, Remote row) throws SQLException {
        String table = row.table();
        String pk = SyncTables.PRIMARY_KEYS.get(table);
        Map<String, Object> data = new LinkedHashMap<>(Json.read(row.data()));
        data.keySet().retainAll(columns(local, table));
        data.remove(pk);
        data.remove("sync_uuid");
        for (String[] fk : foreignKeys(local, table)) {
            Object value = data.get(fk[1]);
            if (value != null) {
                Long parent = idOf(local, fk[0], fk[2], value.toString());
                if (parent == null) {
                    return "Missing " + fk[0] + " " + value + " for " + fk[1];
                }
                data.put(fk[1], parent);
            }
        }
        Long existing = idOf(local, table, pk, row.uuid());
        try {
            if (existing != null) {
                update(local, table, pk, existing, data);
            } else {
                insert(local, table, row.uuid(), data);
            }
            return null;
        } catch (SQLException exception) {
            String natural = SyncTables.NATURAL_KEYS.get(table);
            if (existing == null && natural != null && data.get(natural) != null
                    && src.database.Sql.isUniqueViolation(exception)) {
                return adopt(local, table, pk, natural, row.uuid(), data);
            }
            return exception.getMessage();
        }
    }

    /** Same record created on two computers: the local row takes the remote sync id. */
    private String adopt(Connection local, String table, String pk, String natural, String uuid,
            Map<String, Object> data) throws SQLException {
        Long id;
        String oldUuid;
        try (PreparedStatement find = local.prepareStatement(
                "SELECT " + pk + ", sync_uuid FROM " + table + " WHERE " + natural + " = ?")) {
            find.setObject(1, data.get(natural));
            try (ResultSet result = find.executeQuery()) {
                if (!result.next()) {
                    return "Duplicate " + natural + " " + data.get(natural);
                }
                id = result.getLong(1);
                oldUuid = result.getString(2);
            }
        }
        try {
            try (PreparedStatement relink = local.prepareStatement(
                    "UPDATE " + table + " SET sync_uuid = ? WHERE " + pk + " = ?")) {
                relink.setString(1, uuid);
                relink.setLong(2, id);
                relink.executeUpdate();
            }
            update(local, table, pk, id, data);
        } catch (SQLException exception) {
            return exception.getMessage();
        }
        if (oldUuid != null && !oldUuid.equals(uuid)) {
            // Retire the duplicate this computer uploaded earlier.
            try (PreparedStatement retire = local.prepareStatement(
                    "INSERT INTO sync_outbox (table_name, row_id, row_uuid, op) VALUES (?, ?, ?, 'D')")) {
                retire.setString(1, table);
                retire.setLong(2, id);
                retire.setString(3, oldUuid);
                retire.executeUpdate();
            }
        }
        return null;
    }

    private void update(Connection local, String table, String pk, long id, Map<String, Object> data)
            throws SQLException {
        if (data.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("UPDATE " + table + " SET ");
        int i = 0;
        for (String column : data.keySet()) {
            sql.append(i++ == 0 ? "" : ", ").append(column).append(" = ?");
        }
        sql.append(" WHERE ").append(pk).append(" = ?");
        try (PreparedStatement statement = local.prepareStatement(sql.toString())) {
            int index = 1;
            for (Object value : data.values()) {
                statement.setObject(index++, value);
            }
            statement.setLong(index, id);
            statement.executeUpdate();
        }
    }

    private void insert(Connection local, String table, String uuid, Map<String, Object> data)
            throws SQLException {
        StringBuilder names = new StringBuilder("sync_uuid");
        StringBuilder marks = new StringBuilder("?");
        for (String column : data.keySet()) {
            names.append(", ").append(column);
            marks.append(", ?");
        }
        try (PreparedStatement statement = local.prepareStatement(
                "INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ")")) {
            statement.setString(1, uuid);
            int index = 2;
            for (Object value : data.values()) {
                statement.setObject(index++, value);
            }
            statement.executeUpdate();
        }
    }

    /** Drops rows whose parent is gone (e.g. deleted elsewhere), so the commit can succeed. */
    private int removeOrphans(Connection local) throws SQLException {
        int removed = 0;
        for (int pass = 0; pass < 5; pass++) {
            List<Object[]> orphans = new ArrayList<>();
            try (Statement statement = local.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
                while (result.next()) {
                    orphans.add(new Object[] { result.getString(1), result.getLong(2), result.getString(3) });
                }
            }
            if (orphans.isEmpty()) {
                break;
            }
            for (Object[] orphan : orphans) {
                String table = (String) orphan[0];
                if (!SyncTables.PRIMARY_KEYS.containsKey(table)) {
                    continue;
                }
                conflict(local, table, null, "Removed row " + orphan[1] + ": its " + orphan[2] + " no longer exists");
                try (PreparedStatement delete = local.prepareStatement(
                        "DELETE FROM " + table + " WHERE rowid = ?")) {
                    delete.setLong(1, (Long) orphan[1]);
                    removed += delete.executeUpdate();
                }
            }
        }
        return removed;
    }

    // ---- helpers -----------------------------------------------------------------

    /** Rows with local changes not yet pushed, as "table:uuid". */
    private Set<String> pendingKeys(Connection local) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (Statement statement = local.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT table_name, row_id, row_uuid, op FROM sync_outbox")) {
            while (result.next()) {
                String table = result.getString(1);
                String uuid = "D".equals(result.getString(4)) ? result.getString(3)
                        : uuidOf(local, table, SyncTables.PRIMARY_KEYS.get(table), result.getLong(2));
                if (uuid != null) {
                    keys.add(table + ":" + uuid);
                }
            }
        }
        return keys;
    }

    /** [parentTable, column, parentColumn] for each foreign key of the table. */
    private List<String[]> foreignKeys(Connection local, String table) throws SQLException {
        List<String[]> cached = foreignKeys.get(table);
        if (cached != null) {
            return cached;
        }
        List<String[]> keys = new ArrayList<>();
        try (Statement statement = local.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (result.next()) {
                keys.add(new String[] { result.getString("table"), result.getString("from"),
                        result.getString("to") });
            }
        }
        foreignKeys.put(table, keys);
        return keys;
    }

    private Set<String> columns(Connection local, String table) throws SQLException {
        Set<String> cached = columns.get(table);
        if (cached != null) {
            return cached;
        }
        Set<String> names = new HashSet<>();
        try (Statement statement = local.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                names.add(result.getString("name"));
            }
        }
        columns.put(table, names);
        return names;
    }

    private static String uuidOf(Connection local, String table, String column, long id) throws SQLException {
        try (PreparedStatement statement = local.prepareStatement(
                "SELECT sync_uuid FROM " + table + " WHERE " + column + " = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static Long idOf(Connection local, String table, String column, String uuid) throws SQLException {
        try (PreparedStatement statement = local.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE sync_uuid = ?")) {
            statement.setString(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private static void conflict(Connection local, String table, String uuid, String reason)
            throws SQLException {
        try (PreparedStatement statement = local.prepareStatement(
                "INSERT INTO sync_conflicts (table_name, row_uuid, reason) VALUES (?, ?, ?)")) {
            statement.setString(1, table);
            statement.setString(2, uuid);
            statement.setString(3, reason);
            statement.executeUpdate();
        }
    }

    static String deviceId(Connection local) throws SQLException {
        return state(local, "device_id", "unknown");
    }

    static String state(Connection local, String key, String fallback) throws SQLException {
        try (PreparedStatement statement = local.prepareStatement(
                "SELECT value FROM sync_state WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : fallback;
            }
        }
    }

    private static void setState(Connection local, String key, String value) throws SQLException {
        try (PreparedStatement statement = local.prepareStatement(
                "INSERT INTO sync_state (key, value) VALUES (?, ?) "
                        + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }
}
