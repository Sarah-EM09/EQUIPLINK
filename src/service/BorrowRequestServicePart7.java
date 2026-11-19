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

/** Part 7 of BorrowRequestService, split out to keep files short. */
public abstract class BorrowRequestServicePart7 extends BorrowRequestServicePart6 {

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
                                : "A new borrowing request is waiting for equipment processing.",
                        "SUBMITTED");
                notifyRole(connection, "ADMIN", requestId,
                        priority ? "New faculty request submitted" : "New request submitted",
                        "A borrower submitted a new equipment request. ICT and Facility staff will review their assigned equipment.",
                        null, "SUBMITTED");
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
                            updated_at = datetime('now', '+8 hours')
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
                        "A borrowing request was updated and needs equipment processing again.",
                        "BORROWER_EDITED");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    void loadOccurrences(Connection connection, Map<Long, BorrowRequestBuilder> builders)
            throws SQLException {
        String q = "SELECT occurrence_id, request_id, event_date, start_time, end_time, venue "
                + "FROM request_occurrences WHERE request_id IN (" + placeholders(builders.size())
                + ") ORDER BY event_date, start_time, occurrence_id";
        try (PreparedStatement ps = connection.prepareStatement(q)) {
            int i = 1;
            for (Long id : builders.keySet()) ps.setLong(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BorrowRequestBuilder b = builders.get(rs.getLong("request_id"));
                    if (b != null) b.occurrences.add(new RequestOccurrence(
                            rs.getLong("occurrence_id"), rs.getLong("request_id"),
                            Sql.getDate(rs, "event_date").toLocalDate(),
                            Sql.getTime(rs, "start_time").toLocalTime(),
                            Sql.getTime(rs, "end_time").toLocalTime(),
                            rs.getString("venue")));
                }
            }
        }
    }

    Map<Long, EquipmentRecord> lockAndValidateEquipment(
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

    Map<String, Long> createGroups(
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

    void insertItems(
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

    static class BorrowRequestBuilder {
        final long requestId;
        final String requestCode;
        final long borrowerId;
        final String borrowerName;
        final String venue;
        final LocalDate eventDate;
        final java.time.LocalTime startTime;
        final java.time.LocalTime endTime;
        final String purpose;
        final String participants;
        final String personInCharge;
        final String position;
        final String status;
        final String remarks;
        final String adminStatus;
        final String directorStatus;
        final LocalDateTime submittedAt;
        /** One line per equipment; multi-schedule copies are merged to avoid duplicates. */
        final Map<Long, EquipmentRequestItem> items = new LinkedHashMap<>();
        final List<RequestOccurrence> occurrences = new ArrayList<>();
        final List<DepartmentStatus> departments = new ArrayList<>();
        final String borrowerType;

        BorrowRequestBuilder(ResultSet result) throws SQLException {
            borrowerType = result.getString("borrower_type");
            requestId = result.getLong("request_id");
            requestCode = result.getString("request_code");
            borrowerId = result.getLong("borrower_id");
            borrowerName = result.getString("borrower_name");
            venue = result.getString("venue");
            eventDate = Sql.getDate(result, "event_date").toLocalDate();
            startTime = Sql.getTime(result, "start_time").toLocalTime();
            endTime = Sql.getTime(result, "end_time").toLocalTime();
            purpose = result.getString("purpose");
            participants = result.getString("participants");
            personInCharge = result.getString("person_in_charge");
            position = result.getString("position");
            status = result.getString("status");
            remarks = result.getString("remarks");
            adminStatus = result.getString("admin_status");
            directorStatus = result.getString("director_status");
            submittedAt = Sql.getTimestamp(result, "submitted_at").toLocalDateTime();
        }

        void addItem(EquipmentRequestItem item) {
            EquipmentRequestItem existing = items.get(item.getEquipmentId());
            if (existing == null) {
                items.put(item.getEquipmentId(), item);
                return;
            }
            items.put(item.getEquipmentId(), new EquipmentRequestItem(
                    existing.getEquipmentId(), existing.getEquipmentCode(),
                    existing.getEquipmentName(), existing.getCategory(),
                    existing.getRequestedQuantity(),
                    Math.max(existing.getAssignedQuantity(), item.getAssignedQuantity()),
                    Math.max(existing.getReleasedQuantity(), item.getReleasedQuantity()),
                    Math.max(existing.getReturnedQuantity(), item.getReturnedQuantity()),
                    existing.getCustodianRole(), existing.getGroupStatus(),
                    Math.max(existing.getDamagedQuantity(), item.getDamagedQuantity()),
                    Math.max(existing.getMissingQuantity(), item.getMissingQuantity()),
                    existing.getConditionBefore() != null
                            ? existing.getConditionBefore() : item.getConditionBefore(),
                    existing.getConditionAfter() != null
                            ? existing.getConditionAfter() : item.getConditionAfter()));
        }

        BorrowRequest build() {
            BorrowRequest draft = create(new ArrayList<>(items.values()));
            // Each item shows its department's combined status across all schedules.
            Map<String, String> roleStatus = new HashMap<>();
            List<EquipmentRequestItem> merged = new ArrayList<>();
            for (EquipmentRequestItem item : items.values()) {
                String role = item.getCustodianRole();
                String combined = role == null ? item.getGroupStatus()
                        : roleStatus.computeIfAbsent(role,
                                key -> RequestStatus.department(draft, key));
                merged.add(new EquipmentRequestItem(
                        item.getEquipmentId(), item.getEquipmentCode(),
                        item.getEquipmentName(), item.getCategory(),
                        item.getRequestedQuantity(), item.getAssignedQuantity(),
                        item.getReleasedQuantity(), item.getReturnedQuantity(),
                        role, combined == null ? item.getGroupStatus() : combined,
                        item.getDamagedQuantity(), item.getMissingQuantity(),
                        item.getConditionBefore(), item.getConditionAfter()));
            }
            return create(merged);
        }

        BorrowRequest create(List<EquipmentRequestItem> requestItems) {
            BorrowRequest request = new BorrowRequest(
                    requestId, requestCode, borrowerId, borrowerName, venue,
                    eventDate, startTime, endTime, purpose, participants,
                    personInCharge, position, status, remarks, submittedAt,
                    requestItems, occurrences);
            request.setBorrowerType(borrowerType);
            request.setAdminStatus(adminStatus);
            request.setDirectorStatus(directorStatus);
            request.setDepartments(departments);
            return request;
        }
    }
}
