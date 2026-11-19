package src.service;

import src.database.Database;
import src.database.Sql;
import src.model.BorrowRequest;
import src.model.BorrowRequestForm;
import src.model.DepartmentStatus;
import src.model.EquipmentRequestItem;
import src.model.ItemInspection;
import src.model.RequestOccurrence;
import src.model.RequestStatus;
import src.util.UndoPolicy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Part 1 of BorrowRequestService, split out to keep files short. */
public abstract class BorrowRequestServicePart1 {

    static final String[] EDITABLE_STATUSES = {
            "PENDING_STAFF", "NEEDS_ACTION"
    };
    static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    static void requireIctRole(String actorRole) {
        if (!"ICT_STAFF".equalsIgnoreCase(actorRole == null ? "" : actorRole.trim())) {
            throw new SecurityException("Only the ICT System Admin can delete requests.");
        }
    }

    public int countQueueForRole(String role) throws SQLException {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        String sql;
        if ("ICT_STAFF".equals(normalizedRole)
                || "FACILITY_STAFF".equals(normalizedRole)) {
            sql = """
                    SELECT COUNT(DISTINCT br.request_id)
                    FROM borrow_requests br
                    JOIN request_groups rg ON rg.request_id = br.request_id
                    WHERE br.deleted_at IS NULL
                      AND br.status = 'PENDING_STAFF'
                      AND rg.custodian_role = ?
                      AND rg.status = 'PENDING'
                    """;
        } else if ("ADMIN".equals(normalizedRole)) {
            sql = "SELECT COUNT(*) FROM borrow_requests "
                    + "WHERE deleted_at IS NULL AND status = 'PENDING_ADMIN'";
        } else if ("DIRECTOR".equals(normalizedRole)) {
            sql = "SELECT COUNT(*) FROM borrow_requests "
                    + "WHERE deleted_at IS NULL AND status = 'PENDING_DIRECTOR'";
        } else {
            sql = """
                    SELECT COUNT(*) FROM borrow_requests
                    WHERE deleted_at IS NULL
                      AND status IN ('PENDING_STAFF', 'NEEDS_ACTION',
                                     'PENDING_ADMIN', 'PENDING_DIRECTOR')
                    """;
        }

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if ("ICT_STAFF".equals(normalizedRole)
                    || "FACILITY_STAFF".equals(normalizedRole)) {
                statement.setString(1, normalizedRole);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public int countByStatus(String status) throws SQLException {
        Set<String> allowedStatuses = Set.of(
                "PENDING_STAFF", "NEEDS_ACTION", "PENDING_ADMIN",
                "PENDING_DIRECTOR", "APPROVED", "RELEASED", "COMPLETED",
                "REJECTED", "CANCELLED");
        if (status == null || !allowedStatuses.contains(status)) {
            throw new IllegalArgumentException("Select a valid request status.");
        }
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM borrow_requests "
                                + "WHERE deleted_at IS NULL AND status = ?")) {
            statement.setString(1, status);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** Orders faculty (priority) requests first, keeping the existing order otherwise. */
    static List<BorrowRequest> prioritized(List<BorrowRequest> requests) {
        requests.sort(BorrowRequest.PRIORITY_FIRST);
        return requests;
    }

    /** One department group row with its schedule (occurrence or request-level). */
    static final class GroupRow {
        final long groupId;
        final String status;
        final LocalDate eventDate;
        final java.time.LocalTime startTime;
        final java.time.LocalTime endTime;

        GroupRow(long groupId, String status, LocalDate eventDate,
                java.time.LocalTime startTime, java.time.LocalTime endTime) {
            this.groupId = groupId;
            this.status = status;
            this.eventDate = eventDate;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    /** Request stage plus the two stored review decisions used to gate release. */
    static final class ApprovalState {
        final String status;
        final String adminStatus;
        final String directorStatus;

        ApprovalState(String status, String adminStatus, String directorStatus) {
            this.status = status;
            this.adminStatus = adminStatus;
            this.directorStatus = directorStatus;
        }
    }

    /** Locks the request row so concurrent department actions are serialized. */
    String lockRequestStatus(Connection connection, long requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM borrow_requests "
                        + "WHERE request_id = ? AND deleted_at IS NULL")) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Request was not found.");
                }
                return result.getString(1);
            }
        }
    }

    /** Locks a request and returns its stage together with both review decisions. */
    ApprovalState lockApprovalState(
            Connection connection, long requestId, Long borrowerId) throws SQLException {
        String sql = "SELECT status, admin_status, director_status FROM borrow_requests "
                + "WHERE request_id = ? AND deleted_at IS NULL";
        if (borrowerId != null) {
            sql += " AND borrower_id = ?";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requestId);
            if (borrowerId != null) {
                statement.setLong(2, borrowerId);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Request was not found.");
                }
                return new ApprovalState(
                        result.getString("status"),
                        result.getString("admin_status"),
                        result.getString("director_status"));
            }
        }
    }

    List<GroupRow> lockBorrowerGroups(Connection connection, long requestId) throws SQLException {
        List<GroupRow> groups = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rg.group_id, rg.status, COALESCE(ro.event_date, br.event_date) AS event_date, "
                        + "COALESCE(ro.start_time, br.start_time) AS start_time, COALESCE(ro.end_time, br.end_time) AS end_time "
                        + "FROM request_groups rg JOIN borrow_requests br ON br.request_id=rg.request_id "
                        + "LEFT JOIN request_occurrences ro ON ro.occurrence_id=rg.occurrence_id "
                        + "WHERE rg.request_id=? ORDER BY 3,4,rg.group_id")) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    groups.add(new GroupRow(result.getLong("group_id"), result.getString("status"),
                            Sql.getDate(result, "event_date").toLocalDate(),
                            Sql.getTime(result, "start_time").toLocalTime(),
                            Sql.getTime(result, "end_time").toLocalTime()));
                }
            }
        }
        return groups;
    }

    /**
     * Locks a borrower's own request for deletion. Unlike the edit lock, this accepts
     * every status — completed requests are deletable too — but never a deleted row.
     */
    String lockOwnRequestForDeletion(
            Connection connection, long borrowerId, long requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status
                FROM borrow_requests
                WHERE request_id = ? AND borrower_id = ? AND deleted_at IS NULL
                """)) {
            statement.setLong(1, requestId);
            statement.setLong(2, borrowerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Request was not found.");
                }
                return result.getString("status");
            }
        }
    }

    void touchRequest(Connection connection, long requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE borrow_requests SET updated_at = datetime('now', '+8 hours') WHERE request_id = ?")) {
            statement.setLong(1, requestId);
            statement.executeUpdate();
        }
    }

    /** Locks the department's own groups, earliest schedule first. */
    List<GroupRow> lockRoleGroups(Connection connection, long requestId, String role)
            throws SQLException {
        List<GroupRow> groups = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rg.group_id, rg.status,
                       COALESCE(ro.event_date, br.event_date) AS event_date,
                       COALESCE(ro.start_time, br.start_time) AS start_time,
                       COALESCE(ro.end_time, br.end_time) AS end_time
                FROM request_groups rg
                JOIN borrow_requests br ON br.request_id = rg.request_id
                LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                WHERE rg.request_id = ? AND rg.custodian_role = ?
                ORDER BY 3, 4, rg.group_id
                """)) {
            statement.setLong(1, requestId);
            statement.setString(2, role);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    groups.add(new GroupRow(
                            result.getLong("group_id"),
                            result.getString("status"),
                            Sql.getDate(result, "event_date").toLocalDate(),
                            Sql.getTime(result, "start_time").toLocalTime(),
                            Sql.getTime(result, "end_time").toLocalTime()));
                }
            }
        }
        return groups;
    }

    Map<Long, Integer> lockGroupItems(Connection connection, long groupId)
            throws SQLException {
        Map<Long, Integer> requested = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT requested_equipment_id, requested_quantity
                FROM request_items
                WHERE group_id = ?
                """)) {
            statement.setLong(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    requested.put(result.getLong(1), result.getInt(2));
                }
            }
        }
        return requested;
    }

    static void requireStaffReview(String requestStatus) {
        if (!"PENDING_STAFF".equals(requestStatus) && !"NEEDS_ACTION".equals(requestStatus)) {
            throw new IllegalArgumentException(
                    "This request is no longer waiting for department review.");
        }
    }

    static List<GroupRow> processablePendingGroups(List<GroupRow> groups) {
        if (groups.isEmpty()) {
            throw new SecurityException(
                    "This request has no equipment assigned to your department.");
        }
        List<GroupRow> pending = new ArrayList<>();
        for (GroupRow group : groups) {
            if ("PENDING".equals(group.status)) {
                pending.add(group);
            }
        }
        if (pending.isEmpty()) {
            throw new IllegalArgumentException(
                    "Your department already processed this request.");
        }
        return pending;
    }

    /** First group in the given state, ordered by the query's schedule/ID sort. */
    static GroupRow firstWithStatus(List<GroupRow> groups, String status) {
        for (GroupRow group : groups) {
            if (status.equals(group.status)) {
                return group;
            }
        }
        return null;
    }

    static String releaseBlockedReason(List<GroupRow> groups) {
        boolean pending = false, declined = false, released = false, cancelled = false;
        for (GroupRow group : groups) {
            switch (group.status) {
                case "PENDING", "UNABLE" -> pending = true;
                case "DECLINED" -> declined = true;
                case "RELEASED", "RETURNED" -> released = true;
                case "CANCELLED", "NOT_REQUIRED" -> cancelled = true;
                default -> { }
            }
        }
        if (pending) return "Your department's equipment is still pending approval.";
        if (cancelled && !released && !declined) {
            return "This request was declined or cancelled, so nothing can be released.";
        }
        if (declined && !released) return "Your department declined this equipment, so it cannot be released.";
        if (released) return "Your department's equipment was already released.";
        return "This equipment cannot be released.";
    }

    static String stageBlockedReason(String requestStatus) {
        return switch (requestStatus) {
            case "PENDING_STAFF", "NEEDS_ACTION", "PENDING_ADMIN" ->
                    "The request is still waiting for Facility Admin approval.";
            case "PENDING_DIRECTOR" ->
                    "The request is still waiting for Campus Director approval.";
            case "REJECTED" -> "This request was declined and cannot be released.";
            case "CANCELLED" -> "This request was cancelled.";
            default -> "Only approved equipment can be released.";
        };
    }
}
