package src.database;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Date and time handling for SQLite, which has no date types. Values are stored as
 * ISO text in Philippine time so they sort and compare correctly in SQL:
 * dates {@code yyyy-MM-dd}, times {@code HH:mm:ss}, timestamps {@code yyyy-MM-dd HH:mm:ss}.
 * Use these helpers instead of the driver's setDate/getDate family, which would
 * store epoch numbers and cannot read text dates.
 */
public final class Sql {

    /** Current Philippine date-time, as SQL. Manila is UTC+8 all year (no DST). */
    public static final String NOW = "datetime('now', '+8 hours')";

    /** Current Philippine date, as SQL. */
    public static final String TODAY = "date('now', '+8 hours')";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Sql() {
    }

    // ---- writing -----------------------------------------------------------------

    public static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    public static String text(LocalTime time) {
        return time == null ? null : time.format(TIME);
    }

    public static String text(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(STAMP);
    }

    public static void setDate(PreparedStatement statement, int index, LocalDate date)
            throws SQLException {
        statement.setString(index, text(date));
    }

    public static void setTime(PreparedStatement statement, int index, LocalTime time)
            throws SQLException {
        statement.setString(index, text(time));
    }

    public static void setTimestamp(PreparedStatement statement, int index, LocalDateTime dateTime)
            throws SQLException {
        statement.setString(index, text(dateTime));
    }

    public static void setTimestamp(PreparedStatement statement, int index, Timestamp timestamp)
            throws SQLException {
        setTimestamp(statement, index, timestamp == null ? null : timestamp.toLocalDateTime());
    }

    // ---- errors ------------------------------------------------------------------

    /** True when a UNIQUE or PRIMARY KEY constraint rejected the write. */
    public static boolean isUniqueViolation(SQLException exception) {
        return resultCode(exception) == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                || resultCode(exception) == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY;
    }

    /** True when a FOREIGN KEY constraint rejected the write (e.g. a row still referenced). */
    public static boolean isForeignKeyViolation(SQLException exception) {
        return resultCode(exception) == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY;
    }

    private static org.sqlite.SQLiteErrorCode resultCode(SQLException exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof org.sqlite.SQLiteException sqlite) {
                return sqlite.getResultCode();
            }
        }
        return null;
    }

    // ---- reading -----------------------------------------------------------------

    public static Date getDate(ResultSet result, String column) throws SQLException {
        LocalDate date = parseDate(result.getString(column));
        return date == null ? null : Date.valueOf(date);
    }

    public static Time getTime(ResultSet result, String column) throws SQLException {
        LocalTime time = parseTime(result.getString(column));
        return time == null ? null : Time.valueOf(time);
    }

    public static Timestamp getTimestamp(ResultSet result, String column) throws SQLException {
        LocalDateTime dateTime = parseTimestamp(result.getString(column));
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    public static Timestamp getTimestamp(ResultSet result, int column) throws SQLException {
        LocalDateTime dateTime = parseTimestamp(result.getString(column));
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDate.parse(text.trim().substring(0, 10));
    }

    static LocalTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        int space = value.indexOf(' ');
        if (space > 0 && value.indexOf(':') > space) {
            value = value.substring(space + 1); // tolerate "yyyy-MM-dd HH:mm:ss"
        }
        return LocalTime.parse(value);
    }

    static LocalDateTime parseTimestamp(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim().replace('T', ' ');
        if (value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay();
        }
        return LocalDateTime.parse(value.replace(' ', 'T'));
    }
}
