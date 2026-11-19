package src;

import src.database.DatabaseInitializer;
import src.service.RecoveryService;

import javax.swing.JOptionPane;
import javax.swing.JTextField;
import java.awt.GraphicsEnvironment;

/**
 * Last-resort administrator password reset, run on the computer that stores the
 * EquipLink database:
 * <pre>
 *   EquipLink.exe --reset-password &lt;username or email&gt;
 * </pre>
 * Prints (and, on a desktop, shows) a temporary password that must be changed at the
 * next sign-in. Only ICT System Admin, Facility Admin and Campus Director accounts can
 * be reset this way; the reset is logged and the other administrators are notified.
 */
final class EmergencyReset {

    private EmergencyReset() {
    }

    static void run(String[] args) {
        boolean desktop = !GraphicsEnvironment.isHeadless();
        if (args.length < 2 || args[1].isBlank()) {
            report(desktop, "Usage: EquipLink --reset-password <username or email>",
                    JOptionPane.WARNING_MESSAGE, null);
            System.exit(2);
            return;
        }
        try {
            DatabaseInitializer.initializeDatabase();
            String temporary = new RecoveryService().emergencyReset(args[1]);
            report(desktop, "Temporary password for " + args[1] + ":\n\n    " + temporary
                    + "\n\nSign in with it; you will be asked to choose a new password.",
                    JOptionPane.INFORMATION_MESSAGE, temporary);
            System.exit(0);
        } catch (Exception exception) {
            report(desktop, "Emergency reset failed: " + exception.getMessage(),
                    JOptionPane.ERROR_MESSAGE, null);
            System.exit(1);
        }
    }

    private static void report(boolean desktop, String message, int type, String copyable) {
        System.out.println(message);
        if (!desktop) {
            return;
        }
        Object body = message;
        if (copyable != null) {
            JTextField field = new JTextField(copyable);
            field.setEditable(false);
            body = new Object[] {
                    "Temporary password (select and copy):", field,
                    "Sign in with it; you will be asked to choose a new password." };
        }
        JOptionPane.showMessageDialog(null, body, "EquipLink emergency reset", type);
    }
}
