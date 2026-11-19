package src.service;

import src.database.Database;
import src.database.Sql;
import src.model.PasswordResetRequest;
import src.model.User;
import src.sync.SyncService;
import src.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Part 2 of AuthService, split out to keep files short. */
public abstract class AuthServicePart2 extends AuthServicePart1 {

    /** ICT creates faculty accounts, which receive priority request handling. */
    public void createFacultyAccount(
            String actorRole,
            String username,
            String email,
            String fullName,
            String department,
            char[] password) throws Exception {
        requireIctStaff(actorRole);
        if (username == null || username.isBlank()
                || email == null || email.isBlank()
                || fullName == null || fullName.isBlank()
                || password == null || password.length < 8) {
            throw new IllegalArgumentException(
                    "Complete name, username, and email. Password must be at least 8 characters.");
        }
        if (!email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        try {
            insertBorrower(username, email, fullName, department, "FACULTY", password);
        } catch (java.sql.SQLException exception) {
            if (Sql.isUniqueViolation(exception)) {
                throw new IllegalArgumentException(
                        "That username or email is already in use.");
            }
            throw exception;
        }
        SyncService.get().syncSoon();
    }

    public void resetPasswordAfterApproval(
            String usernameOrEmail,
            String registeredEmail,
            char[] newPassword) throws Exception {
                if (usernameOrEmail == null || usernameOrEmail.isBlank()
                        || newPassword == null || newPassword.length < 8) {
                    throw new IllegalArgumentException(
                            "Enter your username or email and a password of at least 8 characters.");
                }

                try (Connection connection = Database.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        long userId;
                        try (PreparedStatement statement = connection.prepareStatement("""
                                SELECT user_id, email, role
                                FROM users
                                WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)
                                """)) {
                            String identity = usernameOrEmail.trim();
                            statement.setString(1, identity);
                            statement.setString(2, identity);
                            try (ResultSet result = statement.executeQuery()) {
                                if (!result.next()) {
                                    throw new IllegalArgumentException(
                                            "There is no approved password reset request for this account.");
                                }
                                if (registeredEmail == null || registeredEmail.isBlank()
                                        || result.getString("email") == null
                                        || !result.getString("email").trim().equalsIgnoreCase(registeredEmail.trim())) {
                                    throw new IllegalArgumentException(
                                            "The registered email does not match this account.");
                                }
                                userId = result.getLong("user_id");
                            }
                        }

                        long requestId;
                        try (PreparedStatement statement = connection.prepareStatement("""
                                SELECT reset_request_id
                                FROM password_reset_requests
                                WHERE user_id = ? AND status = 'APPROVED'
                                  AND registered_email_match = TRUE AND identity_verified = TRUE
                                ORDER BY requested_at DESC
                                LIMIT 1
                                """)) {
                            statement.setLong(1, userId);
                            try (ResultSet result = statement.executeQuery()) {
                                if (!result.next()) {
                                    throw new IllegalArgumentException(
                                            "There is no verified, approved password reset request for this account.");
                                }
                                requestId = result.getLong("reset_request_id");
                            }
                        }

                        String salt = PasswordHasher.generateSalt();
                        String hash = PasswordHasher.hashPassword(newPassword, salt);
                        try (PreparedStatement statement = connection.prepareStatement("""
                                UPDATE users
                                SET password_hash = ?, password_salt = ?, password_iterations = ?
                                WHERE user_id = ?
                                """)) {
                            statement.setString(1, hash);
                            statement.setString(2, salt);
                            statement.setInt(3, PasswordHasher.getIterations());
                            statement.setLong(4, userId);
                            if (statement.executeUpdate() != 1) {
                                throw new IllegalStateException("Password update did not complete.");
                            }
                        }

                        try (PreparedStatement statement = connection.prepareStatement("""
                                UPDATE password_reset_requests
                                SET status = 'COMPLETED'
                                WHERE reset_request_id = ? AND status = 'APPROVED'
                                """)) {
                            statement.setLong(1, requestId);
                            if (statement.executeUpdate() != 1) {
                                throw new IllegalStateException(
                                        "The approved password reset could not be completed.");
                            }
                        }
                        connection.commit();
                    } catch (Exception exception) {
                        connection.rollback();
                        throw exception;
                    }
                }
            }

            public List<PasswordResetRequest> findPasswordResetRequests(String actorRole)
                    throws Exception {
                requirePasswordResetReviewerRole(actorRole);
                String sql = """
                        SELECT pr.reset_request_id, u.user_id, u.username, u.full_name,
                               u.email, u.role, u.department, pr.requested_at,
                               pr.registered_email_match, pr.identity_verified
                        FROM password_reset_requests pr
                        JOIN users u ON u.user_id = pr.user_id
                        WHERE pr.status = 'PENDING'
                        ORDER BY pr.requested_at ASC, pr.reset_request_id ASC
                        """;
                List<PasswordResetRequest> requests = new ArrayList<>();
                try (Connection connection = Database.getConnection();
                        PreparedStatement statement = connection.prepareStatement(sql);
                        ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        requests.add(new PasswordResetRequest(
                                result.getLong("reset_request_id"),
                                result.getLong("user_id"),
                                result.getString("username"),
                                result.getString("full_name"),
                                result.getString("email"),
                                result.getString("role"),
                                result.getString("department"),
                                Sql.getTimestamp(result, "requested_at").toLocalDateTime(),
                                result.getBoolean("registered_email_match"),
                                result.getBoolean("identity_verified")));
                    }
                }
                return requests;
            }

            public List<User> findBorrowersForIct(String actorRole) throws Exception {
                requireIctStaff(actorRole);
                List<User> users = new ArrayList<>();
                try (Connection connection = Database.getConnection();
                        PreparedStatement statement = connection.prepareStatement("""
                                SELECT user_id, username, email, full_name, department,
                                       role, borrower_type, active
                                FROM users
                                WHERE role = 'BORROWER'
                                ORDER BY borrower_type, department NULLS LAST, full_name
                                """);
                        ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        users.add(new User(
                                result.getLong("user_id"),
                                result.getString("username"),
                                result.getString("email"),
                                result.getString("full_name"),
                                result.getString("department"),
                                result.getString("role"),
                                result.getString("borrower_type"),
                                result.getBoolean("active")));
                    }
                }
                return users;
            }

            public void deleteBorrowerAccount(String actorRole, long userId)
                    throws Exception {
                requireIctStaff(actorRole);
                if (userId <= 0) {
                    throw new IllegalArgumentException("Invalid borrower account.");
                }

                try (Connection connection = Database.getConnection();
                        PreparedStatement statement = connection.prepareStatement(
                                "DELETE FROM users WHERE user_id = ? AND role = 'BORROWER'")) {
                    statement.setLong(1, userId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalArgumentException(
                                "This borrower account no longer exists.");
                    }
                    SyncService.get().syncSoon();
                } catch (java.sql.SQLException exception) {
                    if (Sql.isForeignKeyViolation(exception)) {
                        throw new IllegalStateException(
                                "This account is linked to existing requests or records and "
                                        + "cannot be deleted without removing that history.",
                                exception);
                    }
                    throw exception;
                }
            }

            void requireIctStaff(String actorRole) {
                if (!"ICT_STAFF".equalsIgnoreCase(actorRole == null ? "" : actorRole.trim())) {
                    throw new SecurityException("Only ICT system administrators can manage this.");
                }
            }

            void requirePasswordResetReviewerRole(String actorRole) {
                String role = actorRole == null ? "" : actorRole.trim().toUpperCase();
                if (!role.equals("ICT_STAFF") && !role.equals("ADMIN") && !role.equals("DIRECTOR")) {
                    throw new SecurityException("Only authorized administrators can review password requests.");
                }
            }

            void requirePasswordResetReviewer(
                    String actorRole, long actorId, String targetRole, long targetId) {
                requirePasswordResetReviewerRole(actorRole);
                String role = actorRole.trim().toUpperCase();
                String target = targetRole == null ? "" : targetRole.trim().toUpperCase();
                if (actorId == targetId) {
                    throw new SecurityException("You cannot review your own password reset request.");
                }
                if (isElevatedRole(target) && !isFacilityOrDirector(role)) {
                    throw new SecurityException(
                            "Password recovery for elevated accounts requires a Facility or Campus Director reviewer.");
                }
            }

            boolean isFacilityOrDirector(String role) {
                String value = role == null ? "" : role.trim().toUpperCase();
                return value.equals("ADMIN") || value.equals("DIRECTOR");
            }

            boolean isElevatedRole(String role) {
                String value = role == null ? "" : role.trim().toUpperCase();
                return value.equals("ICT_STAFF") || value.equals("ADMIN") || value.equals("DIRECTOR");
            }
}
