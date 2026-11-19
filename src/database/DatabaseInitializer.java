package src.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Creates and upgrades the SQLite schema with numbered migrations.
 *
 * <p>The applied version is kept in {@code PRAGMA user_version}. Each migration runs
 * in its own transaction together with the version bump, so a failure leaves the
 * database exactly at the previous version. To change the schema later, append a
 * new list to {@link #MIGRATIONS}; never edit one that has already shipped.
 */
public final class DatabaseInitializer {

    /** MIGRATIONS.get(i) upgrades the database from version i to i + 1. */
    static final List<List<String>> MIGRATIONS = List.of(
            SchemaV1.STATEMENTS,
            SchemaV2.STATEMENTS
    );

    private DatabaseInitializer() {
        // Prevent object instantiation.
    }

    public static void initializeDatabase() throws SQLException {
        try (Connection connection = Database.getConnection()) {
            int version = userVersion(connection);
            if (version > MIGRATIONS.size()) {
                throw new SQLException("The database at " + Database.file()
                        + " was created by a newer EquipLink (schema v" + version
                        + "). Update the app to open it.");
            }
            for (int next = version; next < MIGRATIONS.size(); next++) {
                migrate(connection, next + 1, MIGRATIONS.get(next));
            }
            System.out.println("EquipLink database ready (schema v" + MIGRATIONS.size()
                    + ") at " + Database.file());
        }
    }

    private static void migrate(Connection connection, int target, List<String> statements)
            throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
            statement.execute("PRAGMA user_version = " + target);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            System.err.println("Database migration to v" + target + " failed: "
                    + exception.getMessage());
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
}
