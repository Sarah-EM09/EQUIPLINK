package src.service;

import src.database.Database;
import src.util.PasswordHasher;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Account recovery for elevated accounts (ICT System Admin, Facility Admin, Campus
 * Director), used when no other administrator can approve a password reset.
 *
 * <ul>
 *   <li><b>Recovery codes</b>: an admin generates {@value #CODE_COUNT} one-time codes in
 *       Settings. Only PBKDF2 hashes are stored; each code resets the password once.</li>
 *   <li><b>Emergency reset</b>: run on the computer that holds the database
 *       ({@code EquipLink --reset-password <username>}). It sets a temporary password
 *       that must be changed at the next sign-in. Anyone with access to that computer
 *       could already edit the database file, so this adds no new way in; it makes the
 *       reset safe and recorded.</li>
 * </ul>
 * Every generation, use, failed attempt and emergency reset is written to
 * {@code security_events}, and the other administrators are notified.
 */
public class RecoveryService {

    public static final int CODE_COUNT = 8;
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final int LOCKOUT_MINUTES = 15;

    /** No 0/O, 1/I/L: easy to read back from paper. 31^10 codes (about 50 bits). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final String TEMP_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final String ELEVATED = "('ICT_STAFF', 'ADMIN', 'DIRECTOR')";
    private static final String GENERIC_FAILURE = "The username or recovery code is incorrect.";

    private final SecureRandom random = new SecureRandom();

    /** Replaces the user's unused codes with a fresh set and returns them (shown once). */
    public List<String> generateCodes(long userId) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireElevated(connection, userId);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM recovery_codes WHERE user_id = ? AND used_at IS NULL")) {
                    delete.setLong(1, userId);
                    delete.executeUpdate();
                }
                List<String> codes = new ArrayList<>();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO recovery_codes (user_id, code_hash, code_salt, iterations)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    for (int i = 0; i < CODE_COUNT; i++) {
                        String code = randomText(CODE_ALPHABET, 10);
                        String salt = PasswordHasher.generateSalt();
                        insert.setLong(1, userId);
                        insert.setString(2, PasswordHasher.hashPassword(code.toCharArray(), salt));
                        insert.setString(3, salt);
                        insert.setInt(4, PasswordHasher.getIterations());
                        insert.executeUpdate();
                        codes.add(code.substring(0, 5) + "-" + code.substring(5));
                    }
                }
                logEvent(connection, userId, "RECOVERY_CODES_GENERATED", CODE_COUNT + " codes");
                connection.commit();
                return codes;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public int remainingCodes(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM recovery_codes WHERE user_id = ? AND used_at IS NULL")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** Sets a new password using one unused recovery code, which is then spent. */
    public void resetWithCode(String usernameOrEmail, String code, char[] newPassword)
            throws Exception {
        if (newPassword == null || newPassword.length < 8) {
            throw new IllegalArgumentException("The new password must have at least 8 characters.");
        }
        String normalized = code == null ? ""
                : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Long userId = findElevatedUser(connection, usernameOrEmail);
                if (userId == null) {
                    connection.rollback();
                    throw new IllegalArgumentException(GENERIC_FAILURE);
                }
                if (recentFailures(connection, userId) >= MAX_FAILED_ATTEMPTS) {
                    connection.rollback();
                    throw new IllegalStateException("Too many incorrect recovery codes. Try again in "
                            + LOCKOUT_MINUTES + " minutes.");
                }
                Long codeId = matchCode(connection, userId, normalized);
                if (codeId == null) {
                    logEvent(connection, userId, "RECOVERY_CODE_FAILED", null);
                    connection.commit(); // keep the failed attempt for the lockout
                    throw new IllegalArgumentException(GENERIC_FAILURE);
                }
                try (PreparedStatement spend = connection.prepareStatement(
                        "UPDATE recovery_codes SET used_at = " + src.database.Sql.NOW
                                + " WHERE code_id = ? AND used_at IS NULL")) {
                    spend.setLong(1, codeId);
                    if (spend.executeUpdate() != 1) {
                        throw new IllegalStateException("That recovery code was just used.");
                    }
                }
                setPassword(connection, userId, newPassword, false);
                int left = countUnused(connection, userId);
                logEvent(connection, userId, "RECOVERY_CODE_USED", left + " codes left");
                notifyOtherAdmins(connection, userId, "Password reset with a recovery code",
                        displayName(connection, userId) + " reset their password with a recovery code.");
                connection.commit();
            } catch (Exception exception) {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                throw exception;
            }
        }
    }

    /**
     * Emergency reset run on the database's computer. Returns a temporary password that
     * must be changed at the next sign-in.
     */
    public String emergencyReset(String usernameOrEmail) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Long userId = findElevatedUser(connection, usernameOrEmail);
                if (userId == null) {
                    throw new IllegalArgumentException("No active ICT System Admin, Facility Admin or "
                            + "Campus Director account matches \"" + usernameOrEmail + "\".");
                }
                String temporary = randomText(TEMP_ALPHABET, 14);
                setPassword(connection, userId, temporary.toCharArray(), true);
                logEvent(connection, userId, "EMERGENCY_RESET", "temporary password issued on this computer");
                notifyOtherAdmins(connection, userId, "Emergency password reset",
                        displayName(connection, userId) + "'s password was reset on the computer "
                                + "that stores the EquipLink database.");
                connection.commit();
                return temporary;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public boolean mustChangePassword(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT must_change_password FROM users WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private Long findElevatedUser(Connection connection, String usernameOrEmail) throws SQLException {
        String login = usernameOrEmail == null ? "" : usernameOrEmail.trim();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id FROM users WHERE active = TRUE AND role IN " + ELEVATED
                        + " AND (LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?))")) {
            statement.setString(1, login);
            statement.setString(2, login);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private void requireElevated(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM users WHERE user_id = ? AND active = TRUE AND role IN " + ELEVATED)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SecurityException("Recovery codes are only for administrator accounts.");
                }
            }
        }
    }

    private Long matchCode(Connection connection, long userId, String code) throws Exception {
        if (code.length() != 10) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT code_id, code_hash, code_salt, iterations FROM recovery_codes
                WHERE user_id = ? AND used_at IS NULL
                """)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (PasswordHasher.verifyPassword(code.toCharArray(), result.getString("code_salt"),
                            result.getString("code_hash"), result.getInt("iterations"))) {
                        return result.getLong("code_id");
                    }
                }
            }
        }
        return null;
    }

    private int recentFailures(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM security_events
                WHERE user_id = ? AND event_type = 'RECOVERY_CODE_FAILED'
                  AND created_at >= datetime('now', '+8 hours', ?)
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, "-" + LOCKOUT_MINUTES + " minutes");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int countUnused(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM recovery_codes WHERE user_id = ? AND used_at IS NULL")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void setPassword(Connection connection, long userId, char[] password, boolean mustChange)
            throws Exception {
        String salt = PasswordHasher.generateSalt();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE users SET password_hash = ?, password_salt = ?, password_iterations = ?,
                                 must_change_password = ?
                WHERE user_id = ?
                """)) {
            statement.setString(1, PasswordHasher.hashPassword(password, salt));
            statement.setString(2, salt);
            statement.setInt(3, PasswordHasher.getIterations());
            statement.setBoolean(4, mustChange);
            statement.setLong(5, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Password update did not complete.");
            }
        }
    }

    private void logEvent(Connection connection, long userId, String type, String details)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO security_events (user_id, event_type, details) VALUES (?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setString(2, type);
            statement.setString(3, details);
            statement.executeUpdate();
        }
    }

    private void notifyOtherAdmins(Connection connection, long userId, String title, String message)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO notifications (user_id, title, message, action) "
                        + "SELECT user_id, ?, ?, 'SECURITY' FROM users "
                        + "WHERE active = TRUE AND role IN " + ELEVATED + " AND user_id <> ?")) {
            statement.setString(1, title);
            statement.setString(2, message);
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    private String displayName(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT full_name, username FROM users WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? result.getString("full_name") + " (" + result.getString("username") + ")"
                        : "An administrator";
            }
        }
    }

    private String randomText(String alphabet, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }
}
