package src.service;

import src.database.Database;
import src.database.Sql;
import src.model.PasswordResetRequest;
import src.model.User;
import src.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Part 1 of AuthService, split out to keep files short. */
public abstract class AuthServicePart1 {

    public User login(String usernameOrEmail, char[] password)
            throws Exception {

        String sql = """
                SELECT user_id, username, email, full_name,
                       department, role, borrower_type, active, notifications_enabled,
                       password_hash, password_salt,
                       password_iterations
                FROM users
                WHERE LOWER(username) = LOWER(?)
                   OR LOWER(email) = LOWER(?)
                LIMIT 1
                """;

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, usernameOrEmail.trim());
            statement.setString(2, usernameOrEmail.trim());

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    return null;
                }

                if (!rs.getBoolean("active")) {
                    throw new Exception("Your account is inactive.");
                }

                String storedHash = rs.getString("password_hash");
                String salt = rs.getString("password_salt");
                int iterations = rs.getInt("password_iterations");

                boolean valid = PasswordHasher.verifyPassword(
                        password,
                        salt,
                        storedHash,
                        iterations);

                if (!valid) {
                    return null;
                }

                User user = new User(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("department"),
                        rs.getString("role"),
                        rs.getString("borrower_type"),
                        rs.getBoolean("active"));
                user.setNotificationsEnabled(rs.getBoolean("notifications_enabled"));
                return user;
            }
        }
    }

    public void registerBorrower(
            String username,
            String email,
            String fullName,
            String department,
            String borrowerType,
            char[] password) throws Exception {

        if (username == null || username.isBlank()
                || email == null || email.isBlank()
                || fullName == null || fullName.isBlank()
                || password == null || password.length < 8) {

            throw new IllegalArgumentException(
                    "Please complete all required fields. Password must be at least 8 characters.");
        }

        if (!email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }

        if (!"STUDENT".equalsIgnoreCase(borrowerType)) {

            throw new IllegalArgumentException(
                    "Self-registration is for students. Faculty accounts are created by ICT.");
        }

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1")) {
            statement.setString(1, email.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException(
                            "An account already exists for this GSuite account.");
                }
            }
        }

        try {
            insertBorrower(username, email, fullName, department, "STUDENT", password);
        } catch (java.sql.SQLException exception) {
            if (Sql.isUniqueViolation(exception)) {
                throw new IllegalArgumentException(
                        "That username or email is already in use.");
            }
            throw exception;
        }
    }

    void insertBorrower(
            String username,
            String email,
            String fullName,
            String department,
            String borrowerType,
            char[] password) throws Exception {

        String salt = PasswordHasher.generateSalt();

        String passwordHash = PasswordHasher.hashPassword(password, salt);

        String sql = """
                INSERT INTO users (
                    username,
                    email,
                    password_hash,
                    password_salt,
                    password_iterations,
                    role,
                    borrower_type,
                    full_name,
                    department,
                    active
                )
                VALUES (?, ?, ?, ?, ?, 'BORROWER', ?, ?, ?, TRUE)
                """;

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username.trim());
            statement.setString(2, email.trim());
            statement.setString(3, passwordHash);
            statement.setString(4, salt);
            statement.setInt(5, PasswordHasher.getIterations());
            statement.setString(6, borrowerType.toUpperCase());
            statement.setString(7, fullName.trim());

            if (department == null || department.isBlank()) {
                statement.setNull(8, Types.VARCHAR);
            } else {
                statement.setString(8, department.trim());
            }

            statement.executeUpdate();
        }
    }

    public void changePassword(
            long userId,
            char[] currentPassword,
            char[] newPassword) throws Exception {

        if (currentPassword == null || currentPassword.length == 0
                || newPassword == null || newPassword.length < 8) {
            throw new IllegalArgumentException(
                    "The new password must be at least 8 characters.");
        }

        String selectSql = """
                SELECT password_hash, password_salt, password_iterations
                FROM users
                WHERE user_id = ?
                """;

        try (Connection connection = Database.getConnection()) {
            String storedHash;
            String salt;
            int iterations;

            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setLong(1, userId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("User account was not found.");
                    }
                    storedHash = rs.getString("password_hash");
                    salt = rs.getString("password_salt");
                    iterations = rs.getInt("password_iterations");
                }
            }

            if (!PasswordHasher.verifyPassword(
                    currentPassword, salt, storedHash, iterations)) {
                throw new IllegalArgumentException("The current password is incorrect.");
            }

            String newSalt = PasswordHasher.generateSalt();
            String newHash = PasswordHasher.hashPassword(newPassword, newSalt);
            String updateSql = """
                    UPDATE users
                    SET password_hash = ?,
                        password_salt = ?,
                        password_iterations = ?,
                        must_change_password = FALSE
                    WHERE user_id = ?
                    """;

            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setString(1, newHash);
                statement.setString(2, newSalt);
                statement.setInt(3, PasswordHasher.getIterations());
                statement.setLong(4, userId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Password update did not complete.");
                }
            }
        }
    }

    public void updateNotificationPreference(long userId, boolean enabled) throws Exception {
        if (userId <= 0) {
            throw new IllegalArgumentException("A signed-in account is required.");
        }
        String sql = "UPDATE users SET notifications_enabled = ? WHERE user_id = ?";
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, enabled);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Notification preference could not be saved.");
            }
        }
    }

    public void requestPasswordReset(String usernameOrEmail, String registeredEmail) throws Exception {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            throw new IllegalArgumentException("Enter your username or email.");
        }

        boolean emailProvided = registeredEmail != null && !registeredEmail.isBlank();
        if (!emailProvided) {
            throw new IllegalArgumentException(
                    "Enter the email registered to your account for identity verification.");
        }

        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Long userId = null;
                boolean emailMatches = false;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT user_id, email
                        FROM users
                        WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)
                        """)) {
                    String identity = usernameOrEmail.trim();
                    statement.setString(1, identity);
                    statement.setString(2, identity);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            userId = result.getLong("user_id");
                            emailMatches = result.getString("email") != null
                                    && result.getString("email").trim().equalsIgnoreCase(registeredEmail.trim());
                        }
                    }
                }

                if (!emailMatches) {
                    throw new IllegalArgumentException(
                            "The registered email does not match this account.");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT reset_request_id
                        FROM password_reset_requests
                        WHERE user_id = ? AND status IN ('PENDING', 'APPROVED')
                          AND registered_email_match = TRUE AND identity_verified = TRUE
                        """)) {
                    statement.setLong(1, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            try (PreparedStatement insert = connection.prepareStatement("""
                                    INSERT INTO password_reset_requests
                                    (user_id, registered_email_match, identity_verified)
                                    VALUES (?, TRUE, TRUE)
                                    """)) {
                                insert.setLong(1, userId);
                                insert.executeUpdate();
                            }
                        }
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
