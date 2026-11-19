package src.service;

import src.database.Database;
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

/** Part 6 of BorrowRequestService, split out to keep files short. */
public abstract class BorrowRequestServicePart6 extends BorrowRequestServicePart5 {

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

    /**
     * Releases or inspects the return of the actor's department equipment. The final
     * decision is checked from the stored Facility Admin and Campus Director approvals,
     * so a request can never be released before both required approvals are recorded.
     * Each department is still processed independently.
     */
    void updateStaffTransaction(
            String role, long actorId, long requestId, boolean release,
            List<ItemInspection> inspections) throws Exception {
        String normalizedRole = requireCustodianRole(role);
        String department = RequestStatus.departmentLabel(normalizedRole);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ApprovalState state = lockApprovalState(connection, requestId, null);
                String requestStatus = state.status;
                List<GroupRow> groups = lockRoleGroups(connection, requestId, normalizedRole);
                if (groups.isEmpty()) {
                    throw new SecurityException(
                            "This request has no equipment assigned to your department.");
                }
                boolean finalApproved = RequestStatus.isFinalApprovalGranted(
                        state.status, state.adminStatus, state.directorStatus);
                GroupRow target;
                String issues = null;
                if (release) {
                    if (!finalApproved) {
                        throw new IllegalArgumentException(stageBlockedReason(requestStatus));
                    }
                    target = firstWithStatus(groups, "ASSIGNED");
                    if (target == null) {
                        throw new IllegalArgumentException(releaseBlockedReason(groups));
                    }
                    recordReleaseConditions(connection, target.groupId, inspections);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE request_groups
                            SET status = 'RELEASED', released_by = ?,
                                released_at = datetime('now', '+8 hours')
                            WHERE group_id = ? AND status = 'ASSIGNED'
                            """)) {
                        statement.setLong(1, actorId);
                        statement.setLong(2, target.groupId);
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("Equipment release did not complete.");
                        }
                    }
                } else {
                    if (!finalApproved) {
                        throw new IllegalArgumentException(stageBlockedReason(requestStatus));
                    }
                    target = firstWithStatus(groups, "RECEIVED");
                    if (target == null) {
                        throw new IllegalArgumentException(
                                "Borrower must confirm receipt (Receive) before you can inspect/record return.");
                    }
                    issues = recordReturnInspection(
                            connection, requestId, target.groupId, inspections);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE request_groups
                            SET status = 'RETURNED', inspected_by = ?,
                                inspected_at = datetime('now', '+8 hours')
                            WHERE group_id = ? AND status = 'RECEIVED'
                            """)) {
                        statement.setLong(1, actorId);
                        statement.setLong(2, target.groupId);
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("Equipment return did not complete.");
                        }
                    }
                }

                String nextStatus = requestStatus;
                String remainingStatuses = release
                        ? "('PENDING', 'ASSIGNED')"
                        : "('PENDING', 'ASSIGNED', 'RELEASED', 'RECEIVED')";
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
                            SET status = ?, updated_at = datetime('now', '+8 hours')
                            WHERE request_id = ?
                            """)) {
                        statement.setString(1, nextStatus);
                        statement.setLong(2, requestId);
                        statement.executeUpdate();
                    }
                } else {
                    touchRequest(connection, requestId);
                }
                recordHistory(connection, requestId, target.groupId, actorId,
                        release ? "EQUIPMENT_RELEASED" : "EQUIPMENT_RETURNED",
                        requestStatus, nextStatus,
                        issues == null ? department : department + ": " + issues);
                String action = release ? "EQUIPMENT_RELEASED" : "EQUIPMENT_RETURNED";
                if (issues != null) {
                    notifyRole(connection, "ADMIN", requestId,
                            "Damaged or missing equipment",
                            department + " return inspection found issues: " + issues,
                            normalizedRole, action);
                }
                notifyBorrower(connection, requestId,
                        release ? department + " equipment released" : department + " equipment returned",
                        release
                                ? "Your approved " + department + " equipment has been released to you."
                                : issues == null
                                        ? "Your returned " + department + " equipment was inspected with no issues."
                                        : "Your returned " + department + " equipment was inspected. Issues found: " + issues,
                        normalizedRole, action);
                notifyRole(connection, "ADMIN", requestId,
                        release ? department + " released equipment" : department + " received returned equipment",
                        release
                                ? department + " staff released their equipment to the borrower."
                                : department + " staff recorded the equipment return.",
                        normalizedRole, action);
                notifyDepartment(connection, normalizedRole, requestId, actorId,
                        release ? department + " equipment released" : department + " equipment returned",
                        release
                                ? "Your department released its equipment for this request."
                                : "Your department recorded the return of its equipment.",
                        action);
                if ("COMPLETED".equals(nextStatus)) {
                    notifyBorrower(connection, requestId, "Transaction completed",
                            "All equipment has been returned and the request is completed.",
                            null, "COMPLETED");
                    notifyRole(connection, "ADMIN", requestId, "Request completed",
                            "All departments finished processing this request.",
                            null, "COMPLETED");
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    void reviewRequest(
            long actorId, long requestId, String reviewerRole,
            boolean approve, String remarks) throws Exception {
        String currentStatus = "ADMIN".equals(reviewerRole)
                ? "PENDING_ADMIN" : "PENDING_DIRECTOR";
        String nextStatus = approve
                ? ("ADMIN".equals(reviewerRole) ? "PENDING_DIRECTOR" : "APPROVED")
                : "REJECTED";
        String statusColumn = "ADMIN".equals(reviewerRole) ? "admin_status" : "director_status";
        String event = reviewerRole + (approve ? "_APPROVED" : "_REJECTED");
        String reviewer = "ADMIN".equals(reviewerRole) ? "Administrator" : "Campus director";
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String status = lockRequestStatus(connection, requestId);
                if (!currentStatus.equals(status)) {
                    throw new IllegalArgumentException(
                            "This request is not waiting for your review.");
                }
                if (!approve && (remarks == null || remarks.isBlank())) {
                    throw new IllegalArgumentException(
                            "Enter a reason before rejecting the request.");
                }
                String updateSql = """
                        UPDATE borrow_requests
                        SET status = ?, %s = ?, remarks = ?,
                            updated_at = datetime('now', '+8 hours')
                        WHERE request_id = ?
                        """.formatted(statusColumn);
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setString(1, nextStatus);
                    statement.setString(2, approve ? "APPROVED" : "REJECTED");
                    statement.setString(3, blankToNull(remarks));
                    statement.setLong(4, requestId);
                    statement.executeUpdate();
                }
                if (!approve) {
                    notifyGroupStaff(connection, requestId, "ASSIGNED",
                            "Request declined by " + reviewer.toLowerCase(),
                            reviewer + " declined a request your department approved. Reason: "
                                    + remarks.trim(),
                            event);
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
                        event, currentStatus, nextStatus, blankToNull(remarks));
                if (approve && "ADMIN".equals(reviewerRole)) {
                    notifyRole(connection, "DIRECTOR", requestId,
                            "Request awaiting final approval",
                            "A borrowing request approved by the administrator needs your final decision.",
                            null, event);
                    notifyGroupStaff(connection, requestId, "ASSIGNED",
                            "Request approved by administrator",
                            "A request your department approved passed administrative review and awaits final approval.",
                            event);
                } else if (approve) {
                    notifyGroupStaff(connection, requestId, "ASSIGNED",
                            "Equipment ready for release",
                            "A request was given final approval. Record the equipment condition and release your department's equipment.",
                            event);
                    notifyRole(connection, "ADMIN", requestId,
                            "Request given final approval",
                            "The campus director approved this request. Departments can now release their equipment.",
                            null, event);
                } else if ("DIRECTOR".equals(reviewerRole)) {
                    notifyRole(connection, "ADMIN", requestId,
                            "Request declined by campus director",
                            "The campus director declined this request. Reason: " + remarks.trim(),
                            null, event);
                }
                notifyBorrower(connection, requestId,
                        approve ? "Request approved" : "Request declined",
                        approve
                                ? ("ADMIN".equals(reviewerRole)
                                        ? "Your request is waiting for final campus director review."
                                        : "Your equipment request has been approved. Each department will release its equipment.")
                                : "Your request was declined. Remarks: " + remarks.trim(),
                        null, event);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    static class EquipmentRecord {
        final String name;
        final String status;
        final String custodianRole;
        final int availableQuantity;

        EquipmentRecord(
                String name, String status, String custodianRole, int availableQuantity) {
            this.name = name;
            this.status = status;
            this.custodianRole = custodianRole;
            this.availableQuantity = availableQuantity;
        }
    }
}
