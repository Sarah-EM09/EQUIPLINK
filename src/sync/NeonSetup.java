package src.sync;

import src.database.Database;
import src.database.DatabaseInitializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-time Neon setup, run by the database owner (not by the app):
 * <pre>
 * java -cp "equiplink.jar;lib/*" src.sync.NeonSetup "&lt;owner connection string&gt;" [app-url-output-file]
 * </pre>
 * Creates the sync tables, a least-privilege {@code equiplink_app} login (it can only
 * read and upsert sync rows, never delete or change the schema), and read-only views
 * (schema {@code equiplink}) that show each synced table as normal columns for
 * reporting in the Neon console. Safe to re-run; it rotates the app password and
 * writes the new app connection string to the output file (default: neon.properties
 * next to the local database).
 */
public final class NeonSetup {

    static final List<String> SCHEMA = List.of(
            "CREATE SEQUENCE IF NOT EXISTS sync_seq",
            """
            CREATE TABLE IF NOT EXISTS sync_rows (
                table_name TEXT NOT NULL,
                row_uuid TEXT NOT NULL,
                data JSONB,
                deleted BOOLEAN NOT NULL DEFAULT FALSE,
                changed_at TEXT NOT NULL,
                origin TEXT NOT NULL,
                seq BIGINT NOT NULL,
                PRIMARY KEY (table_name, row_uuid)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_sync_rows_seq ON sync_rows (seq)",
            """
            CREATE TABLE IF NOT EXISTS sync_devices (
                device_id TEXT PRIMARY KEY,
                first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
                last_seen TIMESTAMPTZ
            )
            """,
            "CREATE SCHEMA IF NOT EXISTS equiplink");

    private NeonSetup() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: NeonSetup <owner connection string> [app-url-output-file]");
            System.exit(2);
        }
        String ownerUrl = SyncConfig.toJdbc(args[0]);
        String password = randomPassword();
        try (Connection owner = DriverManager.getConnection(ownerUrl);
             Statement statement = owner.createStatement()) {
            for (String sql : SCHEMA) {
                statement.execute(sql);
            }
            boolean exists;
            try (ResultSet result = statement.executeQuery(
                    "SELECT 1 FROM pg_roles WHERE rolname = 'equiplink_app'")) {
                exists = result.next();
            }
            statement.execute((exists ? "ALTER" : "CREATE") + " ROLE equiplink_app LOGIN PASSWORD '"
                    + password + "'");
            statement.execute("GRANT CONNECT ON DATABASE " + database(owner) + " TO equiplink_app");
            statement.execute("GRANT USAGE ON SCHEMA public TO equiplink_app");
            statement.execute("REVOKE ALL ON sync_rows, sync_devices FROM equiplink_app");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON sync_rows, sync_devices TO equiplink_app");
            statement.execute("GRANT USAGE, SELECT ON SEQUENCE sync_seq TO equiplink_app");
            for (String view : views()) {
                statement.execute(view);
            }
        }
        String appUrl = args[0].replaceFirst("://[^@]+@", "://equiplink_app:" + password + "@");
        Path output = args.length > 1 ? Path.of(args[1])
                : Database.file().getParent().resolve("neon.properties");
        Files.writeString(output, "# EquipLink sync (Neon). Keep this file private.\nurl="
                + appUrl.replace("\\", "\\\\") + "\n", StandardCharsets.UTF_8);
        output.toFile().setReadable(false, false);
        output.toFile().setReadable(true, true);
        System.out.println("Neon is ready. App connection string written to " + output);
    }

    /** One view per synced table, using the local schema's columns. */
    static List<String> views() throws SQLException {
        DatabaseInitializer.initializeDatabase();
        List<String> views = new ArrayList<>();
        try (Connection local = Database.getConnection(); Statement statement = local.createStatement()) {
            for (Map.Entry<String, String> entry : SyncTables.PRIMARY_KEYS.entrySet()) {
                StringBuilder sql = new StringBuilder("CREATE OR REPLACE VIEW equiplink.")
                        .append(entry.getKey()).append(" AS SELECT row_uuid AS sync_uuid");
                try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + entry.getKey() + ")")) {
                    while (result.next()) {
                        String column = result.getString("name");
                        if (!column.equals(entry.getValue()) && !column.equals("sync_uuid")) {
                            sql.append(", data->>'").append(column).append("' AS ").append(column);
                        }
                    }
                }
                sql.append(", changed_at, origin FROM public.sync_rows WHERE table_name = '")
                        .append(entry.getKey()).append("' AND NOT deleted");
                views.add("DROP VIEW IF EXISTS equiplink." + entry.getKey());
                views.add(sql.toString());
            }
        }
        return views;
    }

    private static String database(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_database()")) {
            result.next();
            return "\"" + result.getString(1).replace("\"", "\"\"") + "\"";
        }
    }

    private static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return password.toString();
    }
}
