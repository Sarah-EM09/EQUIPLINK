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

public class BorrowRequestService extends BorrowRequestServicePart7 {

    public List<BorrowRequest> findActiveRequests(long borrowerId) throws SQLException {
        return findRequests(
                "br.borrower_id = ? AND br.status IN "
                        + "('PENDING_STAFF', 'NEEDS_ACTION', 'PENDING_ADMIN', "
                        + "'PENDING_DIRECTOR', 'APPROVED', 'RELEASED')",
                borrowerId);
    }

    public List<BorrowRequest> findBorrowingHistory(long borrowerId) throws SQLException {
        return findRequests(
                "br.borrower_id = ? AND br.status IN ('COMPLETED', 'REJECTED', 'CANCELLED')",
                borrowerId);
    }

    public BorrowRequest findRequest(long borrowerId, long requestId) throws SQLException {
        List<BorrowRequest> requests = findRequests(
                "br.borrower_id = ? AND br.request_id = ?",
                borrowerId, requestId);
        return requests.isEmpty() ? null : requests.get(0);
    }

    public List<BorrowRequest> findStaffQueue(String role) throws SQLException {
        String normalizedRole = requireCustodianRole(role);
        // NEEDS_ACTION requests wait on the borrower, so they leave every staff queue
        // until the borrower resubmits.
        return prioritized(findRequests("""
                br.status = 'PENDING_STAFF'
                AND EXISTS (
                    SELECT 1
                    FROM request_groups rg
                    WHERE rg.request_id = br.request_id
                      AND rg.custodian_role = ?
                      AND rg.status = 'PENDING'
                )
                """, normalizedRole));
    }

    public List<BorrowRequest> findAdminQueue() throws SQLException {
        return prioritized(findRequests("br.status = 'PENDING_ADMIN'"));
    }

    public List<BorrowRequest> findDirectorQueue() throws SQLException {
        return prioritized(findRequests("br.status = 'PENDING_DIRECTOR'"));
    }

    /**
     * Every request visible to the role. Admin and director see all requests; ICT and
     * facility staff only see requests containing equipment from their department.
     */
    public List<BorrowRequest> findAllRequests(String role) throws SQLException {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        if ("ICT_STAFF".equals(normalizedRole) || "FACILITY_STAFF".equals(normalizedRole)) {
            return findRequests("""
                    EXISTS (
                        SELECT 1 FROM request_groups rg
                        WHERE rg.request_id = br.request_id AND rg.custodian_role = ?
                    )
                    """, normalizedRole);
        }
        if ("ADMIN".equals(normalizedRole) || "DIRECTOR".equals(normalizedRole)) {
            return findRequests("br.request_id > 0");
        }
        throw new SecurityException("Your account is not allowed to view all requests.");
    }

    public List<BorrowRequest> findAllRequests() throws SQLException {
        return findRequests("br.request_id > 0");
    }

    /** Every request the borrower submitted, in any status. */
    public List<BorrowRequest> findAllForBorrower(long borrowerId) throws SQLException {
        return findRequests("br.borrower_id = ?", borrowerId);
    }

    /**
     * Loads one request if the account may see it: borrowers their own, staff requests
     * with their department's equipment, admin and director any request.
     */
    public BorrowRequest findRequestForUser(String role, long userId, long requestId)
            throws SQLException {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        List<BorrowRequest> requests;
        if ("BORROWER".equals(normalizedRole)) {
            requests = findRequests("br.borrower_id = ? AND br.request_id = ?", userId, requestId);
        } else if ("ICT_STAFF".equals(normalizedRole) || "FACILITY_STAFF".equals(normalizedRole)) {
            requests = findRequests("""
                    br.request_id = ? AND EXISTS (
                        SELECT 1 FROM request_groups rg
                        WHERE rg.request_id = br.request_id AND rg.custodian_role = ?
                    )
                    """, requestId, normalizedRole);
        } else if ("ADMIN".equals(normalizedRole) || "DIRECTOR".equals(normalizedRole)) {
            requests = findRequests("br.request_id = ?", requestId);
        } else {
            requests = List.of();
        }
        return requests.isEmpty() ? null : requests.get(0);
    }

    /**
     * Active hand-overs for the role: department-approved equipment on requests with
     * final approval (ready to release), and released equipment waiting to be returned.
     * Each department is listed independently of the other department's progress.
     */
    public List<BorrowRequest> findScheduleForRole(String role) throws SQLException {
        String normalizedRole = requireCustodianRole(role);
        return prioritized(findRequests("""
                EXISTS (
                    SELECT 1
                    FROM request_groups rg
                    WHERE rg.request_id = br.request_id
                      AND rg.custodian_role = ?
                      AND (
                          (rg.status = 'ASSIGNED' AND br.status IN ('APPROVED', 'RELEASED'))
                          OR rg.status IN ('RELEASED', 'RECEIVED')
                      )
                )
                """, normalizedRole));
    }

    /**
     * Transaction history for the role: every request containing this department's
     * equipment, in any status. Pending, approved, declined, overdue, released,
     * received, and completed records all live here; nothing is archived or deleted.
     */
    public List<BorrowRequest> findHistoryForRole(String role) throws SQLException {
        return findAllRequests(requireCustodianRole(role));
    }
    /**
     * Requests with activity on the given Manila day. Dashboard counters use this so
     * a new day starts with fresh counts; the underlying request and audit records
     * are never removed. History events cover submissions, decisions, reviews,
     * releases, receipt, and returns; overdue notices cover overdue counters.
     */
    public List<BorrowRequest> findDailyActivity(
            String role, long userId, LocalDate day) throws SQLException {
        if (day == null) return List.of();
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        List<BorrowRequest> visible;
        if ("BORROWER".equals(normalizedRole)) {
            visible = findAllForBorrower(userId);
        } else if ("ICT_STAFF".equals(normalizedRole)
                || "FACILITY_STAFF".equals(normalizedRole)
                || "ADMIN".equals(normalizedRole)
                || "DIRECTOR".equals(normalizedRole)) {
            visible = findAllRequests(normalizedRole);
        } else {
            return List.of();
        }
        if (visible.isEmpty()) return visible;

        LocalDate effectiveDay = day == null ? LocalDate.now(MANILA) : day;
        Set<Long> activeIds = new HashSet<>();
        try (Connection connection = Database.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT request_id FROM request_history
                    WHERE request_id IS NOT NULL AND date(created_at) = ?
                    """)) {
                Sql.setDate(statement, 1, effectiveDay);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        activeIds.add(result.getLong("request_id"));
                    }
                }
            }
            boolean scopedDepartment = "ICT_STAFF".equals(normalizedRole)
                    || "FACILITY_STAFF".equals(normalizedRole);
            String department = scopedDepartment ? normalizedRole : null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT request_id FROM notifications
                    WHERE request_id IS NOT NULL
                      AND date(created_at) = ?
                      AND action IN ('OVERDUE', 'RETURN_INSPECTION')
                      AND (? IS NULL OR department = ?)
                    """)) {
                Sql.setDate(statement, 1, effectiveDay);
                statement.setObject(2, department, Types.VARCHAR);
                statement.setObject(3, department, Types.VARCHAR);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        activeIds.add(result.getLong("request_id"));
                    }
                }
            }
        }
        List<BorrowRequest> daily = new ArrayList<>();
        for (BorrowRequest request : visible) {
            if (activeIds.contains(request.getRequestId())) {
                daily.add(request);
            }
        }
        return daily;
    }

    List<BorrowRequest> findRequests(String predicate, Object... parameters)
            throws SQLException {
        String sql = """
                SELECT br.request_id, br.request_code, br.borrower_id,
                       u.full_name AS borrower_name, u.borrower_type, br.venue, br.event_date,
                       br.start_time, br.end_time, br.purpose, br.participants,
                       br.person_in_charge, br.position, br.status, br.remarks,
                       br.admin_status, br.director_status, br.submitted_at,
                       e.equipment_id, e.equipment_code, e.equipment_name, e.category,
                       rg.custodian_role, rg.status AS group_status,
                       ri.requested_quantity, ri.assigned_quantity,
                       ri.released_quantity, ri.returned_quantity,
                       ri.damaged_quantity, ri.missing_quantity,
                       ri.condition_before, ri.condition_after
                FROM borrow_requests br
                JOIN users u ON u.user_id = br.borrower_id
                LEFT JOIN request_items ri ON ri.request_id = br.request_id
                LEFT JOIN request_groups rg ON rg.group_id = ri.group_id
                LEFT JOIN equipment e ON e.equipment_id = ri.requested_equipment_id
                WHERE br.deleted_at IS NULL AND (%s)
                ORDER BY br.submitted_at DESC, br.request_id DESC, e.equipment_name,
                         ri.request_item_id
                """.formatted(predicate);

        Map<Long, BorrowRequestBuilder> builders = new LinkedHashMap<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong("request_id");
                    BorrowRequestBuilder builder = builders.get(id);
                    if (builder == null) {
                        builder = new BorrowRequestBuilder(result);
                        builders.put(id, builder);
                    }
                    if (result.getObject("equipment_id") != null) {
                        builder.addItem(new EquipmentRequestItem(
                                result.getLong("equipment_id"),
                                result.getString("equipment_code"),
                                result.getString("equipment_name"),
                                result.getString("category"),
                                result.getInt("requested_quantity"),
                                result.getInt("assigned_quantity"),
                                result.getInt("released_quantity"),
                                result.getInt("returned_quantity"),
                                result.getString("custodian_role"),
                                result.getString("group_status"),
                                result.getInt("damaged_quantity"),
                                result.getInt("missing_quantity"),
                                result.getString("condition_before"),
                                result.getString("condition_after")));
                    }
                }
            }
            if (!builders.isEmpty()) {
                loadOccurrences(connection, builders);
                loadDepartments(connection, builders);
            }
        }

        List<BorrowRequest> requests = new ArrayList<>();
        for (BorrowRequestBuilder builder : builders.values()) {
            requests.add(builder.build());
        }
        return requests;
    }

    /** Loads each department's decision, remarks, and release records. */
    void loadDepartments(Connection connection, Map<Long, BorrowRequestBuilder> builders)
            throws SQLException {
        String q = """
                SELECT rg.group_id, rg.request_id, rg.custodian_role, rg.status,
                       rg.occurrence_id, rg.staff_remarks,
                       COALESCE(ro.event_date, br.event_date) AS event_date,
                       COALESCE(ro.start_time, br.start_time) AS start_time,
                       COALESCE(ro.end_time, br.end_time) AS end_time,
                       CASE WHEN rg.status = 'DECLINED' THEN rg.declined_at
                            ELSE rg.assigned_at END AS decided_at,
                       CASE WHEN rg.status = 'DECLINED' THEN ud.full_name
                            ELSE ua.full_name END AS decided_by,
                       ur.full_name AS released_by, rg.released_at,
                       urcv.full_name AS received_by, rg.received_at,
                       ui.full_name AS inspected_by, rg.inspected_at
                FROM request_groups rg
                JOIN borrow_requests br ON br.request_id = rg.request_id
                LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                LEFT JOIN users ua ON ua.user_id = rg.assigned_by
                LEFT JOIN users ud ON ud.user_id = rg.declined_by
                LEFT JOIN users ur ON ur.user_id = rg.released_by
                LEFT JOIN users urcv ON urcv.user_id = rg.received_by
                LEFT JOIN users ui ON ui.user_id = rg.inspected_by
                WHERE rg.request_id IN (%s)
                ORDER BY rg.custodian_role, 7, 8, rg.group_id
                """.formatted(placeholders(builders.size()));
        try (PreparedStatement ps = connection.prepareStatement(q)) {
            int i = 1;
            for (Long id : builders.keySet()) ps.setLong(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BorrowRequestBuilder b = builders.get(rs.getLong("request_id"));
                    if (b == null) continue;
                    LocalDate date = Sql.getDate(rs, "event_date").toLocalDate();
                    long occurrence = rs.getLong("occurrence_id");
                    Long occurrenceId = rs.wasNull() ? null : occurrence;
                    b.departments.add(new DepartmentStatus(
                            rs.getLong("group_id"),
                            rs.getString("custodian_role"),
                            rs.getString("status"),
                            occurrenceId,
                            LocalDateTime.of(date, Sql.getTime(rs, "start_time").toLocalTime()),
                            LocalDateTime.of(date, Sql.getTime(rs, "end_time").toLocalTime()),
                            rs.getString("staff_remarks"),
                            rs.getString("decided_by"),
                            toDateTime(Sql.getTimestamp(rs, "decided_at")),
                            rs.getString("released_by"),
                            toDateTime(Sql.getTimestamp(rs, "released_at")),
                            rs.getString("received_by"),
                            toDateTime(Sql.getTimestamp(rs, "received_at")),
                            rs.getString("inspected_by"),
                            toDateTime(Sql.getTimestamp(rs, "inspected_at"))));
                }
            }
        }
    }
}
