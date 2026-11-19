package src.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time copy of an existing EquipLink PostgreSQL database into the SQLite database.
 *
 * <pre>
 * java -cp "equiplink.jar;lib/*" src.database.PostgresImport [--replace]
 *      [--url jdbc:postgresql://localhost:5432/equiplink] [--user postgres] [--password ...]
 * </pre>
 *
 * <p>Safety: every row, id and timestamp is copied in one SQLite transaction. The import
 * refuses to run if the SQLite database already has data beyond the default accounts, and only
 * replaces existing data when {@code --replace} is given. Afterwards it compares row
 * counts table by table and runs SQLite's integrity and foreign-key checks; on any
 * mismatch nothing is kept. The PostgreSQL database is only read, never changed.
 */
public final class PostgresImport {

    /** Copy order: parents before children, so foreign keys always resolve. */
    static final List<String> TABLES = List.of(
            "users", "password_reset_requests", "equipment", "borrow_requests",
            "request_occurrences", "request_groups", "request_items",
            "notifications", "request_history");

    private PostgresImport() {
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/equiplink";
        String user = "postgres";
        String password = System.getenv().getOrDefault("EQUIPLINK_PG_PASSWORD", "");
        boolean replace = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = args[++i];
                case "--user" -> user = args[++i];
                case "--password" -> password = args[++i];
                case "--replace" -> replace = true;
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        Map<String, Integer> copied = run(url, user, password, replace);
        System.out.println("Imported into " + Database.file() + ":");
        copied.forEach((table, rows) -> System.out.printf("  %-26s %d rows%n", table, rows));
    }

    /** Runs the import and returns rows copied per table. */
    public static Map<String, Integer> run(String url, String user, String password, boolean replace)
            throws Exception {
        DatabaseInitializer.initializeDatabase();
        try (Connection source = DriverManager.getConnection(url, user, password);
             Connection target = Database.getConnection()) {
            source.setReadOnly(true);
            source.setAutoCommit(false); // one consistent snapshot of the source
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            if (!replace && hasOwnData(target)) {
                throw new IllegalStateException("The SQLite database at " + Database.file()
                        + " already has data of its own. Re-run with --replace to overwrite it.");
            }

            target.setAutoCommit(false);
            Map<String, Integer> copied = new LinkedHashMap<>();
            try (Statement statement = target.createStatement()) {
                statement.execute("PRAGMA defer_foreign_keys = ON");
                for (int i = TABLES.size() - 1; i >= 0; i--) {
                    statement.executeUpdate("DELETE FROM " + TABLES.get(i));
                }
                for (String table : TABLES) {
                    copied.put(table, copyTable(source, target, table));
                }
                verify(source, target, copied);
                target.commit();
            } catch (Exception exception) {
                target.rollback();
                throw exception;
            } finally {
                target.setAutoCommit(true);
                source.rollback();
            }
            // Default accounts are only added when missing (the import usually has them).
            DatabaseSeeder.seedAccounts();
            return copied;
        }
    }

    private static int copyTable(Connection source, Connection target, String table)
            throws SQLException {
        if (!sourceHasTable(source, table)) {
            return 0;
        }
        Set<String> targetColumns = targetColumns(target, table);
        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rows.getMetaData();
            List<Integer> indexes = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                String name = meta.getColumnName(c).toLowerCase();
                if (targetColumns.contains(name)) {
                    indexes.add(c);
                    names.add(name);
                }
            }
            String sql = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                    + "?, ".repeat(names.size() - 1) + "?)";
            int count = 0;
            try (PreparedStatement insert = target.prepareStatement(sql)) {
                while (rows.next()) {
                    for (int k = 0; k < indexes.size(); k++) {
                        bind(insert, k + 1, rows, indexes.get(k), meta.getColumnType(indexes.get(k)));
                    }
                    insert.addBatch();
                    if (++count % 500 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            return count;
        }
    }

    /** Converts one PostgreSQL value into the SQLite storage format (see {@link Sql}). */
    private static void bind(PreparedStatement insert, int index, ResultSet rows, int column, int type)
            throws SQLException {
        switch (type) {
            case Types.DATE -> insert.setString(index, Sql.text(rows.getObject(column, LocalDate.class)));
            case Types.TIME -> insert.setString(index, Sql.text(rows.getObject(column, LocalTime.class)));
            case Types.TIMESTAMP -> insert.setString(index,
                    Sql.text(rows.getObject(column, LocalDateTime.class)));
            case Types.BOOLEAN, Types.BIT -> {
                boolean value = rows.getBoolean(column);
                if (rows.wasNull()) {
                    insert.setNull(index, Types.INTEGER);
                } else {
                    insert.setInt(index, value ? 1 : 0);
                }
            }
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT -> {
                long value = rows.getLong(column);
                if (rows.wasNull()) {
                    insert.setNull(index, Types.INTEGER);
                } else {
                    insert.setLong(index, value);
                }
            }
            default -> insert.setString(index, rows.getString(column));
        }
    }

    private static void verify(Connection source, Connection target, Map<String, Integer> copied)
            throws SQLException {
        for (Map.Entry<String, Integer> entry : copied.entrySet()) {
            int expected = sourceHasTable(source, entry.getKey()) ? count(source, entry.getKey()) : 0;
            int actual = count(target, entry.getKey());
            if (expected != actual || actual != entry.getValue()) {
                throw new SQLException("Row count mismatch in " + entry.getKey()
                        + ": PostgreSQL " + expected + ", SQLite " + actual);
            }
        }
        try (Statement statement = target.createStatement()) {
            try (ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (result.next()) {
                    throw new SQLException("Foreign key problem after import in table "
                            + result.getString(1) + " (row " + result.getLong(2) + ")");
                }
            }
            try (ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
                result.next();
                if (!"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("SQLite integrity check failed: " + result.getString(1));
                }
            }
        }
    }

    /** True when SQLite holds anything beyond the freshly seeded default accounts. */
    private static boolean hasOwnData(Connection target) throws SQLException {
        for (String table : TABLES) {
            if (!table.equals("users") && count(target, table) > 0) {
                return true;
            }
        }
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM users WHERE username NOT IN ("
                     + "'systemadmin', 'facilitystaff', 'facilityadmin', 'campusdirector')")) {
            result.next();
            return result.getInt(1) > 0;
        }
    }

    private static boolean sourceHasTable(Connection source, String table) throws SQLException {
        try (PreparedStatement statement = source.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Set<String> targetColumns(Connection target, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                columns.add(result.getString("name").toLowerCase());
            }
        }
        return columns;
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
