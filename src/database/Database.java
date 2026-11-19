package src.database;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens connections to EquipLink's local SQLite database.
 *
 * <p>Where the file lives:
 * <ul>
 *   <li>{@code -Dequiplink.db=...} or the {@code EQUIPLINK_DB} environment variable, if set;</li>
 *   <li>installed copies: {@code %LOCALAPPDATA%\EquipLink\equiplink.db}
 *       ({@code ~/.equiplink/equiplink.db} outside Windows), which survives updates;</li>
 *   <li>running from source: {@code data/equiplink.db} in the project folder.</li>
 * </ul>
 *
 * <p>Every connection enforces foreign keys, waits for busy locks instead of failing,
 * and starts write transactions with {@code BEGIN IMMEDIATE}, which serializes writers
 * the way the old PostgreSQL {@code SELECT ... FOR UPDATE} row locks did.
 */
public final class Database {

    /** All timestamps and dates are handled as Philippine time (Asia/Manila, UTC+8). */
    public static final String TIME_ZONE = "Asia/Manila";

    private static volatile Path file;

    private Database() {
        // Prevent object instantiation.
    }

    public static Connection getConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(10_000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        return config.createConnection("jdbc:sqlite:" + file());
    }

    /** The database file, creating its folder on first use. */
    public static synchronized Path file() throws SQLException {
        if (file == null) {
            Path chosen = choose();
            try {
                if (chosen.getParent() != null) {
                    Files.createDirectories(chosen.getParent());
                }
            } catch (IOException exception) {
                throw new SQLException("Cannot create the database folder for " + chosen, exception);
            }
            file = chosen.toAbsolutePath();
        }
        return file;
    }

    private static Path choose() {
        String override = System.getProperty("equiplink.db");
        if (override == null || override.isBlank()) {
            override = System.getenv("EQUIPLINK_DB");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        if (src.util.AppInfo.isPackaged()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path dir = localAppData != null && !localAppData.isBlank()
                    ? Path.of(localAppData, "EquipLink")
                    : Path.of(System.getProperty("user.home"), ".equiplink");
            return dir.resolve("equiplink.db");
        }
        return Path.of("data", "equiplink.db");
    }
}
