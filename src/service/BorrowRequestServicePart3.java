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

/** Part 3 of BorrowRequestService, split out to keep files short. */
public abstract class BorrowRequestServicePart3 extends BorrowRequestServicePart2 {

    public int countActiveForBorrower(long borrowerId) throws SQLException {
        return countRequests(borrowerId,
                "('PENDING_STAFF', 'NEEDS_ACTION', 'PENDING_ADMIN', "
                        + "'PENDING_DIRECTOR', 'APPROVED', 'RELEASED')");
    }

    public int countPendingForBorrower(long borrowerId) throws SQLException {
        return countRequests(borrowerId,
                "('PENDING_STAFF', 'NEEDS_ACTION', 'PENDING_ADMIN', "
                        + "'PENDING_DIRECTOR', 'APPROVED')");
    }

    public int countBorrowedForBorrower(long borrowerId) throws SQLException {
        return countRequests(borrowerId, "('RELEASED')");
    }

    void notifyAfterDepartmentDecision(
            Connection connection, long requestId, String previousStatus, String nextStatus)
            throws SQLException {
        if (previousStatus.equals(nextStatus)) {
            return;
        }
        if ("PENDING_ADMIN".equals(nextStatus)) {
            notifyBorrower(connection, requestId,
                    "Request ready for approval",
                    "Every department finished its review. Your request is waiting for administrative review.",
                    null, "PENDING_ADMIN");
            notifyRole(connection, "ADMIN", requestId,
                    "Request awaiting review",
                    "All departments decided on this request. It is waiting for administrative review.",
                    null, "PENDING_ADMIN");
        } else if ("REJECTED".equals(nextStatus)) {
            notifyBorrower(connection, requestId,
                    "Request declined",
                    "Every department declined its equipment, so the request was declined.",
                    null, "DECLINED");
            notifyRole(connection, "ADMIN", requestId,
                    "Request declined by all departments",
                    "Every department declined its equipment for this request.",
                    null, "DECLINED");
        }
    }
    String requireCustodianRole(String role) {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        if (!"ICT_STAFF".equals(normalizedRole)
                && !"FACILITY_STAFF".equals(normalizedRole)) {
            throw new SecurityException(
                    "Only equipment staff can manage this request group.");
        }
        return normalizedRole;
    }

    int countRequests(long borrowerId, String statuses) throws SQLException {
        String sql = "SELECT COUNT(*) FROM borrow_requests "
                + "WHERE deleted_at IS NULL AND borrower_id = ? AND status IN " + statuses;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, borrowerId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    static String placeholders(int count) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) in.append(',');
            in.append('?');
        }
        return in.toString();
    }

    static LocalDateTime toDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
    void validate(BorrowRequestForm form) {
        if (form == null) {
            throw new IllegalArgumentException("Request details are required.");
        }
        List<RequestOccurrence> occs = effectiveOccurrences(form);
        if (occs.isEmpty()) {
            throw new IllegalArgumentException("Add at least one schedule.");
        }
        RequestOccurrence first = occs.get(0);
        if (form.getPurpose().isBlank()
                || form.getParticipants().isBlank()
                || form.getPersonInCharge().isBlank()
                || form.getPosition().isBlank()) {
            throw new IllegalArgumentException("Complete all required request details.");
        }
        if (form.getParticipants().length() > 255
                || form.getPersonInCharge().length() > 150
                || form.getPosition().length() > 100) {
            throw new IllegalArgumentException(
                    "Some request details are too long. Shorten the participants, person in charge, or position.");
        }
        for (RequestOccurrence o : occs) {
            if (o.getVenue() == null || o.getVenue().isBlank() || o.getVenue().length() > 150
                    || o.getEventDate() == null || o.getStartTime() == null || o.getEndTime() == null) {
                throw new IllegalArgumentException("Complete venue, date, start time, and end time for each schedule.");
            }
            if (o.getEventDate().isBefore(LocalDate.now())) {
                throw new IllegalArgumentException("Schedule dates cannot be in the past.");
            }
            if (!o.getEndTime().isAfter(o.getStartTime())) {
                throw new IllegalArgumentException("Each schedule's end time must be after its start time.");
            }
        }
        if (form.getEquipmentItems().isEmpty()) {
            throw new IllegalArgumentException("Add at least one equipment item.");
        }

        Set<Long> equipmentIds = new HashSet<>();
        for (EquipmentRequestItem item : form.getEquipmentItems()) {
            if (item.getEquipmentId() <= 0 || item.getRequestedQuantity() <= 0) {
                throw new IllegalArgumentException("Select equipment and enter a valid quantity.");
            }
            if (!equipmentIds.add(item.getEquipmentId())) {
                throw new IllegalArgumentException(
                        "Each equipment type can only be added once.");
            }
        }
    }

    List<RequestOccurrence> effectiveOccurrences(BorrowRequestForm form) {
        if (form.getOccurrences() != null && !form.getOccurrences().isEmpty()) {
            return form.getOccurrences();
        }
        if (form.getEventDate() != null && form.getStartTime() != null && form.getEndTime() != null) {
            return List.of(new RequestOccurrence(form.getEventDate(), form.getStartTime(),
                    form.getEndTime(), form.getVenue()));
        }
        return List.of();
    }

    long insertRequest(
            Connection connection,
            long borrowerId,
            BorrowRequestForm form) throws SQLException {
        String sql = """
                INSERT INTO borrow_requests (
                    request_code, borrower_id, venue, event_date, start_time,
                    end_time, purpose, participants, person_in_charge, position,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_STAFF')
                """;
        String code = "EL-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10).toUpperCase();
        List<RequestOccurrence> occs = effectiveOccurrences(form);
        RequestOccurrence first = occs.get(0);
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, code);
            statement.setLong(2, borrowerId);
            statement.setString(3, first.getVenue());
            Sql.setDate(statement, 4, first.getEventDate());
            Sql.setTime(statement, 5, first.getStartTime());
            Sql.setTime(statement, 6, first.getEndTime());
            statement.setString(7, form.getPurpose());
            statement.setString(8, form.getParticipants());
            statement.setString(9, form.getPersonInCharge());
            statement.setString(10, form.getPosition());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return a request ID.");
                }
                return keys.getLong(1);
            }
        }
    }

    List<Long> insertOccurrences(Connection connection, long requestId, BorrowRequestForm form)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_occurrences (request_id, event_date, start_time, end_time, venue)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            for (RequestOccurrence o : effectiveOccurrences(form)) {
                statement.setLong(1, requestId);
                Sql.setDate(statement, 2, o.getEventDate());
                Sql.setTime(statement, 3, o.getStartTime());
                Sql.setTime(statement, 4, o.getEndTime());
                statement.setString(5, o.getVenue());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) ids.add(keys.getLong(1));
                }
            }
        }
        return ids;
    }

    void bindForm(
            PreparedStatement statement,
            BorrowRequestForm form,
            int offset) throws SQLException {
        List<RequestOccurrence> occs = effectiveOccurrences(form);
        RequestOccurrence first = occs.get(0);
        statement.setString(1 + offset, first.getVenue());
        Sql.setDate(statement, 2 + offset, first.getEventDate());
        Sql.setTime(statement, 3 + offset, first.getStartTime());
        Sql.setTime(statement, 4 + offset, first.getEndTime());
        statement.setString(5 + offset, form.getPurpose());
        statement.setString(6 + offset, form.getParticipants());
        statement.setString(7 + offset, form.getPersonInCharge());
        statement.setString(8 + offset, form.getPosition());
    }

    long insertGroup(Connection connection, long requestId, Long occurrenceId, String role)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_groups (request_id, occurrence_id, custodian_role, status)
                VALUES (?, ?, ?, 'PENDING')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, requestId);
            if (occurrenceId == null) statement.setNull(2, Types.BIGINT);
            else statement.setLong(2, occurrenceId);
            statement.setString(3, role);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return a request group ID.");
                }
                return keys.getLong(1);
            }
        }
    }

    String lockEditableRequest(
            Connection connection,
            long borrowerId,
            long requestId) throws SQLException {
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
                String status = result.getString("status");
                if (!Arrays.asList(EDITABLE_STATUSES).contains(status)) {
                    throw new IllegalArgumentException(
                            "Only pending requests that have not been processed can be changed.");
                }
                return status;
            }
        }
    }

    void ensureGroupsArePending(Connection connection, long requestId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM request_groups
                WHERE request_id = ?
                  AND status NOT IN ('PENDING', 'UNABLE', 'CANCELLED', 'DECLINED')
                """)) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) > 0) {
                    throw new IllegalArgumentException(
                            "This request can no longer be changed because staff has processed it.");
                }
            }
        }
    }

    void recordHistory(
            Connection connection,
            long requestId,
            Long groupId,
            long actorId,
            String event,
            String fromStatus,
            String toStatus,
            String remarks) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_history (
                    request_id, group_id, actor_id, event_type,
                    from_status, to_status, remarks
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, requestId);
            if (groupId == null) {
                statement.setNull(2, Types.BIGINT);
            } else {
                statement.setLong(2, groupId);
            }
            statement.setLong(3, actorId);
            statement.setString(4, event);
            statement.setString(5, fromStatus);
            statement.setString(6, toStatus);
            statement.setString(7, remarks);
            statement.executeUpdate();
        }
    }
}
