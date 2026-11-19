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

/** Part 2 of BorrowRequestService, split out to keep files short. */
public abstract class BorrowRequestServicePart2 extends BorrowRequestServicePart1 {

    /**
     * Moves the request forward once every department decided: to admin review when
     * at least one department approved, or to declined when all departments declined.
     */
    String advanceAfterDepartmentDecision(
            Connection connection, long requestId, String currentStatus) throws SQLException {
        int undecided;
        int approved;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FILTER (WHERE status IN ('PENDING', 'UNABLE')),
                       COUNT(*) FILTER (WHERE status = 'ASSIGNED')
                FROM request_groups
                WHERE request_id = ?
                """)) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                undecided = result.getInt(1);
                approved = result.getInt(2);
            }
        }
        if (undecided > 0) {
            touchRequest(connection, requestId);
            return currentStatus;
        }
        String nextStatus = approved > 0 ? "PENDING_ADMIN" : "REJECTED";
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE borrow_requests
                SET status = ?,
                    remarks = CASE WHEN ? THEN 'All departments declined their equipment.'
                                   ELSE remarks END,
                    updated_at = datetime('now', '+8 hours')
                WHERE request_id = ?
                """)) {
            statement.setString(1, nextStatus);
            statement.setBoolean(2, "REJECTED".equals(nextStatus));
            statement.setLong(3, requestId);
            statement.executeUpdate();
        }
        return nextStatus;
    }

    void recordReleaseConditions(
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
                            "SELECT 1 FROM equipment WHERE equipment_id = ?")) {
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
    String recordReturnInspection(
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

    Map<Long, ItemInspection> indexInspections(List<ItemInspection> inspections) {
        Map<Long, ItemInspection> map = new HashMap<>();
        if (inspections != null) {
            for (ItemInspection inspection : inspections) {
                map.put(inspection.getEquipmentId(), inspection);
            }
        }
        return map;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String limit(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    boolean isFacultyBorrower(Connection connection, long borrowerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT borrower_type FROM users WHERE user_id = ?")) {
            statement.setLong(1, borrowerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "FACULTY".equals(result.getString(1));
            }
        }
    }

    void notifyRole(
            Connection connection, String role, long requestId, String title, String message)
            throws SQLException {
        notifyRole(connection, role, requestId, title, message, null, null);
    }

    void notifyRole(
            Connection connection, String role, long requestId, String title, String message,
            String department, String action) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message, department, action)
                SELECT user_id, ?, ?, ?, ?, ?
                FROM users
                WHERE role = ? AND active = TRUE
                  AND COALESCE(notifications_enabled, TRUE) = TRUE
                """)) {
            statement.setLong(1, requestId);
            statement.setString(2, title);
            statement.setString(3, message);
            statement.setString(4, department);
            statement.setString(5, action);
            statement.setString(6, role);
            statement.executeUpdate();
        }
    }

    /** Notifies every active staff member of one department, except the actor. */
    void notifyDepartment(
            Connection connection, String role, long requestId, long excludedUserId,
            String title, String message, String action) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message, department, action)
                SELECT user_id, ?, ?, ?, ?, ?
                FROM users
                WHERE role = ? AND active = TRUE AND user_id <> ?
                  AND COALESCE(notifications_enabled, TRUE) = TRUE
                """)) {
            statement.setLong(1, requestId);
            statement.setString(2, title);
            statement.setString(3, message);
            statement.setString(4, role);
            statement.setString(5, action);
            statement.setString(6, role);
            statement.setLong(7, excludedUserId);
            statement.executeUpdate();
        }
    }

    void notifyGroupStaff(
            Connection connection, long requestId, String groupStatus,
            String title, String message) throws SQLException {
        notifyGroupStaff(connection, requestId, groupStatus, title, message, null);
    }

    /** Notifies staff whose department group on the request has the given status. */
    void notifyGroupStaff(
            Connection connection, long requestId, String groupStatus,
            String title, String message, String action) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message, department, action)
                SELECT DISTINCT u.user_id, rg.request_id, ?, ?, rg.custodian_role, ?
                FROM request_groups rg
                JOIN users u ON u.role = rg.custodian_role AND u.active = TRUE
                WHERE rg.request_id = ? AND rg.status = ?
                  AND COALESCE(u.notifications_enabled, TRUE) = TRUE
                """)) {
            statement.setString(1, title);
            statement.setString(2, message);
            statement.setString(3, action);
            statement.setLong(4, requestId);
            statement.setString(5, groupStatus);
            statement.executeUpdate();
        }
    }

    void notifyBorrower(
            Connection connection, long requestId, String title, String message)
            throws SQLException {
        notifyBorrower(connection, requestId, title, message, null, null);
    }

    void notifyBorrower(
            Connection connection, long requestId, String title, String message,
            String department, String action) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message, department, action)
                SELECT br.borrower_id, br.request_id, ?, ?, ?, ?
                FROM borrow_requests br
                JOIN users u ON u.user_id = br.borrower_id
                WHERE br.request_id = ?
                  AND COALESCE(u.notifications_enabled, TRUE) = TRUE
                """)) {
            statement.setString(1, title);
            statement.setString(2, message);
            statement.setString(3, department);
            statement.setString(4, action);
            statement.setLong(5, requestId);
            statement.executeUpdate();
        }
    }
}
