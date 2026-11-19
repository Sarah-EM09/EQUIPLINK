
package src.database;

import src.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class DatabaseSeeder {

    public static void seedAccounts() throws Exception {

        try (Connection connection = Database.getConnection()) {

            connection.setAutoCommit(false);

            try {

                // SYSTEM ADMIN - ICT STAFF
                seedAccount(
                        connection,
                        "systemadmin",
                        "ict@equiplink.local",
                        "ICT System Administrator",
                        "Information and Communications Technology",
                        "ICT_STAFF",
                        "ICTAdmin123!");

                // FACILITY STAFF
                seedAccount(
                        connection,
                        "facilitystaff",
                        "facilitystaff@equiplink.local",
                        "Facility Staff",
                        "Facilities",
                        "FACILITY_STAFF",
                        "FacilityStaff123!");

                // FACILITY ADMIN
                seedAccount(
                        connection,
                        "facilityadmin",
                        "facilityadmin@equiplink.local",
                        "Facility Administrator",
                        "Administration",
                        "ADMIN",
                        "FacilityAdmin123!");

                // CAMPUS DIRECTOR
                seedAccount(
                        connection,
                        "campusdirector",
                        "director@equiplink.local",
                        "Campus Director",
                        "Office of the Campus Director",
                        "DIRECTOR",
                        "Director123!");

                connection.commit();

                System.out.println(
                        "EquipLink default accounts seeded successfully.");

            } catch (Exception ex) {

                connection.rollback();
                throw ex;

            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void seedAccount(
            Connection connection,
            String username,
            String email,
            String fullName,
            String department,
            String role,
            String password) throws Exception {

        String checkSql = """
                SELECT COUNT(*)
                FROM users
                WHERE LOWER(username) = LOWER(?)
                   OR LOWER(email) = LOWER(?)
                """;

        try (PreparedStatement check = connection.prepareStatement(checkSql)) {

            check.setString(1, username);
            check.setString(2, email);

            try (ResultSet result = check.executeQuery()) {

                result.next();

                if (result.getInt(1) > 0) {
                    System.out.println(
                            username + " already exists. Skipping.");
                    return;
                }
            }
        }

        String salt = PasswordHasher.generateSalt();

        String passwordHash = PasswordHasher.hashPassword(
                password.toCharArray(),
                salt);

        String insertSql = """
                INSERT INTO users (
                    username,
                    email,
                    password_hash,
                    password_salt,
                    password_iterations,
                    role,
                    borrower_type,
                    full_name,
                    department,
                    active,
                    sync_uuid
                )
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, TRUE, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {

            statement.setString(1, username);
            statement.setString(2, email);
            statement.setString(3, passwordHash);
            statement.setString(4, salt);
            statement.setInt(5, PasswordHasher.getIterations());
            statement.setString(6, role);
            statement.setString(7, fullName);
            statement.setString(8, department);
            // Same sync id on every computer, so default accounts merge in Neon.
            statement.setString(9, src.sync.SyncTables.seedUuid(username));

            statement.executeUpdate();

            // Mark the default row as older than any real change, so a newly installed
            // computer never overwrites an account another computer already updated.
            try (PreparedStatement age = connection.prepareStatement(
                    "UPDATE sync_outbox SET changed_at = '2000-01-01 00:00:00.000' "
                            + "WHERE table_name = 'users' AND row_id = last_insert_rowid()")) {
                age.executeUpdate();
            }

            System.out.println(
                    username + " account created successfully.");
        }
    }
}
