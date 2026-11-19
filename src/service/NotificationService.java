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

public class NotificationService extends NotificationServicePart2 {

    public List<Notification> findForUser(long userId) throws SQLException {
        try { createUpcomingReleaseReminders(userId); } catch (Exception ignored) { }
        try { runOverdueSweep(); } catch (Exception ignored) { }
        String sql = """
                SELECT n.notification_id, n.user_id, n.request_id, n.title,
                       n.message, n.is_read, n.created_at, n.department, n.action,
                       br.event_date, br.request_code, br.status AS request_status,
                       COALESCE((SELECT group_concat(name, ', ') FROM (
                           SELECT DISTINCT e.equipment_name AS name
                           FROM request_items ri
                           JOIN equipment e ON e.equipment_id = COALESCE(
                                   ri.assigned_equipment_id, ri.requested_equipment_id)
                           WHERE ri.request_id = br.request_id
                           ORDER BY name)), '') AS equipment_names,
                       COALESCE((SELECT group_concat(name, ', ') FROM (
                           SELECT DISTINCT e.category AS name
                           FROM request_items ri
                           JOIN equipment e ON e.equipment_id = COALESCE(
                                   ri.assigned_equipment_id, ri.requested_equipment_id)
                           WHERE ri.request_id = br.request_id
                           ORDER BY name)), '') AS equipment_categories
                FROM notifications n
                LEFT JOIN borrow_requests br ON br.request_id = n.request_id
                WHERE n.user_id = ? AND n.is_deleted = FALSE
                ORDER BY n.created_at DESC, n.notification_id DESC
                LIMIT 200
                """;
        List<Notification> notifications = new ArrayList<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long requestId = result.getLong("request_id");
                    Long nullableRequestId = result.wasNull() ? null : requestId;
                    java.sql.Date eventDate = Sql.getDate(result, "event_date");
                    java.sql.Timestamp created = Sql.getTimestamp(result, "created_at");
                    notifications.add(new Notification(
                            result.getLong("notification_id"),
                            result.getLong("user_id"),
                            nullableRequestId,
                            result.getString("title"),
                            result.getString("message"),
                            result.getBoolean("is_read"),
                            created == null ? null : created.toLocalDateTime(),
                            result.getString("equipment_names"),
                            result.getString("equipment_categories"),
                            eventDate == null ? null : eventDate.toLocalDate())
                            .withDetails(
                                    result.getString("request_code"),
                                    result.getString("department"),
                                    result.getString("action"),
                                    result.getString("request_status")));
                }
            }
        }
        return notifications;
    }
}
