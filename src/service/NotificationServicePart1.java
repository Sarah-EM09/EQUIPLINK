package src.service;

import src.database.Database;
import src.database.Sql;
import src.model.Notification;
import src.model.RequestStatus;
import src.util.UndoPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Part 1 of NotificationService, split out to keep files short. */
public abstract class NotificationServicePart1 {

    public int countUnread(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM notifications
                        WHERE user_id = ? AND is_read = FALSE AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public void markRead(long userId, long notificationId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET is_read = TRUE, read_at = COALESCE(read_at, datetime('now', '+8 hours'))
                        WHERE notification_id = ? AND user_id = ? AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Notification was not found.");
            }
        }
    }

    public void markAllRead(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET is_read = TRUE, read_at = COALESCE(read_at, datetime('now', '+8 hours'))
                        WHERE user_id = ? AND is_read = FALSE AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    /**
     * Removes one notification from this user's panel. Only the user's own
     * notification row is touched — requests, approvals, releases, and history stay.
     * The row is soft-deleted so reminders already sent are not generated again.
     */
    public void delete(long userId, long notificationId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET read_before_delete = is_read,
                            is_deleted = TRUE,
                            deleted_at = datetime('now', '+8 hours'),
                            is_read = TRUE
                        WHERE notification_id = ? AND user_id = ? AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Notification was not found.");
            }
        }
    }

    /** Removes every notification from this user's panel; returns how many. */
    public int deleteAll(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET read_before_delete = is_read,
                            is_deleted = TRUE,
                            deleted_at = datetime('now', '+8 hours'),
                            is_read = TRUE
                        WHERE user_id = ? AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, userId);
            return statement.executeUpdate();
        }
    }

    /**
     * Brings one deleted notification back to the user's list. Restore is only allowed
     * while the shared undo window is open; the original read/unread state is restored.
     */
    public void restore(long userId, long notificationId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET is_deleted = FALSE,
                            deleted_at = NULL,
                            is_read = COALESCE(read_before_delete, FALSE),
                            read_before_delete = NULL
                        WHERE notification_id = ? AND user_id = ? AND is_deleted = TRUE
                          AND deleted_at >= """
                        + UndoPolicy.RECOVERY_SQL + """
                         """)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException(
                        "The " + UndoPolicy.RECOVERY_LABEL
                                + " recovery period has ended or the notification no longer exists.");
            }
        }
    }

    /** Restores several notifications deleted together (Undo for "Delete all"). */
    public int restoreAll(long userId, java.util.Collection<Long> notificationIds)
            throws SQLException {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("""
                UPDATE notifications
                SET is_deleted = FALSE,
                    deleted_at = NULL,
                    is_read = COALESCE(read_before_delete, FALSE),
                    read_before_delete = NULL
                WHERE user_id = ? AND is_deleted = TRUE
                  AND deleted_at >= """
                + UndoPolicy.RECOVERY_SQL + " AND notification_id IN (");
        for (int i = 0; i < notificationIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(')');
        int restored = 0;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setLong(1, userId);
            int index = 2;
            for (Long id : notificationIds) {
                statement.setLong(index++, id == null ? 0L : id);
            }
            restored = statement.executeUpdate();
        }
        if (restored == 0) {
            throw new IllegalArgumentException(
                    "The " + UndoPolicy.RECOVERY_LABEL + " recovery period has ended.");
        }
        return restored;
    }

    /**
     * Cheap fingerprint of request activity plus this user's notifications. Screens
     * poll it and reload only when it changes, keeping every dashboard in sync.
     */
    public String changeToken(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT (SELECT COALESCE(MAX(history_id), 0) FROM request_history),
                               (SELECT COALESCE(MAX(updated_at), '1970-01-01 00:00:00')
                                  FROM borrow_requests),
                               (SELECT COUNT(*) FROM borrow_requests),
                               (SELECT COALESCE(MAX(notification_id), 0) FROM notifications
                                 WHERE user_id = ?),
                               (SELECT COUNT(*) FROM notifications
                                 WHERE user_id = ? AND is_deleted = FALSE AND is_read = FALSE),
                               (SELECT COUNT(*) FROM notifications
                                 WHERE user_id = ? AND is_deleted = TRUE)
                        """)) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            statement.setLong(3, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) + "|" + Sql.getTimestamp(result, 2) + "|"
                        + result.getLong(3) + "|" + result.getLong(4) + "|"
                        + result.getLong(5) + "|" + result.getLong(6);
            }
        }
    }

    void notifyUsers(Connection connection, String predicate, String value,
            long requestId, String title, String message, String department, String action)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO notifications (user_id, request_id, title, message, department, action) "
                        + "SELECT user_id, ?, ?, ?, ?, ? FROM users WHERE active = TRUE "
                        + "AND COALESCE(notifications_enabled, TRUE) = TRUE AND "
                        + predicate)) {
            statement.setLong(1, requestId);
            statement.setString(2, title);
            statement.setString(3, message);
            statement.setString(4, department);
            statement.setString(5, action);
            statement.setString(6, value);
            statement.executeUpdate();
        }
    }

    void notifyBorrower(Connection connection, long requestId, String title,
            String message, String department) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message, department, action)
                SELECT br.borrower_id, br.request_id, ?, ?, ?, 'OVERDUE'
                FROM borrow_requests br
                JOIN users u ON u.user_id = br.borrower_id
                WHERE br.request_id = ?
                  AND COALESCE(u.notifications_enabled, TRUE) = TRUE
                """)) {
            statement.setString(1, title);
            statement.setString(2, message);
            statement.setString(3, department);
            statement.setLong(4, requestId);
            statement.executeUpdate();
        }
    }
}
