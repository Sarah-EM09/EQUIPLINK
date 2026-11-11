package src.service;

import src.database.Database;
import src.model.BorrowRequest;
import src.model.BorrowRequestForm;
import src.model.EquipmentRequestItem;
import src.model.ItemInspection;
import src.model.RequestOccurrence;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BorrowRequestService {

    private static final String[] EDITABLE_STATUSES = {
            "PENDING_STAFF", "NEEDS_ACTION"
    };

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

    public long createRequest(long borrowerId, BorrowRequestForm form) throws Exception {
        validate(form);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Map<Long, EquipmentRecord> equipment =
                        lockAndValidateEquipment(connection, form, null);
                long requestId = insertRequest(connection, borrowerId, form);
                List<Long> occurrenceIds = insertOccurrences(connection, requestId, form);
                for (Long occId : occurrenceIds) {
                    Map<String, Long> groupIds = createGroups(connection, requestId, occId, equipment);
                    insertItems(connection, requestId, occId, form, equipment, groupIds);
                }
                recordHistory(connection, requestId, null, borrowerId,
                        "SUBMITTED", null, "PENDING_STAFF", null);
                boolean priority = isFacultyBorrower(connection, borrowerId);
                notifyGroupStaff(connection, requestId, "PENDING",
                        priority ? "Priority: new faculty request" : "New equipment request",
                        priority
                                ? "A faculty borrowing request is waiting for equipment processing. Faculty requests are handled first."
                                : "A new borrowing request is waiting for equipment processing.");
                connection.commit();
                return requestId;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void updateRequest(
            long borrowerId,
            long requestId,
            BorrowRequestForm form) throws Exception {
        validate(form);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockEditableRequest(connection, borrowerId, requestId);
                ensureGroupsArePending(connection, requestId);
                Map<Long, EquipmentRecord> equipment =
                        lockAndValidateEquipment(connection, form, requestId);

                String updateSql = """
                        UPDATE borrow_requests
                        SET venue = ?, event_date = ?, start_time = ?, end_time = ?,
                            purpose = ?, participants = ?, person_in_charge = ?,
                            position = ?, status = 'PENDING_STAFF', remarks = NULL,
                            admin_status = NULL, director_status = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE request_id = ? AND borrower_id = ?
                        """;
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    bindForm(statement, form, 0);
                    statement.setLong(9, requestId);
                    statement.setLong(10, borrowerId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be updated.");
                    }
                }

                try (PreparedStatement s = connection.prepareStatement(
                        "DELETE FROM request_occurrences WHERE request_id = ?")) {
                    s.setLong(1, requestId);
                    s.executeUpdate();
                }
                try (PreparedStatement s = connection.prepareStatement(
                        "DELETE FROM request_groups WHERE request_id = ?")) {
                    s.setLong(1, requestId);
                    s.executeUpdate();
                }

                List<Long> occurrenceIds = insertOccurrences(connection, requestId, form);
                for (Long occId : occurrenceIds) {
                    Map<String, Long> groupIds = createGroups(connection, requestId, occId, equipment);
                    insertItems(connection, requestId, occId, form, equipment, groupIds);
                }
                recordHistory(connection, requestId, null, borrowerId,
                        "BORROWER_EDITED", previousStatus, "PENDING_STAFF",
                        "Borrower updated request details.");
                notifyGroupStaff(connection, requestId, "PENDING",
                        "Request updated by borrower",
                        "A borrowing request was updated and needs equipment processing again.");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void deleteRequest(long borrowerId, long requestId) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockEditableRequest(connection, borrowerId, requestId);
                ensureGroupsArePending(connection, requestId);

                String updateSql = """
                        UPDATE borrow_requests
                        SET status = 'CANCELLED',
                            cancelled_by = ?,
                            cancelled_at = CURRENT_TIMESTAMP,
                            cancellation_reason = 'Cancelled by borrower',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE request_id = ? AND borrower_id = ?
                        """;
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setLong(1, borrowerId);
                    statement.setLong(2, requestId);
                    statement.setLong(3, borrowerId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be cancelled.");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'CANCELLED', cancelled_by = ?,
                            cancelled_at = CURRENT_TIMESTAMP
                        WHERE request_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setLong(1, borrowerId);
                    statement.setLong(2, requestId);
                    statement.executeUpdate();
                }
                recordHistory(connection, requestId, null, borrowerId,
                        "BORROWER_CANCELLED", previousStatus, "CANCELLED",
                        "Cancelled by borrower.");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

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

    public int countQueueForRole(String role) throws SQLException {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        String sql;
        if ("ICT_STAFF".equals(normalizedRole)
                || "FACILITY_STAFF".equals(normalizedRole)) {
            sql = """
                    SELECT COUNT(DISTINCT br.request_id)
                    FROM borrow_requests br
                    JOIN request_groups rg ON rg.request_id = br.request_id
                    WHERE br.status = 'PENDING_STAFF'
                      AND rg.custodian_role = ?
                      AND rg.status = 'PENDING'
                    """;
        } else if ("ADMIN".equals(normalizedRole)) {
            sql = "SELECT COUNT(*) FROM borrow_requests WHERE status = 'PENDING_ADMIN'";
        } else if ("DIRECTOR".equals(normalizedRole)) {
            sql = "SELECT COUNT(*) FROM borrow_requests WHERE status = 'PENDING_DIRECTOR'";
        } else {
            sql = """
                    SELECT COUNT(*) FROM borrow_requests
                    WHERE status IN ('PENDING_STAFF', 'NEEDS_ACTION', 'PENDING_ADMIN', 'PENDING_DIRECTOR')
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
                        "SELECT COUNT(*) FROM borrow_requests WHERE status = ?")) {
            statement.setString(1, status);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
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

    /** Orders faculty (priority) requests first, keeping the existing order otherwise. */
    private static List<BorrowRequest> prioritized(List<BorrowRequest> requests) {
        requests.sort(BorrowRequest.PRIORITY_FIRST);
        return requests;
    }

    public List<BorrowRequest> findAllRequests() throws SQLException {
        return findRequests("br.request_id > 0");
    }

    /**
     * Active hand-overs for the role: approved requests whose equipment is ready to
     * release, and released equipment still waiting to be returned.
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
                          (rg.status = 'ASSIGNED' AND br.status = 'APPROVED')
                          OR rg.status = 'RELEASED'
                      )
                )
                """, normalizedRole));
    }

    /**
     * Finished transactions for the role: equipment that was returned, or assigned
     * requests that were later rejected or cancelled.
     */
    public List<BorrowRequest> findHistoryForRole(String role) throws SQLException {
        String normalizedRole = requireCustodianRole(role);
        return findRequests("""
                EXISTS (
                    SELECT 1
                    FROM request_groups rg
                    WHERE rg.request_id = br.request_id
                      AND rg.custodian_role = ?
                      AND (
                          rg.status = 'RETURNED'
                          OR (br.status IN ('REJECTED', 'CANCELLED')
                              AND rg.assigned_by IS NOT NULL)
                      )
                )
                """, normalizedRole);
    }

    public void assignStaffRequest(String role, long actorId, long requestId)
            throws Exception {
        assignStaffRequest(role, actorId, requestId, null);
    }

    /**
     * Assigns quantities for the actor's equipment group. A null list assigns the full
     * requested quantities; otherwise each entry sets the quantity for one item, where
     * 0 marks the item as unable to fulfill.
     */
    public void assignStaffRequest(String role, long actorId, long requestId,
            List<ItemInspection> assignments) throws Exception {
        String normalizedRole = requireCustodianRole(role);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                LocalDate eventDate;
                java.time.LocalTime startTime;
                java.time.LocalTime endTime;
                String previousStatus;
                long groupId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT br.status,
                               COALESCE(ro.event_date, br.event_date) AS event_date,
                               COALESCE(ro.start_time, br.start_time) AS start_time,
                               COALESCE(ro.end_time, br.end_time) AS end_time,
                               rg.group_id, rg.status AS group_status
                        FROM borrow_requests br
                        JOIN request_groups rg ON rg.request_id = br.request_id
                        LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                        WHERE br.request_id = ? AND rg.custodian_role = ? AND rg.status = 'PENDING'
                        ORDER BY COALESCE(ro.event_date, br.event_date),
                                 COALESCE(ro.start_time, br.start_time), rg.group_id
                        FOR UPDATE OF br, rg
                        """)) {
                    statement.setLong(1, requestId);
                    statement.setString(2, normalizedRole);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException(
                                    "No pending equipment group was found for this request.");
                        }
                        previousStatus = result.getString("status");
                        if (!"PENDING_STAFF".equals(previousStatus)
                                && !"NEEDS_ACTION".equals(previousStatus)) {
                            throw new IllegalArgumentException(
                                    "This request is no longer waiting for staff review.");
                        }
                        groupId = result.getLong("group_id");
                        eventDate = result.getDate("event_date").toLocalDate();
                        startTime = result.getTime("start_time").toLocalTime();
                        endTime = result.getTime("end_time").toLocalTime();
                    }
                }

                Map<Long, Integer> requested = new LinkedHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT requested_equipment_id, requested_quantity
                        FROM request_items
                        WHERE group_id = ?
                        FOR UPDATE
                        """)) {
                    statement.setLong(1, groupId);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            requested.put(result.getLong("requested_equipment_id"),
                                    result.getInt("requested_quantity"));
                        }
                    }
                }
                if (requested.isEmpty()) {
                    throw new IllegalArgumentException(
                            "There are no equipment items to assign in this request.");
                }

                Map<Long, ItemInspection> inputs = new HashMap<>();
                if (assignments != null) {
                    for (ItemInspection input : assignments) {
                        inputs.put(input.getEquipmentId(), input);
                    }
                }
                int totalAssigned = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_items
                        SET assigned_equipment_id = requested_equipment_id,
                            assigned_quantity = ?,
                            inspection_result = ?,
                            staff_remarks = ?
                        WHERE group_id = ? AND requested_equipment_id = ?
                        """)) {
                    for (Map.Entry<Long, Integer> item : requested.entrySet()) {
                        ItemInspection input = inputs.get(item.getKey());
                        int quantity = input == null ? item.getValue() : input.getQuantity();
                        if (quantity < 0 || quantity > item.getValue()) {
                            throw new IllegalArgumentException(
                                    "Assigned quantity must be between 0 and the requested quantity.");
                        }
                        int available = EquipmentService.findAvailableQuantity(
                                connection, item.getKey(), requestId,
                                eventDate, startTime, endTime);
                        if (quantity > available) {
                            throw new IllegalArgumentException(
                                    "Only " + available
                                            + " unit(s) are available for the selected schedule.");
                        }
                        String note = input == null || input.getRemarks() == null
                                || input.getRemarks().isBlank()
                                ? null : input.getRemarks().trim();
                        statement.setInt(1, quantity);
                        statement.setString(2, quantity == 0 ? "UNABLE_TO_FULFILL"
                                : quantity < item.getValue() ? "PARTIAL" : null);
                        statement.setString(3, note);
                        statement.setLong(4, groupId);
                        statement.setLong(5, item.getKey());
                        statement.addBatch();
                        totalAssigned += quantity;
                    }
                    if (totalAssigned == 0) {
                        throw new IllegalArgumentException(
                                "No equipment can be assigned. Use \"Request changes\" to notify the borrower instead.");
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'ASSIGNED', assigned_by = ?,
                            assigned_at = CURRENT_TIMESTAMP, staff_remarks = NULL
                        WHERE group_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setLong(1, actorId);
                    statement.setLong(2, groupId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "Equipment assignment did not complete.");
                    }
                }

                boolean readyForAdmin;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT NOT EXISTS (
                            SELECT 1 FROM request_groups
                            WHERE request_id = ?
                              AND status NOT IN ('ASSIGNED', 'CANCELLED')
                        )
                        """)) {
                    statement.setLong(1, requestId);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        readyForAdmin = result.getBoolean(1);
                    }
                }
                String nextStatus = readyForAdmin ? "PENDING_ADMIN" : previousStatus;
                if (readyForAdmin) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE borrow_requests
                            SET status = 'PENDING_ADMIN', updated_at = CURRENT_TIMESTAMP
                            WHERE request_id = ?
                            """)) {
                        statement.setLong(1, requestId);
                        statement.executeUpdate();
                    }
                }
                recordHistory(connection, requestId, groupId, actorId,
                        "EQUIPMENT_ASSIGNED", previousStatus, nextStatus, null);
                if (readyForAdmin) {
                    notifyBorrower(connection, requestId,
                            "Request ready for approval",
                            "Staff completed equipment assignment. Your request is waiting for administrative review.");
                    notifyRole(connection, "ADMIN", requestId,
                            "Request awaiting review",
                            "A processed borrowing request is waiting for administrative review.");
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void markStaffRequestNeedsAction(
            String role, long actorId, long requestId, String remarks) throws Exception {
        String normalizedRole = requireCustodianRole(role);
        if (remarks == null || remarks.isBlank()) {
            throw new IllegalArgumentException("Enter a reason for the requested changes.");
        }
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long groupId;
                String previousStatus;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT br.status, rg.group_id, rg.status AS group_status
                        FROM borrow_requests br
                        JOIN request_groups rg ON rg.request_id = br.request_id
                        LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                        WHERE br.request_id = ? AND rg.custodian_role = ? AND rg.status = 'PENDING'
                        ORDER BY COALESCE(ro.event_date, br.event_date), COALESCE(ro.start_time, br.start_time), rg.group_id
                        FOR UPDATE OF br, rg
                        """)) {
                    statement.setLong(1, requestId);
                    statement.setString(2, normalizedRole);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException("No pending equipment group was found.");
                        }
                        previousStatus = result.getString("status");
                        if (!"PENDING_STAFF".equals(previousStatus)
                                && !"NEEDS_ACTION".equals(previousStatus)) {
                            throw new IllegalArgumentException(
                                    "This request is no longer waiting for staff review.");
                        }
                        groupId = result.getLong("group_id");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'UNABLE', staff_remarks = ?
                        WHERE group_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setString(1, remarks.trim());
                    statement.setLong(2, groupId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_items
                        SET assigned_equipment_id = NULL, assigned_quantity = 0
                        WHERE request_id = ?
                        """)) {
                    statement.setLong(1, requestId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'PENDING', assigned_by = NULL, assigned_at = NULL
                        WHERE request_id = ? AND status = 'ASSIGNED'
                        """)) {
                    statement.setLong(1, requestId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE borrow_requests
                        SET status = 'NEEDS_ACTION', remarks = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE request_id = ?
                        """)) {
                    statement.setString(1, remarks.trim());
                    statement.setLong(2, requestId);
                    statement.executeUpdate();
                }
                recordHistory(connection, requestId, groupId, actorId,
                        "STAFF_REQUESTED_CHANGES", previousStatus, "NEEDS_ACTION",
                        remarks.trim());
                notifyBorrower(connection, requestId, "Request needs your attention",
                        remarks.trim());
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void reviewByAdmin(long actorId, long requestId, boolean approve, String remarks)
            throws Exception {
        reviewRequest(actorId, requestId, "ADMIN", approve, remarks);
    }

    public void reviewByDirector(long actorId, long requestId, boolean approve, String remarks)
            throws Exception {
        reviewRequest(actorId, requestId, "DIRECTOR", approve, remarks);
    }

    public void releaseStaffAssignment(String role, long actorId, long requestId,
            List<ItemInspection> conditions) throws Exception {
        updateStaffTransaction(role, actorId, requestId, true, conditions);
    }

    public void recordStaffReturn(String role, long actorId, long requestId,
            List<ItemInspection> inspections) throws Exception {
        updateStaffTransaction(role, actorId, requestId, false, inspections);
    }

    private void updateStaffTransaction(
            String role, long actorId, long requestId, boolean release,
            List<ItemInspection> inspections) throws Exception {
        String normalizedRole = requireCustodianRole(role);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long groupId;
                String requestStatus;
                String groupStatus;
                String issues = null;
                String wantedGroup = release ? "ASSIGNED" : "RELEASED";
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT br.status, rg.group_id, rg.status AS group_status
                        FROM borrow_requests br
                        JOIN request_groups rg ON rg.request_id = br.request_id
                        LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                        WHERE br.request_id = ? AND rg.custodian_role = ? AND rg.status = ?
                        ORDER BY COALESCE(ro.event_date, br.event_date), COALESCE(ro.start_time, br.start_time), rg.group_id
                        FOR UPDATE OF br, rg
                        """)) {
                    statement.setLong(1, requestId);
                    statement.setString(2, normalizedRole);
                    statement.setString(3, wantedGroup);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException(
                                    release ? "No assigned equipment group is ready for release."
                                            : "No released equipment group is ready for return inspection.");
                        }
                        requestStatus = result.getString("status");
                        groupId = result.getLong("group_id");
                        groupStatus = result.getString("group_status");
                    }
                }

                if (release) {
                    if (!"APPROVED".equals(requestStatus) || !"ASSIGNED".equals(groupStatus)) {
                        throw new IllegalArgumentException(
                                "Only approved equipment assignments can be released.");
                    }
                    recordReleaseConditions(connection, groupId, inspections);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE request_groups
                            SET status = 'RELEASED', released_by = ?,
                                released_at = CURRENT_TIMESTAMP
                            WHERE group_id = ? AND status = 'ASSIGNED'
                            """)) {
                        statement.setLong(1, actorId);
                        statement.setLong(2, groupId);
                        statement.executeUpdate();
                    }
                } else {
                    if (!"APPROVED".equals(requestStatus)
                            && !"RELEASED".equals(requestStatus)) {
                        throw new IllegalArgumentException(
                                "Only released equipment can be marked as returned.");
                    }
                    if (!"RELEASED".equals(groupStatus)) {
                        throw new IllegalArgumentException(
                                "Only released equipment can be marked as returned.");
                    }
                    issues = recordReturnInspection(
                            connection, requestId, groupId, inspections);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE request_groups
                            SET status = 'RETURNED', inspected_by = ?,
                                inspected_at = CURRENT_TIMESTAMP
                            WHERE group_id = ? AND status = 'RELEASED'
                            """)) {
                        statement.setLong(1, actorId);
                        statement.setLong(2, groupId);
                        statement.executeUpdate();
                    }
                }

                String nextStatus = requestStatus;
                String remainingStatuses = release
                        ? "('PENDING', 'ASSIGNED')"
                        : "('PENDING', 'ASSIGNED', 'RELEASED')";
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT NOT EXISTS (SELECT 1 FROM request_groups "
                                + "WHERE request_id = ? AND status IN " + remainingStatuses + ")")) {
                    statement.setLong(1, requestId);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        if (result.getBoolean(1)) {
                            nextStatus = release ? "RELEASED" : "COMPLETED";
                        }
                    }
                }
                if (!requestStatus.equals(nextStatus)) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE borrow_requests
                            SET status = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE request_id = ?
                            """)) {
                        statement.setString(1, nextStatus);
                        statement.setLong(2, requestId);
                        statement.executeUpdate();
                    }
                }
                recordHistory(connection, requestId, groupId, actorId,
                        release ? "EQUIPMENT_RELEASED" : "EQUIPMENT_RETURNED",
                        requestStatus, nextStatus, issues);
                if (issues != null) {
                    notifyRole(connection, "ADMIN", requestId,
                            "Damaged or missing equipment",
                            "Return inspection found issues: " + issues);
                }
                notifyBorrower(connection, requestId,
                        release ? "Equipment released" : "Equipment returned",
                        release
                                ? "Your approved equipment has been released to you."
                                : issues == null
                                        ? "Your returned equipment was inspected with no issues."
                                        : "Your returned equipment was inspected. Issues found: " + issues);
                if ("COMPLETED".equals(nextStatus)) {
                    notifyBorrower(connection, requestId, "Transaction completed",
                            "All equipment has been returned and the request is completed.");
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void reviewRequest(
            long actorId, long requestId, String reviewerRole,
            boolean approve, String remarks) throws Exception {
        String currentStatus = "ADMIN".equals(reviewerRole)
                ? "PENDING_ADMIN" : "PENDING_DIRECTOR";
        String nextStatus = approve
                ? ("ADMIN".equals(reviewerRole) ? "PENDING_DIRECTOR" : "APPROVED")
                : "REJECTED";
        String statusColumn = "ADMIN".equals(reviewerRole) ? "admin_status" : "director_status";
        String event = reviewerRole + (approve ? "_APPROVED" : "_REJECTED");
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT status
                        FROM borrow_requests
                        WHERE request_id = ?
                        FOR UPDATE
                        """)) {
                    statement.setLong(1, requestId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || !currentStatus.equals(result.getString("status"))) {
                            throw new IllegalArgumentException(
                                    "This request is not waiting for your review.");
                        }
                    }
                }
                if (!approve && (remarks == null || remarks.isBlank())) {
                    throw new IllegalArgumentException(
                            "Enter a reason before rejecting the request.");
                }
                String updateSql = """
                        UPDATE borrow_requests
                        SET status = ?, %s = ?, remarks = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE request_id = ?
                        """.formatted(statusColumn);
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setString(1, nextStatus);
                    statement.setString(2, approve ? "APPROVED" : "REJECTED");
                    statement.setString(3, remarks == null || remarks.isBlank()
                            ? null : remarks.trim());
                    statement.setLong(4, requestId);
                    statement.executeUpdate();
                }
                if (!approve) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE request_groups
                            SET status = 'CANCELLED'
                            WHERE request_id = ?
                              AND status IN ('PENDING', 'ASSIGNED')
                            """)) {
                        statement.setLong(1, requestId);
                        statement.executeUpdate();
                    }
                }
                recordHistory(connection, requestId, null, actorId,
                        event, currentStatus, nextStatus,
                        remarks == null || remarks.isBlank() ? null : remarks.trim());
                if (approve && "ADMIN".equals(reviewerRole)) {
                    notifyRole(connection, "DIRECTOR", requestId,
                            "Request awaiting final approval",
                            "A borrowing request approved by the administrator needs your final decision.");
                } else if (approve) {
                    notifyGroupStaff(connection, requestId, "ASSIGNED",
                            "Equipment ready for release",
                            "A request was given final approval. Record the equipment condition and release it.");
                }
                notifyBorrower(connection, requestId,
                        approve ? "Request approved" : "Request rejected",
                        approve
                                ? ("ADMIN".equals(reviewerRole)
                                        ? "Your request is waiting for final campus director review."
                                        : "Your equipment request has been approved.")
                                : "Your request was rejected. Remarks: " + remarks.trim());
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void recordReleaseConditions(
            Connection connection, long groupId, List<ItemInspection> inspections)
            throws SQLException {
        Map<Long, ItemInspection> inputs = indexInspections(inspections);
        List<long[]> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ri.request_item_id, ri.requested_equipment_id,
                       ri.assigned_equipment_id, ri.assigned_quantity, e.equipment_name
                FROM request_items ri
                JOIN equipment e ON e.equipment_id = ri.assigned_equipment_id
                WHERE ri.group_id = ? AND ri.assigned_quantity > 0
                ORDER BY ri.assigned_equipment_id
                """)) {
            statement.setLong(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new long[] { result.getLong(1), result.getLong(2) });
                    long assignedId = result.getLong(3);
                    int quantity = result.getInt(4);
                    String name = result.getString(5);
                    // Final confirmation deducts stock; lock the item so concurrent
                    // releases cannot hand out more units than are on hand.
                    try (PreparedStatement lock = connection.prepareStatement(
                            "SELECT 1 FROM equipment WHERE equipment_id = ? FOR UPDATE")) {
                        lock.setLong(1, assignedId);
                        lock.executeQuery().close();
                    }
                    int onHand = EquipmentService.findAvailableQuantity(
                            connection, assignedId, null, null, null, null);
                    if (quantity > onHand) {
                        throw new IllegalArgumentException("Only " + onHand + " " + name
                                + " on hand; " + quantity + " needed. Wait for returns "
                                + "or adjust the assignment.");
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE request_items
                SET released_quantity = assigned_quantity,
                    condition_before = ?,
                    staff_remarks = COALESCE(?, staff_remarks)
                WHERE request_item_id = ?
                """)) {
            for (long[] item : items) {
                ItemInspection input = inputs.get(item[1]);
                if (input == null || input.getCondition() == null
                        || input.getCondition().isBlank()) {
                    throw new IllegalArgumentException(
                            "Record the initial condition of every released item.");
                }
                statement.setString(1, limit(input.getCondition(), 80));
                statement.setString(2, blankToNull(input.getRemarks()));
                statement.setLong(3, item[0]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Records return inspection; returns an issue summary, or null when none. */
    private String recordReturnInspection(
            Connection connection, long requestId, long groupId,
            List<ItemInspection> inspections) throws SQLException {
        Map<Long, ItemInspection> inputs = indexInspections(inspections);
        StringBuilder issues = new StringBuilder();
        List<Object[]> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ri.request_item_id, ri.requested_equipment_id,
                       ri.assigned_equipment_id, ri.released_quantity, e.equipment_name
                FROM request_items ri
                JOIN equipment e ON e.equipment_id = ri.requested_equipment_id
                WHERE ri.group_id = ? AND ri.released_quantity > 0
                """)) {
            statement.setLong(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new Object[] {
                            result.getLong(1), result.getLong(2), result.getLong(3),
                            result.getInt(4), result.getString(5) });
                }
            }
        }
        try (PreparedStatement itemUpdate = connection.prepareStatement("""
                UPDATE request_items
                SET returned_quantity = ?, damaged_quantity = ?, missing_quantity = ?,
                    condition_after = ?, inspection_result = ?,
                    staff_remarks = COALESCE(?, staff_remarks)
                WHERE request_item_id = ?
                """);
                PreparedStatement equipmentUpdate = connection.prepareStatement("""
                        UPDATE equipment
                        SET damaged_quantity = damaged_quantity + ?,
                            lost_quantity = lost_quantity + ?
                        WHERE equipment_id = ?
                        """)) {
            for (Object[] item : items) {
                long requestItemId = (Long) item[0];
                long equipmentId = (Long) item[1];
                long assignedEquipmentId = (Long) item[2];
                int released = (Integer) item[3];
                String name = (String) item[4];
                ItemInspection input = inputs.get(equipmentId);
                if (input == null || input.getCondition() == null
                        || input.getCondition().isBlank()) {
                    throw new IllegalArgumentException(
                            "Record the returned condition of every item.");
                }
                int damaged = input.getDamagedQuantity();
                int missing = input.getMissingQuantity();
                if (damaged < 0 || missing < 0 || damaged + missing > released) {
                    throw new IllegalArgumentException(
                            "Damaged and missing quantities for " + name
                                    + " cannot exceed the " + released + " released.");
                }
                boolean hasIssue = damaged > 0 || missing > 0;
                itemUpdate.setInt(1, released - missing);
                itemUpdate.setInt(2, damaged);
                itemUpdate.setInt(3, missing);
                itemUpdate.setString(4, limit(input.getCondition(), 80));
                itemUpdate.setString(5, hasIssue ? "WITH_ISSUES" : "OK");
                itemUpdate.setString(6, blankToNull(input.getRemarks()));
                itemUpdate.setLong(7, requestItemId);
                itemUpdate.addBatch();
                if (hasIssue) {
                    equipmentUpdate.setInt(1, damaged);
                    equipmentUpdate.setInt(2, missing);
                    equipmentUpdate.setLong(3, assignedEquipmentId);
                    equipmentUpdate.addBatch();
                    if (issues.length() > 0) {
                        issues.append("; ");
                    }
                    issues.append(name).append(" (")
                            .append(damaged).append(" damaged, ")
                            .append(missing).append(" missing)");
                }
            }
            itemUpdate.executeBatch();
            equipmentUpdate.executeBatch();
        }
        if (issues.length() == 0) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE borrow_requests SET has_issues = TRUE WHERE request_id = ?")) {
            statement.setLong(1, requestId);
            statement.executeUpdate();
        }
        return issues.toString();
    }

    private Map<Long, ItemInspection> indexInspections(List<ItemInspection> inspections) {
        Map<Long, ItemInspection> map = new HashMap<>();
        if (inspections != null) {
            for (ItemInspection inspection : inspections) {
                map.put(inspection.getEquipmentId(), inspection);
            }
        }
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String limit(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private boolean isFacultyBorrower(Connection connection, long borrowerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT borrower_type FROM users WHERE user_id = ?")) {
            statement.setLong(1, borrowerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "FACULTY".equals(result.getString(1));
            }
        }
    }

    private void notifyRole(
            Connection connection, String role, long requestId, String title, String message)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message)
                SELECT user_id, ?, ?, ?
                FROM users
                WHERE role = ? AND active = TRUE
                """)) {
            statement.setLong(1, requestId);
            statement.setString(2, title);
            statement.setString(3, message);
            statement.setString(4, role);
            statement.executeUpdate();
        }
    }

    /** Notifies staff whose equipment group on the request has the given status. */
    private void notifyGroupStaff(
            Connection connection, long requestId, String groupStatus,
            String title, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message)
                SELECT u.user_id, rg.request_id, ?, ?
                FROM request_groups rg
                JOIN users u ON u.role = rg.custodian_role AND u.active = TRUE
                WHERE rg.request_id = ? AND rg.status = ?
                """)) {
            statement.setString(1, title);
            statement.setString(2, message);
            statement.setLong(3, requestId);
            statement.setString(4, groupStatus);
            statement.executeUpdate();
        }
    }

    private void notifyBorrower(
            Connection connection, long requestId, String title, String message)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message)
                SELECT borrower_id, request_id, ?, ?
                FROM borrow_requests
                WHERE request_id = ?
                """)) {
            statement.setString(1, title);
            statement.setString(2, message);
            statement.setLong(3, requestId);
            statement.executeUpdate();
        }
    }

    private String requireCustodianRole(String role) {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        if (!"ICT_STAFF".equals(normalizedRole)
                && !"FACILITY_STAFF".equals(normalizedRole)) {
            throw new SecurityException(
                    "Only equipment staff can manage this request group.");
        }
        return normalizedRole;
    }

    private int countRequests(long borrowerId, String statuses) throws SQLException {
        String sql = "SELECT COUNT(*) FROM borrow_requests "
                + "WHERE borrower_id = ? AND status IN " + statuses;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, borrowerId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private List<BorrowRequest> findRequests(String predicate, Object... parameters)
            throws SQLException {
        String sql = """
                SELECT br.request_id, br.request_code, br.borrower_id,
                       u.full_name AS borrower_name, u.borrower_type, br.venue, br.event_date,
                       br.start_time, br.end_time, br.purpose, br.participants,
                       br.person_in_charge, br.position, br.status, br.remarks,
                       br.submitted_at,
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
                WHERE %s
                ORDER BY br.submitted_at DESC, br.request_id DESC, e.equipment_name
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
                        builder.items.add(new EquipmentRequestItem(
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
        }

        List<BorrowRequest> requests = new ArrayList<>();
        if (!builders.isEmpty()) {
            loadOccurrences(null, builders);
        }
        for (BorrowRequestBuilder builder : builders.values()) {
            requests.add(builder.build());
        }
        return requests;
    }

    private void loadOccurrences(Connection ignored, Map<Long, BorrowRequestBuilder> builders)
            throws SQLException {
        if (builders.isEmpty()) return;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < builders.size(); i++) { if (i > 0) in.append(','); in.append('?'); }
        String q = "SELECT occurrence_id, request_id, event_date, start_time, end_time, venue "
                + "FROM request_occurrences WHERE request_id IN (" + in
                + ") ORDER BY event_date, start_time, occurrence_id";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(q)) {
            int i = 1;
            for (Long id : builders.keySet()) ps.setLong(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BorrowRequestBuilder b = builders.get(rs.getLong("request_id"));
                    if (b != null) b.occurrences.add(new RequestOccurrence(
                            rs.getLong("occurrence_id"), rs.getLong("request_id"),
                            rs.getDate("event_date").toLocalDate(),
                            rs.getTime("start_time").toLocalTime(),
                            rs.getTime("end_time").toLocalTime(),
                            rs.getString("venue")));
                }
            }
        }
    }

    private void validate(BorrowRequestForm form) {
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

    private List<RequestOccurrence> effectiveOccurrences(BorrowRequestForm form) {
        if (form.getOccurrences() != null && !form.getOccurrences().isEmpty()) {
            return form.getOccurrences();
        }
        if (form.getEventDate() != null && form.getStartTime() != null && form.getEndTime() != null) {
            return List.of(new RequestOccurrence(form.getEventDate(), form.getStartTime(),
                    form.getEndTime(), form.getVenue()));
        }
        return List.of();
    }

    private Map<Long, EquipmentRecord> lockAndValidateEquipment(
            Connection connection,
            BorrowRequestForm form,
            Long excludedRequestId) throws SQLException {
        long[] ids = form.getEquipmentItems().stream()
                .mapToLong(EquipmentRequestItem::getEquipmentId)
                .sorted()
                .toArray();
        Map<Long, EquipmentRecord> equipment = new HashMap<>();
        String sql = """
                SELECT equipment_id, equipment_code, equipment_name, category,
                       status, custodian_role, total_quantity
                FROM equipment
                WHERE equipment_id = ? AND active = TRUE
                FOR UPDATE
                """;

        for (long id : ids) {
            String name;
            String status;
            String custodianRole;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException(
                                "An equipment item is no longer available.");
                    }
                    name = result.getString("equipment_name");
                    status = result.getString("status");
                    custodianRole = result.getString("custodian_role");
                }
            }
            if (!"AVAILABLE".equals(status)) {
                throw new IllegalArgumentException(name + " is not currently available.");
            }
            int minAvailable = Integer.MAX_VALUE;
            for (RequestOccurrence occ : effectiveOccurrences(form)) {
                int available = EquipmentService.findAvailableQuantity(
                        connection, id, excludedRequestId,
                        occ.getEventDate(), occ.getStartTime(), occ.getEndTime());
                if (available < minAvailable) minAvailable = available;
            }
            if (minAvailable == Integer.MAX_VALUE) minAvailable = 0;
            equipment.put(id, new EquipmentRecord(name, status, custodianRole, minAvailable));
        }

        for (EquipmentRequestItem item : form.getEquipmentItems()) {
            EquipmentRecord record = equipment.get(item.getEquipmentId());
            if (record == null || item.getRequestedQuantity() > record.availableQuantity) {
                throw new IllegalArgumentException(
                        "Requested quantity exceeds available stock for "
                                + (record == null ? "equipment" : record.name)
                                + " on one or more selected schedules.");
            }
        }
        return equipment;
    }

    private long insertRequest(
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
            statement.setDate(4, Date.valueOf(first.getEventDate()));
            statement.setTime(5, Time.valueOf(first.getStartTime()));
            statement.setTime(6, Time.valueOf(first.getEndTime()));
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

    private List<Long> insertOccurrences(Connection connection, long requestId, BorrowRequestForm form)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_occurrences (request_id, event_date, start_time, end_time, venue)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            for (RequestOccurrence o : effectiveOccurrences(form)) {
                statement.setLong(1, requestId);
                statement.setDate(2, Date.valueOf(o.getEventDate()));
                statement.setTime(3, Time.valueOf(o.getStartTime()));
                statement.setTime(4, Time.valueOf(o.getEndTime()));
                statement.setString(5, o.getVenue());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) ids.add(keys.getLong(1));
                }
            }
        }
        return ids;
    }

    private void bindForm(
            PreparedStatement statement,
            BorrowRequestForm form,
            int offset) throws SQLException {
        List<RequestOccurrence> occs = effectiveOccurrences(form);
        RequestOccurrence first = occs.get(0);
        statement.setString(1 + offset, first.getVenue());
        statement.setDate(2 + offset, Date.valueOf(first.getEventDate()));
        statement.setTime(3 + offset, Time.valueOf(first.getStartTime()));
        statement.setTime(4 + offset, Time.valueOf(first.getEndTime()));
        statement.setString(5 + offset, form.getPurpose());
        statement.setString(6 + offset, form.getParticipants());
        statement.setString(7 + offset, form.getPersonInCharge());
        statement.setString(8 + offset, form.getPosition());
    }

    private Map<String, Long> createGroups(
            Connection connection,
            long requestId,
            Long occurrenceId,
            Map<Long, EquipmentRecord> equipment) throws SQLException {
        Map<String, Long> groups = new HashMap<>();
        for (EquipmentRecord record : equipment.values()) {
            if (!groups.containsKey(record.custodianRole)) {
                groups.put(record.custodianRole,
                        insertGroup(connection, requestId, occurrenceId, record.custodianRole));
            }
        }
        return groups;
    }

    @SuppressWarnings("unused")
    private Map<String, Long> prepareGroupsForEdit(
            Connection connection,
            long requestId,
            Map<Long, EquipmentRecord> equipment) throws SQLException {
        // Legacy path retained for compatibility; edit flow now recreates occurrences/groups.
        return Map.of();
    }

    private long insertGroup(Connection connection, long requestId, Long occurrenceId, String role)
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

    private void insertItems(
            Connection connection,
            long requestId,
            Long occurrenceId,
            BorrowRequestForm form,
            Map<Long, EquipmentRecord> equipment,
            Map<String, Long> groupIds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_items (
                    request_id, occurrence_id, group_id, requested_equipment_id, requested_quantity
                )
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (EquipmentRequestItem item : form.getEquipmentItems()) {
                EquipmentRecord record = equipment.get(item.getEquipmentId());
                Long groupId = groupIds.get(record.custodianRole);
                if (groupId == null) {
                    throw new SQLException("Equipment custodian group was not created.");
                }
                statement.setLong(1, requestId);
                if (occurrenceId == null) statement.setNull(2, Types.BIGINT);
                else statement.setLong(2, occurrenceId);
                statement.setLong(3, groupId);
                statement.setLong(4, item.getEquipmentId());
                statement.setInt(5, item.getRequestedQuantity());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private String lockEditableRequest(
            Connection connection,
            long borrowerId,
            long requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status
                FROM borrow_requests
                WHERE request_id = ? AND borrower_id = ?
                FOR UPDATE
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

    private void ensureGroupsArePending(Connection connection, long requestId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM request_groups
                WHERE request_id = ?
                  AND status NOT IN ('PENDING', 'UNABLE', 'CANCELLED')
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

    private void recordHistory(
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

    private static class EquipmentRecord {
        private final String name;
        private final String status;
        private final String custodianRole;
        private final int availableQuantity;

        private EquipmentRecord(
                String name, String status, String custodianRole, int availableQuantity) {
            this.name = name;
            this.status = status;
            this.custodianRole = custodianRole;
            this.availableQuantity = availableQuantity;
        }
    }

    private static class BorrowRequestBuilder {
        private final long requestId;
        private final String requestCode;
        private final long borrowerId;
        private final String borrowerName;
        private final String venue;
        private final LocalDate eventDate;
        private final java.time.LocalTime startTime;
        private final java.time.LocalTime endTime;
        private final String purpose;
        private final String participants;
        private final String personInCharge;
        private final String position;
        private final String status;
        private final String remarks;
        private final LocalDateTime submittedAt;
        private final List<EquipmentRequestItem> items = new ArrayList<>();
        private final List<RequestOccurrence> occurrences = new ArrayList<>();
        private final String borrowerType;

        private BorrowRequestBuilder(ResultSet result) throws SQLException {
            borrowerType = result.getString("borrower_type");
            requestId = result.getLong("request_id");
            requestCode = result.getString("request_code");
            borrowerId = result.getLong("borrower_id");
            borrowerName = result.getString("borrower_name");
            venue = result.getString("venue");
            eventDate = result.getDate("event_date").toLocalDate();
            startTime = result.getTime("start_time").toLocalTime();
            endTime = result.getTime("end_time").toLocalTime();
            purpose = result.getString("purpose");
            participants = result.getString("participants");
            personInCharge = result.getString("person_in_charge");
            position = result.getString("position");
            status = result.getString("status");
            remarks = result.getString("remarks");
            submittedAt = result.getTimestamp("submitted_at").toLocalDateTime();
        }

        private BorrowRequest build() {
            BorrowRequest request = new BorrowRequest(
                    requestId, requestCode, borrowerId, borrowerName, venue,
                    eventDate, startTime, endTime, purpose, participants,
                    personInCharge, position, status, remarks, submittedAt, items, occurrences);
            request.setBorrowerType(borrowerType);
            return request;
        }
    }

}
