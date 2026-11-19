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

/** Part 4 of BorrowRequestService, split out to keep files short. */
public abstract class BorrowRequestServicePart4 extends BorrowRequestServicePart3 {

    /**
     * Soft-deletes one of the borrower's requests. Every status is deletable, including
     * completed requests. The request's original status is left untouched so Undo can
     * put it back exactly where it was instead of turning it into a cancellation.
     */
    public void deleteRequest(long borrowerId, long requestId) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockOwnRequestForDeletion(connection, borrowerId, requestId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE borrow_requests
                        SET deleted_at = datetime('now', '+8 hours'),
                            deleted_by = ?,
                            deletion_reason = ?,
                            status_before_delete = ?,
                            updated_at = datetime('now', '+8 hours')
                        WHERE request_id = ? AND borrower_id = ? AND deleted_at IS NULL
                        """)) {
                    statement.setLong(1, borrowerId);
                    statement.setString(2, "Deleted by borrower");
                    statement.setString(3, previousStatus);
                    statement.setLong(4, requestId);
                    statement.setLong(5, borrowerId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be deleted.");
                    }
                }
                recordHistory(connection, requestId, null, borrowerId,
                        "BORROWER_DELETED", previousStatus, previousStatus,
                        "Deleted by borrower; recoverable within "
                                + UndoPolicy.RECOVERY_LABEL + ".");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Restores a request deleted by its borrower, with its original status intact. */
    public void restoreRequest(long borrowerId, long requestId) throws Exception {
        restoreDeletedRequest(borrowerId, borrowerId, requestId, true, "BORROWER_RESTORED");
    }

    /**
     * ICT System Admin deletion is a recoverable soft delete: the row keeps its
     * original status and stays out of every list until it is restored or expires.
     */
    public void deleteRequestAsAdmin(
            String actorRole, long actorId, long requestId, String reason) throws Exception {
        requireIctRole(actorRole);
        String auditReason = reason == null || reason.isBlank()
                ? "Deleted by ICT System Admin" : reason.trim();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockRequestStatus(connection, requestId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE borrow_requests
                        SET deleted_at = datetime('now', '+8 hours'),
                            deleted_by = ?,
                            deletion_reason = ?,
                            status_before_delete = ?,
                            updated_at = datetime('now', '+8 hours')
                        WHERE request_id = ? AND deleted_at IS NULL
                        """)) {
                    statement.setLong(1, actorId);
                    statement.setString(2, auditReason);
                    statement.setString(3, previousStatus);
                    statement.setLong(4, requestId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be deleted.");
                    }
                }
                recordHistory(connection, requestId, null, actorId,
                        "ADMIN_DELETED", previousStatus, previousStatus, auditReason);
                notifyBorrower(connection, requestId, "Request deleted",
                        "The ICT System Admin deleted this request. Reason: " + auditReason,
                        null, "ADMIN_DELETED");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Restores a request deleted by the ICT System Admin, with its original status intact. */
    public void restoreRequestAsAdmin(
            String actorRole, long actorId, long requestId) throws Exception {
        requireIctRole(actorRole);
        restoreDeletedRequest(null, actorId, requestId, false, "ADMIN_RESTORED");
    }

    /**
     * Shared restore path. Only rows deleted inside the recovery window are brought
     * back; the status column is never rewritten, so approvals and completions survive.
     */
    void restoreDeletedRequest(
            Long borrowerId, long actorId, long requestId,
            boolean mustOwn, String event) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String status;
                String previousStatus;
                Timestamp deletedAt;
                String selectSql = "SELECT status, status_before_delete, deleted_at "
                        + "FROM borrow_requests WHERE request_id = ?"
                        + (mustOwn ? " AND borrower_id = ?" : "");
                try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                    statement.setLong(1, requestId);
                    if (mustOwn) {
                        statement.setLong(2, borrowerId == null ? 0L : borrowerId);
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException("Request was not found.");
                        }
                        status = result.getString("status");
                        previousStatus = result.getString("status_before_delete");
                        deletedAt = Sql.getTimestamp(result, "deleted_at");
                    }
                }
                if (deletedAt == null) {
                    throw new IllegalArgumentException("This request is not deleted.");
                }
                LocalDateTime earliest = LocalDateTime.now(MANILA)
                        .minusMinutes(UndoPolicy.RECOVERY_MINUTES);
                if (deletedAt.toLocalDateTime().isBefore(earliest)) {
                    throw new IllegalArgumentException(
                            "The " + UndoPolicy.RECOVERY_LABEL + " recovery period has ended.");
                }

                String updateSql = "UPDATE borrow_requests "
                        + "SET deleted_at = NULL, deleted_by = NULL, deletion_reason = NULL, "
                        + "status_before_delete = NULL, updated_at = datetime('now', '+8 hours') "
                        + "WHERE request_id = ? AND deleted_at IS NOT NULL"
                        + (mustOwn ? " AND borrower_id = ?" : "");
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setLong(1, requestId);
                    if (mustOwn) {
                        statement.setLong(2, borrowerId == null ? 0L : borrowerId);
                    }
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be restored.");
                    }
                }
                recordHistory(connection, requestId, null, actorId, event,
                        previousStatus == null ? status : previousStatus, status,
                        "Restored within the " + UndoPolicy.RECOVERY_LABEL + " recovery window.");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void assignStaffRequest(String role, long actorId, long requestId)
            throws Exception {
        assignStaffRequest(role, actorId, requestId, null);
    }

    /**
     * Department approval: assigns quantities for the actor's own department only. A
     * null list assigns the full requested quantities; otherwise each entry sets the
     * quantity for one item, where 0 marks the item as unable to fulfill. The other
     * department's records are never read for permission or modified.
     */
    public void assignStaffRequest(String role, long actorId, long requestId,
            List<ItemInspection> assignments) throws Exception {
        String normalizedRole = requireCustodianRole(role);
        String department = RequestStatus.departmentLabel(normalizedRole);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockRequestStatus(connection, requestId);
                requireStaffReview(previousStatus);
                List<GroupRow> pending = processablePendingGroups(
                        lockRoleGroups(connection, requestId, normalizedRole));

                Map<Long, ItemInspection> inputs = indexInspections(assignments);
                int totalAssigned = 0;
                int totalItems = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_items
                        SET assigned_equipment_id = requested_equipment_id,
                            assigned_quantity = ?,
                            inspection_result = ?,
                            staff_remarks = ?
                        WHERE group_id = ? AND requested_equipment_id = ?
                        """)) {
                    for (GroupRow group : pending) {
                        Map<Long, Integer> requested = lockGroupItems(connection, group.groupId);
                        for (Map.Entry<Long, Integer> item : requested.entrySet()) {
                            totalItems++;
                            ItemInspection input = inputs.get(item.getKey());
                            int quantity = input == null ? item.getValue() : input.getQuantity();
                            if (quantity < 0 || quantity > item.getValue()) {
                                throw new IllegalArgumentException(
                                        "Assigned quantity must be between 0 and the requested quantity.");
                            }
                            int available = EquipmentService.findAvailableQuantity(
                                    connection, item.getKey(), requestId,
                                    group.eventDate, group.startTime, group.endTime);
                            if (quantity > available) {
                                throw new IllegalArgumentException(
                                        "Only " + available
                                                + " unit(s) are available for the schedule on "
                                                + group.eventDate + ".");
                            }
                            String note = input == null ? null : blankToNull(input.getRemarks());
                            statement.setInt(1, quantity);
                            statement.setString(2, quantity == 0 ? "UNABLE_TO_FULFILL"
                                    : quantity < item.getValue() ? "PARTIAL" : null);
                            statement.setString(3, note);
                            statement.setLong(4, group.groupId);
                            statement.setLong(5, item.getKey());
                            statement.addBatch();
                            totalAssigned += quantity;
                        }
                    }
                    if (totalItems == 0) {
                        throw new IllegalArgumentException(
                                "There are no equipment items to assign in this request.");
                    }
                    if (totalAssigned == 0) {
                        throw new IllegalArgumentException(
                                "No equipment can be assigned. Use \"Decline\" or \"Request changes\" instead.");
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'ASSIGNED', assigned_by = ?,
                            assigned_at = datetime('now', '+8 hours'), staff_remarks = NULL,
                            declined_by = NULL, declined_at = NULL, overdue_notified = FALSE
                        WHERE group_id IN (%s) AND status = 'PENDING'
                        """.formatted(placeholders(pending.size())))) {
                    statement.setLong(1, actorId);
                    int parameter = 2;
                    for (GroupRow group : pending) {
                        statement.setLong(parameter++, group.groupId);
                    }
                    if (statement.executeUpdate() != pending.size()) {
                        throw new IllegalStateException("Equipment assignment did not complete.");
                    }
                }

                String nextStatus = advanceAfterDepartmentDecision(
                        connection, requestId, previousStatus);
                recordHistory(connection, requestId, pending.get(0).groupId, actorId,
                        "EQUIPMENT_ASSIGNED", previousStatus, nextStatus,
                        department + " approved its equipment.");
                notifyBorrower(connection, requestId,
                        department + " equipment approved",
                        department + " staff approved the " + department
                                + " equipment in your request.",
                        normalizedRole, "DEPARTMENT_APPROVED");
                notifyRole(connection, "ADMIN", requestId,
                        department + " approved equipment",
                        department + " staff approved their assigned equipment for this request.",
                        normalizedRole, "DEPARTMENT_APPROVED");
                notifyDepartment(connection, normalizedRole, requestId, actorId,
                        department + " equipment approved",
                        "Your department approved its equipment for this request.",
                        "DEPARTMENT_APPROVED");
                notifyAfterDepartmentDecision(connection, requestId, previousStatus, nextStatus);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
