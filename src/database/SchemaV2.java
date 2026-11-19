package src.database;

import java.util.List;

/**
 * Version 2: admin account recovery. One-time recovery codes for elevated accounts,
 * a forced password change after an emergency reset, and an audit log of security
 * events (code generation and use, failed attempts, emergency resets).
 */
final class SchemaV2 {

    private static final String NOW = "(" + Sql.NOW + ")";

    static final List<String> STATEMENTS = List.of(
            "ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE",
            """
            CREATE TABLE recovery_codes (
                code_id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                code_hash TEXT NOT NULL,
                code_salt TEXT NOT NULL,
                iterations INTEGER NOT NULL CHECK (iterations >= 100000),
                created_at TIMESTAMP NOT NULL DEFAULT %s,
                used_at TIMESTAMP
            )
            """.formatted(NOW),
            "CREATE INDEX idx_recovery_codes_user ON recovery_codes(user_id, used_at)",
            """
            CREATE TABLE security_events (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
                event_type VARCHAR(40) NOT NULL,
                details TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT %s
            )
            """.formatted(NOW),
            "CREATE INDEX idx_security_events_user ON security_events(user_id, event_type, created_at)"
    );

    private SchemaV2() {
    }
}
