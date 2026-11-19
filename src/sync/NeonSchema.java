package src.sync;

import java.util.List;

/**
 * Tables in Neon. Every synced row is stored once in {@code sync_rows} as JSON keyed by
 * (table, sync id); {@code seq} increases with every accepted change, so a computer
 * pulls everything after the last {@code seq} it saw. Pushes take an advisory lock so
 * {@code seq} values become visible in order and none are skipped.
 */
public final class NeonSchema {

    static final long PUSH_LOCK = 0x45514C4BL; // "EQLK"

    public static final List<String> STATEMENTS = List.of(
            "CREATE SEQUENCE IF NOT EXISTS sync_seq",
            """
            CREATE TABLE IF NOT EXISTS sync_rows (
                table_name TEXT NOT NULL,
                row_uuid TEXT NOT NULL,
                data JSONB,
                deleted BOOLEAN NOT NULL DEFAULT FALSE,
                changed_at TEXT NOT NULL,
                origin TEXT NOT NULL,
                seq BIGINT NOT NULL,
                synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (table_name, row_uuid)
            )
            """,
            "CREATE INDEX IF NOT EXISTS sync_rows_seq ON sync_rows(seq)",
            """
            CREATE TABLE IF NOT EXISTS sync_devices (
                device_id TEXT PRIMARY KEY,
                first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
                last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
                app_version TEXT
            )
            """
    );

    private NeonSchema() {
    }
}
