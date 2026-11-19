package src.database;

import java.util.List;

/**
 * Version 1 of the SQLite schema: the complete EquipLink data model, equivalent to
 * the final PostgreSQL schema (all of its later ALTER TABLE migrations folded in).
 *
 * <p>SQLite notes: ids are {@code INTEGER PRIMARY KEY AUTOINCREMENT} (never reused,
 * like PostgreSQL sequences); booleans are 0/1; dates, times and timestamps are
 * ISO text in Philippine time (see {@link Sql}).
 */
final class SchemaV1 {

    private static final String NOW = "(" + Sql.NOW + ")";

    static final List<String> STATEMENTS = List.of(
            """
            CREATE TABLE users (
                user_id INTEGER PRIMARY KEY AUTOINCREMENT,
                username VARCHAR(60) NOT NULL UNIQUE,
                email VARCHAR(150) NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                password_salt TEXT NOT NULL,
                password_iterations INTEGER NOT NULL CHECK (password_iterations >= 100000),
                role VARCHAR(20) NOT NULL CHECK (role IN (
                    'BORROWER', 'ICT_STAFF', 'FACILITY_STAFF', 'ADMIN', 'DIRECTOR')),
                borrower_type VARCHAR(10),
                full_name VARCHAR(150) NOT NULL,
                department VARCHAR(120),
                active BOOLEAN NOT NULL DEFAULT TRUE,
                notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP NOT NULL DEFAULT %s,
                CONSTRAINT borrower_type_rule CHECK (
                    (role = 'BORROWER' AND borrower_type IS NOT NULL
                        AND borrower_type IN ('STUDENT', 'FACULTY'))
                    OR (role <> 'BORROWER' AND borrower_type IS NULL))
            )
            """.formatted(NOW),
            """
            CREATE TABLE password_reset_requests (
                reset_request_id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'COMPLETED')),
                requested_at TIMESTAMP NOT NULL DEFAULT %s,
                reviewed_by BIGINT REFERENCES users(user_id),
                reviewed_at TIMESTAMP,
                review_note TEXT,
                registered_email_match BOOLEAN NOT NULL DEFAULT FALSE,
                identity_verified BOOLEAN NOT NULL DEFAULT FALSE
            )
            """.formatted(NOW),
            """
            CREATE UNIQUE INDEX idx_password_reset_one_open_request
            ON password_reset_requests(user_id) WHERE status IN ('PENDING', 'APPROVED')
            """,
            "CREATE INDEX idx_password_reset_status_date ON password_reset_requests(status, requested_at)",
            """
            CREATE TABLE equipment (
                equipment_id INTEGER PRIMARY KEY AUTOINCREMENT,
                equipment_code VARCHAR(30) NOT NULL UNIQUE,
                equipment_name VARCHAR(120) NOT NULL,
                category VARCHAR(60) NOT NULL,
                total_quantity INTEGER NOT NULL CHECK (total_quantity >= 0),
                damaged_quantity INTEGER NOT NULL DEFAULT 0 CHECK (damaged_quantity >= 0),
                lost_quantity INTEGER NOT NULL DEFAULT 0 CHECK (lost_quantity >= 0),
                status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE'
                    CHECK (status IN ('AVAILABLE', 'UNDER_MAINTENANCE', 'RETIRED')),
                active BOOLEAN NOT NULL DEFAULT TRUE,
                custodian_role VARCHAR(20) NOT NULL
                    CHECK (custodian_role IN ('ICT_STAFF', 'FACILITY_STAFF')),
                created_by BIGINT REFERENCES users(user_id),
                created_at TIMESTAMP NOT NULL DEFAULT %s,
                CHECK (damaged_quantity + lost_quantity <= total_quantity)
            )
            """.formatted(NOW),
            """
            CREATE TABLE borrow_requests (
                request_id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_code VARCHAR(30) NOT NULL UNIQUE,
                borrower_id BIGINT NOT NULL REFERENCES users(user_id),
                venue VARCHAR(150) NOT NULL,
                event_date DATE NOT NULL,
                start_time TIME NOT NULL,
                end_time TIME NOT NULL,
                purpose TEXT NOT NULL,
                participants VARCHAR(255) NOT NULL,
                person_in_charge VARCHAR(150) NOT NULL,
                position VARCHAR(100) NOT NULL,
                status VARCHAR(30) NOT NULL DEFAULT 'PENDING_STAFF' CHECK (status IN (
                    'PENDING_STAFF', 'NEEDS_ACTION', 'PENDING_ADMIN', 'PENDING_DIRECTOR',
                    'APPROVED', 'RELEASED', 'COMPLETED', 'REJECTED', 'CANCELLED')),
                admin_status VARCHAR(20),
                director_status VARCHAR(20),
                remarks TEXT,
                has_issues BOOLEAN NOT NULL DEFAULT FALSE,
                cancelled_by BIGINT REFERENCES users(user_id),
                cancelled_at TIMESTAMP,
                cancellation_reason TEXT,
                submitted_at TIMESTAMP NOT NULL DEFAULT %1$s,
                updated_at TIMESTAMP NOT NULL DEFAULT %1$s,
                overdue_notified BOOLEAN NOT NULL DEFAULT FALSE,
                deleted_at TIMESTAMP,
                deleted_by BIGINT REFERENCES users(user_id),
                deletion_reason TEXT,
                status_before_delete VARCHAR(30),
                CHECK (end_time > start_time)
            )
            """.formatted(NOW),
            """
            CREATE TABLE request_occurrences (
                occurrence_id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id BIGINT NOT NULL
                    REFERENCES borrow_requests(request_id) ON DELETE CASCADE,
                event_date DATE NOT NULL,
                start_time TIME NOT NULL,
                end_time TIME NOT NULL,
                venue VARCHAR(150) NOT NULL,
                release_notified BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT %s,
                CHECK (end_time > start_time)
            )
            """.formatted(NOW),
            """
            CREATE TABLE request_groups (
                group_id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id BIGINT NOT NULL
                    REFERENCES borrow_requests(request_id) ON DELETE CASCADE,
                custodian_role VARCHAR(20) NOT NULL
                    CHECK (custodian_role IN ('ICT_STAFF', 'FACILITY_STAFF')),
                status VARCHAR(20) NOT NULL CHECK (status IN (
                    'PENDING', 'ASSIGNED', 'UNABLE', 'RELEASED', 'RECEIVED',
                    'RETURNED', 'DECLINED', 'CANCELLED', 'NOT_REQUIRED')),
                assigned_by BIGINT REFERENCES users(user_id),
                assigned_at TIMESTAMP,
                released_by BIGINT REFERENCES users(user_id),
                released_at TIMESTAMP,
                inspected_by BIGINT REFERENCES users(user_id),
                inspected_at TIMESTAMP,
                cancelled_by BIGINT REFERENCES users(user_id),
                cancelled_at TIMESTAMP,
                staff_remarks TEXT,
                occurrence_id BIGINT
                    REFERENCES request_occurrences(occurrence_id) ON DELETE CASCADE,
                declined_by BIGINT REFERENCES users(user_id),
                declined_at TIMESTAMP,
                received_by BIGINT REFERENCES users(user_id),
                received_at TIMESTAMP,
                overdue_notified BOOLEAN NOT NULL DEFAULT FALSE,
                return_notified BOOLEAN NOT NULL DEFAULT FALSE
            )
            """,
            """
            CREATE UNIQUE INDEX idx_request_groups_occ_role
            ON request_groups(occurrence_id, custodian_role) WHERE occurrence_id IS NOT NULL
            """,
            """
            CREATE TABLE request_items (
                request_item_id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id BIGINT NOT NULL
                    REFERENCES borrow_requests(request_id) ON DELETE CASCADE,
                group_id BIGINT NOT NULL REFERENCES request_groups(group_id) ON DELETE CASCADE,
                requested_equipment_id BIGINT NOT NULL REFERENCES equipment(equipment_id),
                requested_quantity INTEGER NOT NULL CHECK (requested_quantity > 0),
                assigned_equipment_id BIGINT REFERENCES equipment(equipment_id),
                assigned_quantity INTEGER NOT NULL DEFAULT 0 CHECK (assigned_quantity >= 0),
                released_quantity INTEGER NOT NULL DEFAULT 0 CHECK (released_quantity >= 0),
                returned_quantity INTEGER NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
                damaged_quantity INTEGER NOT NULL DEFAULT 0 CHECK (damaged_quantity >= 0),
                missing_quantity INTEGER NOT NULL DEFAULT 0 CHECK (missing_quantity >= 0),
                condition_before VARCHAR(80),
                condition_after VARCHAR(80),
                inspection_result VARCHAR(80),
                staff_remarks TEXT,
                occurrence_id BIGINT
                    REFERENCES request_occurrences(occurrence_id) ON DELETE CASCADE,
                substitute_equipment_id BIGINT REFERENCES equipment(equipment_id),
                unavailable_remarks TEXT
            )
            """,
            """
            CREATE TABLE notifications (
                notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                request_id BIGINT REFERENCES borrow_requests(request_id) ON DELETE CASCADE,
                title VARCHAR(150) NOT NULL,
                message TEXT NOT NULL,
                is_read BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT %s,
                occurrence_id BIGINT
                    REFERENCES request_occurrences(occurrence_id) ON DELETE CASCADE,
                department VARCHAR(20),
                action VARCHAR(40),
                read_at TIMESTAMP,
                is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
                deleted_at TIMESTAMP,
                read_before_delete BOOLEAN
            )
            """.formatted(NOW),
            """
            CREATE TABLE request_history (
                history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id BIGINT NOT NULL
                    REFERENCES borrow_requests(request_id) ON DELETE CASCADE,
                group_id BIGINT REFERENCES request_groups(group_id) ON DELETE CASCADE,
                actor_id BIGINT REFERENCES users(user_id),
                event_type VARCHAR(60) NOT NULL,
                from_status VARCHAR(30),
                to_status VARCHAR(30),
                remarks TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT %s
            )
            """.formatted(NOW),
            "CREATE INDEX idx_occurrences_request ON request_occurrences(request_id, event_date, start_time)",
            "CREATE INDEX idx_notifications_user_visible ON notifications(user_id, is_deleted, created_at)",
            "CREATE INDEX idx_requests_deleted_at ON borrow_requests(deleted_at)",
            "CREATE INDEX idx_history_latest ON request_history(history_id)",
            "CREATE INDEX idx_requests_borrower ON borrow_requests(borrower_id)",
            "CREATE INDEX idx_requests_status ON borrow_requests(status)",
            "CREATE INDEX idx_groups_status_role ON request_groups(custodian_role, status)",
            "CREATE INDEX idx_notifications_user_read ON notifications(user_id, is_read)",
            "CREATE INDEX idx_history_request ON request_history(request_id, created_at)",
            // Foreign-key lookups (PostgreSQL scanned these fine; SQLite benefits from them).
            "CREATE INDEX idx_groups_request ON request_groups(request_id)",
            "CREATE INDEX idx_items_request ON request_items(request_id)",
            "CREATE INDEX idx_items_group ON request_items(group_id)",
            "CREATE INDEX idx_notifications_request ON notifications(request_id)"
    );

    private SchemaV1() {
    }
}
