package src.service;

import src.database.Database;
import src.model.PasswordResetRequest;
import src.model.User;
import src.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class AuthService extends AuthServicePart2 {

            public void reviewPasswordReset(
                    String actorRole,
                    long actorId,
                    long requestId,
                    boolean approve,
                    String note) throws Exception {
                requirePasswordResetReviewerRole(actorRole);
                if (requestId <= 0) {
                    throw new IllegalArgumentException("A valid reset request is required.");
                }

                String status = approve ? "APPROVED" : "REJECTED";
                try (Connection connection = Database.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        long targetUserId;
                        String targetRole;
                        boolean emailMatched;
                        boolean identityVerified;
                        try (PreparedStatement statement = connection.prepareStatement("""
                                SELECT pr.user_id, u.role, pr.registered_email_match, pr.identity_verified
                                FROM password_reset_requests pr
                                JOIN users u ON u.user_id = pr.user_id
                                WHERE pr.reset_request_id = ? AND pr.status = 'PENDING'
                                """)) {
                            statement.setLong(1, requestId);
                            try (ResultSet result = statement.executeQuery()) {
                                if (!result.next()) {
                                    throw new IllegalArgumentException(
                                            "This password reset request was already reviewed.");
                                }
                                targetUserId = result.getLong("user_id");
                                targetRole = result.getString("role");
                                emailMatched = result.getBoolean("registered_email_match");
                                identityVerified = result.getBoolean("identity_verified");
                            }
                        }
                        requirePasswordResetReviewer(actorRole, actorId, targetRole, targetUserId);
                        if (approve && (!emailMatched || !identityVerified)) {
                            throw new SecurityException(
                                    "This password reset request is not identity verified.");
                        }

                        long userId;
                        try (PreparedStatement statement = connection.prepareStatement("""
                                UPDATE password_reset_requests
                                SET status = ?, reviewed_by = ?, reviewed_at = datetime('now', '+8 hours'),
                                    review_note = ?
                                WHERE reset_request_id = ? AND status = 'PENDING'
                                RETURNING user_id
                                """)) {
                            statement.setString(1, status);
                            statement.setLong(2, actorId);
                            statement.setString(3, note == null || note.isBlank() ? null : note.trim());
                            statement.setLong(4, requestId);
                            try (ResultSet result = statement.executeQuery()) {
                                if (!result.next()) {
                                    throw new IllegalArgumentException(
                                            "This password reset request was already reviewed.");
                                }
                                userId = result.getLong("user_id");
                            }
                        }

                        String title = approve
                                ? "Password reset approved"
                                : "Password reset request declined";
                        String message = approve
                                ? "An authorized reviewer approved your request. Open Forgot password on the login screen to set a new password."
                                : "Your password reset request was declined. Contact the system administrator for help.";
                        try (PreparedStatement statement = connection.prepareStatement("""
                                INSERT INTO notifications (user_id, title, message)
                                SELECT ?, ?, ?
                                WHERE EXISTS (
                                    SELECT 1 FROM users
                                    WHERE user_id = ? AND COALESCE(notifications_enabled, TRUE) = TRUE
                                )
                                """)) {
                            statement.setLong(1, userId);
                            statement.setString(2, title);
                            statement.setString(3, message);
                            statement.setLong(4, userId);
                            statement.executeUpdate();
                        }
                        connection.commit();
                    } catch (Exception exception) {
                        connection.rollback();
                        throw exception;
                    }
                }
            }
}
