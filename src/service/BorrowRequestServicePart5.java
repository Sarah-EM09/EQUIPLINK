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

/** Part 5 of BorrowRequestService, split out to keep files short. */
public abstract class BorrowRequestServicePart5 extends BorrowRequestServicePart4 {

    /**
     * Department decline: marks only the actor's department equipment as declined.
     * The other department keeps processing; the request is declined overall only
     * when every department declined.
     */
    public void declineStaffRequest(String role, long actorId, long requestId, String remarks)
            throws Exception {
        String normalizedRole = requireCustodianRole(role);
        String department = RequestStatus.departmentLabel(normalizedRole);
        if (remarks == null || remarks.isBlank()) {
            throw new IllegalArgumentException("Enter a reason for declining.");
        }
        String reason = remarks.trim();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockRequestStatus(connection, requestId);
                requireStaffReview(previousStatus);
                List<GroupRow> pending = processablePendingGroups(
                        lockRoleGroups(connection, requestId, normalizedRole));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_items
                        SET assigned_equipment_id = NULL, assigned_quantity = 0,
                            inspection_result = 'DECLINED'
                        WHERE group_id IN (%s)
                        """.formatted(placeholders(pending.size())))) {
                    int parameter = 1;
                    for (GroupRow group : pending) {
                        statement.setLong(parameter++, group.groupId);
                    }
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'DECLINED', declined_by = ?,
                            declined_at = datetime('now', '+8 hours'), staff_remarks = ?
                        WHERE group_id IN (%s) AND status = 'PENDING'
                        """.formatted(placeholders(pending.size())))) {
                    statement.setLong(1, actorId);
                    statement.setString(2, reason);
                    int parameter = 3;
                    for (GroupRow group : pending) {
                        statement.setLong(parameter++, group.groupId);
                    }
                    statement.executeUpdate();
                }
                String nextStatus = advanceAfterDepartmentDecision(
                        connection, requestId, previousStatus);
                recordHistory(connection, requestId, pending.get(0).groupId, actorId,
                        "DEPARTMENT_DECLINED", previousStatus, nextStatus,
                        department + " declined: " + reason);
                notifyBorrower(connection, requestId,
                        department + " equipment declined",
                        department + " staff declined the " + department
                                + " equipment in your request. Reason: " + reason
                                + ("REJECTED".equals(nextStatus) ? ""
                                        : " Equipment from other departments is still being processed."),
                        normalizedRole, "DEPARTMENT_DECLINED");
                notifyRole(connection, "ADMIN", requestId,
                        department + " declined equipment",
                        department + " staff declined their assigned equipment. Reason: " + reason,
                        normalizedRole, "DEPARTMENT_DECLINED");
                notifyDepartment(connection, normalizedRole, requestId, actorId,
                        department + " equipment declined",
                        "Your department declined its equipment for this request. Reason: " + reason,
                        "DEPARTMENT_DECLINED");
                notifyAfterDepartmentDecision(connection, requestId, previousStatus, nextStatus);
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
                String previousStatus = lockRequestStatus(connection, requestId);
                requireStaffReview(previousStatus);
                List<GroupRow> pending = processablePendingGroups(
                        lockRoleGroups(connection, requestId, normalizedRole));
                // Departments whose approval is reset by the borrower's edit are told so.
                notifyGroupStaff(connection, requestId, "ASSIGNED",
                        "Request sent back to borrower",
                        RequestStatus.departmentLabel(normalizedRole)
                                + " staff requested changes from the borrower. Review the request again after it is resubmitted.",
                        "NEEDS_ACTION");
                String placeholders = String.join(", ",
                        java.util.Collections.nCopies(pending.size(), "?"));
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'UNABLE', staff_remarks = ?
                        WHERE request_id = ? AND custodian_role = ? AND status = 'PENDING'
                          AND group_id IN (
                        """ + placeholders + ")" )) {
                    int parameter = 1;
                    statement.setString(parameter++, remarks.trim());
                    statement.setLong(parameter++, requestId);
                    statement.setString(parameter++, normalizedRole);
                    for (GroupRow group : pending) {
                        statement.setLong(parameter++, group.groupId);
                    }
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
                        SET status = 'NEEDS_ACTION', remarks = ?, updated_at = datetime('now', '+8 hours')
                        WHERE request_id = ?
                        """)) {
                    statement.setString(1, remarks.trim());
                    statement.setLong(2, requestId);
                    statement.executeUpdate();
                }
                recordHistory(connection, requestId, pending.get(0).groupId, actorId,
                        "STAFF_REQUESTED_CHANGES", previousStatus, "NEEDS_ACTION",
                        remarks.trim());
                notifyBorrower(connection, requestId, "Request needs your attention",
                        remarks.trim(), normalizedRole, "NEEDS_ACTION");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void receiveByBorrower(long borrowerId, long requestId) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ApprovalState state = lockApprovalState(connection, requestId, borrowerId);
                if (!RequestStatus.isFinalApprovalGranted(
                        state.status, state.adminStatus, state.directorStatus)) {
                    throw new IllegalArgumentException(
                            "Equipment can only be received after final approval and release.");
                }
                List<GroupRow> groups = lockBorrowerGroups(connection, requestId);
                if (groups.isEmpty()) {
                    throw new IllegalStateException("No equipment groups found for this request.");
                }
                boolean hasReleased = false;
                for (GroupRow group : groups) {
                    if ("RELEASED".equals(group.status)) {
                        hasReleased = true;
                        break;
                    }
                }
                if (!hasReleased) {
                    throw new IllegalArgumentException(
                            "No released equipment is waiting for your confirmation.");
                }
                // Mark every released department group as received, with the borrower
                // and timestamp kept on the group for Transaction History.
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE request_groups SET status='RECEIVED', received_by=?, received_at=datetime('now', '+8 hours') "
                                + "WHERE request_id=? AND status='RELEASED'")) {
                    ps.setLong(1, borrowerId);
                    ps.setLong(2, requestId);
                    ps.executeUpdate();
                }
                // Do not change the stored request status here; staff still inspects and
                // returns each department's equipment independently.
                recordHistory(connection, requestId, null, borrowerId, "BORROWER_RECEIVED",
                        state.status, state.status, null);
                String message = "Borrower confirmed receiving released equipment for request "
                        + requestId + ".";
                notifyRole(connection, "ICT_STAFF", requestId, "Borrower confirmed receipt",
                        message, null, "BORROWER_RECEIVED");
                notifyRole(connection, "FACILITY_STAFF", requestId, "Borrower confirmed receipt",
                        message, null, "BORROWER_RECEIVED");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
