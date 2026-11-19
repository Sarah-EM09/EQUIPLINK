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

/** Part 2 of NotificationService, split out to keep files short. */
public abstract class NotificationServicePart2 extends NotificationServicePart1 {

    /**
     * Flags work that was not processed before its schedule and notifies the
     * responsible people once. Safe to call from several clients: each overdue row
     * is claimed with an atomic UPDATE ... RETURNING inside one write transaction.
     */
    public void runOverdueSweep() throws SQLException {
        Timestamp now = Timestamp.valueOf(
                LocalDateTime.now(ZoneId.of("Asia/Manila")));
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 1. A department never approved or declined before the schedule started.
                Map<Long, Set<String>> notAssigned = new HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups AS rg
                        SET overdue_notified = TRUE
                        WHERE rg.status = 'PENDING' AND rg.overdue_notified = FALSE
                          AND EXISTS (
                              SELECT 1 FROM borrow_requests br
                              WHERE br.request_id = rg.request_id AND br.status = 'PENDING_STAFF'
                          )
                          AND (
                              SELECT COALESCE(ro.event_date, br.event_date) || ' '
                                     || COALESCE(ro.start_time, br.start_time)
                              FROM borrow_requests br
                              LEFT JOIN request_occurrences ro
                                     ON ro.occurrence_id = rg.occurrence_id
                              WHERE br.request_id = rg.request_id
                          ) < ?
                        RETURNING request_id, custodian_role
                        """)) {
                    Sql.setTimestamp(statement, 1, now);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            notAssigned.computeIfAbsent(result.getLong(1), k -> new HashSet<>())
                                    .add(result.getString(2));
                        }
                    }
                }
                for (Map.Entry<Long, Set<String>> entry : notAssigned.entrySet()) {
                    for (String role : entry.getValue()) {
                        String department = RequestStatus.departmentLabel(role);
                        notifyUsers(connection, "role = ?", role, entry.getKey(),
                                "Not assigned on time",
                                "Your department did not approve or decline its equipment before the scheduled date.",
                                role, "OVERDUE");
                        notifyUsers(connection, "role = ?", "ADMIN", entry.getKey(),
                                department + " request not assigned",
                                department + " staff did not process their equipment before the scheduled date.",
                                role, "OVERDUE");
                        notifyBorrower(connection, entry.getKey(),
                                "Request not processed on time",
                                department + " staff did not process your equipment before the scheduled date.",
                                role);
                    }
                }

                // 2. Admin or director review did not happen before the schedule started.
                Map<Long, String> lateReview = new HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE borrow_requests AS br
                        SET overdue_notified = TRUE
                        WHERE br.status IN ('PENDING_ADMIN', 'PENDING_DIRECTOR')
                          AND br.overdue_notified = FALSE
                          AND MIN(br.event_date || ' ' || br.start_time, COALESCE((
                                  SELECT MIN(ro.event_date || ' ' || ro.start_time)
                                  FROM request_occurrences ro
                                  WHERE ro.request_id = br.request_id
                              ), br.event_date || ' ' || br.start_time)) < ?
                        RETURNING request_id, status
                        """)) {
                    Sql.setTimestamp(statement, 1, now);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            lateReview.put(result.getLong(1), result.getString(2));
                        }
                    }
                }
                for (Map.Entry<Long, String> entry : lateReview.entrySet()) {
                    String reviewer = "PENDING_ADMIN".equals(entry.getValue()) ? "ADMIN" : "DIRECTOR";
                    notifyUsers(connection, "role = ?", reviewer, entry.getKey(),
                            "Review not processed on time",
                            "This request was not reviewed before its scheduled date.",
                            null, "OVERDUE");
                    notifyBorrower(connection, entry.getKey(),
                            "Request not processed on time",
                            "Your request was not reviewed before its scheduled date.", null);
                }

                // 3. Approved equipment was never released before the schedule ended.
                Map<Long, Set<String>> notReleased = new HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups AS rg
                        SET overdue_notified = TRUE
                        WHERE rg.status = 'ASSIGNED' AND rg.overdue_notified = FALSE
                          AND EXISTS (
                              SELECT 1 FROM borrow_requests br
                              WHERE br.request_id = rg.request_id
                                AND br.status IN ('APPROVED', 'RELEASED')
                          )
                          AND (
                              SELECT COALESCE(ro.event_date, br.event_date) || ' '
                                     || COALESCE(ro.end_time, br.end_time)
                              FROM borrow_requests br
                              LEFT JOIN request_occurrences ro
                                     ON ro.occurrence_id = rg.occurrence_id
                              WHERE br.request_id = rg.request_id
                          ) < ?
                        RETURNING request_id, custodian_role
                        """)) {
                    Sql.setTimestamp(statement, 1, now);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            notReleased.computeIfAbsent(result.getLong(1), k -> new HashSet<>())
                                    .add(result.getString(2));
                        }
                    }
                }
                for (Map.Entry<Long, Set<String>> entry : notReleased.entrySet()) {
                    for (String role : entry.getValue()) {
                        String department = RequestStatus.departmentLabel(role);
                        notifyUsers(connection, "role = ?", role, entry.getKey(),
                                "Not released on time",
                                "Approved equipment from your department was not released before the schedule ended.",
                                role, "OVERDUE");
                        notifyUsers(connection, "role = ?", "ADMIN", entry.getKey(),
                                department + " release not processed",
                                department + " staff did not release approved equipment before the schedule ended.",
                                role, "OVERDUE");
                    }
                }

                // 4. Released equipment needs inspection once the usage period ends.
                Map<Long, Set<String>> awaitingInspection = new HashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups AS rg
                        SET return_notified = TRUE
                        WHERE rg.status = 'RELEASED' AND rg.return_notified = FALSE
                          AND (
                              SELECT COALESCE(ro.event_date, br.event_date) || ' '
                                     || COALESCE(ro.end_time, br.end_time)
                              FROM borrow_requests br
                              LEFT JOIN request_occurrences ro
                                     ON ro.occurrence_id = rg.occurrence_id
                              WHERE br.request_id = rg.request_id
                          ) < ?
                        RETURNING request_id, custodian_role
                        """)) {
                    Sql.setTimestamp(statement, 1, now);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            awaitingInspection.computeIfAbsent(
                                    result.getLong(1), k -> new HashSet<>())
                                    .add(result.getString(2));
                        }
                    }
                }
                for (Map.Entry<Long, Set<String>> entry : awaitingInspection.entrySet()) {
                    for (String role : entry.getValue()) {
                        String department = RequestStatus.departmentLabel(role);
                        notifyUsers(connection, "role = ?", role, entry.getKey(),
                                "Equipment ready for return inspection",
                                "The borrowing period ended. Inspect the returned "
                                        + department + " equipment and record its condition.",
                                role, "RETURN_INSPECTION");
                        notifyUsers(connection, "role = ?", "ADMIN", entry.getKey(),
                                department + " equipment awaiting inspection",
                                "The borrowing period ended and " + department
                                        + " staff need to inspect the returned equipment.",
                                role, "RETURN_INSPECTION");
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public boolean hasPendingReminderToday(long userId) throws SQLException {
        String sql = """
                SELECT 1
                FROM notifications
                WHERE user_id = ?
                  AND title = 'Pending request reminder'
                  AND date(created_at) = date('now', '+8 hours')
                LIMIT 1
                """;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public void createPendingReminder(long userId, int pendingCount)
            throws SQLException {
        String message = pendingCount == 1
                ? "You have 1 pending request waiting for your action."
                : "You have " + pendingCount
                        + " pending requests waiting for your action.";
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO notifications (user_id, title, message)
                        SELECT ?, 'Pending request reminder', ?
                        WHERE EXISTS (
                            SELECT 1 FROM users
                            WHERE user_id = ? AND COALESCE(notifications_enabled, TRUE) = TRUE
                        )
                        """)) {
            statement.setLong(1, userId);
            statement.setString(2, message);
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    /** Notifies faculty borrowers before each upcoming scheduled equipment release (Asia/Manila). */
    public void createUpcomingReleaseReminders(long userId) throws SQLException {
        String sql = """
                SELECT ro.occurrence_id, br.request_id, br.request_code, br.borrower_id,
                       ro.event_date, ro.start_time, ro.venue
                FROM request_occurrences ro
                JOIN borrow_requests br ON br.request_id = ro.request_id
                JOIN users u ON u.user_id = br.borrower_id
                WHERE br.borrower_id = ?
                  AND u.borrower_type = 'FACULTY'
                  AND COALESCE(u.notifications_enabled, TRUE) = TRUE
                  AND br.status IN ('APPROVED', 'RELEASED')
                  AND COALESCE(ro.release_notified, FALSE) = FALSE
                  AND (ro.event_date || ' ' || ro.start_time)
                      <= datetime('now', '+8 hours', '+1 day')
                  AND (ro.event_date || ' ' || ro.start_time)
                      >= datetime('now', '+8 hours', '-2 hours')
                """;
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long occId = rs.getLong("occurrence_id");
                        long reqId = rs.getLong("request_id");
                        String code = rs.getString("request_code");
                        String msg = "Reminder: equipment release for " + code + " is scheduled on "
                                + Sql.getDate(rs, "event_date").toLocalDate() + " "
                                + Sql.getTime(rs, "start_time").toLocalTime()
                                + " at " + rs.getString("venue") + ".";
                        try (PreparedStatement ins = connection.prepareStatement("""
                                INSERT INTO notifications (user_id, request_id, occurrence_id, title, message, action)
                                VALUES (?, ?, ?, 'Upcoming equipment release', ?, 'REMINDER')
                                """)) {
                            ins.setLong(1, userId);
                            ins.setLong(2, reqId);
                            ins.setLong(3, occId);
                            ins.setString(4, msg);
                            ins.executeUpdate();
                        }
                        try (PreparedStatement upd = connection.prepareStatement(
                                "UPDATE request_occurrences SET release_notified = TRUE WHERE occurrence_id = ?")) {
                            upd.setLong(1, occId);
                            upd.executeUpdate();
                        }
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            // ignore if schema not migrated yet
            if (e.getMessage() != null && e.getMessage().contains("release_notified")) return;
            throw e;
        }
    }
}
