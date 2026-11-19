package src.ui;

import src.controller.AuthController;
import src.ui.components.Motion;
import src.ui.components.PrimaryButton;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.Arrays;

/** Login-screen dialog: reset an administrator password with a one-time recovery code. */
public class RecoveryCodeLoginDialog extends JDialog {

    private final AuthController authController;
    private final JTextField username = new JTextField();
    private final JTextField code = new JTextField();
    private final JPasswordField newPassword = new JPasswordField();
    private final JPasswordField confirmPassword = new JPasswordField();
    private final PrimaryButton reset = new PrimaryButton("Reset password");

    public RecoveryCodeLoginDialog(Window owner, AuthController authController) {
        super(owner, "Use a recovery code", ModalityType.APPLICATION_MODAL);
        this.authController = authController;

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));

        JLabel title = new JLabel("Reset an administrator password");
        title.setFont(AppTheme.semibold(16));
        title.setForeground(AppTheme.foreground());
        JLabel hint = new JLabel("<html><div style='width:320px'>For ICT System Admin, Facility Admin "
                + "and Campus Director accounts. Enter one of the recovery codes you saved; "
                + "each code works once.</div></html>");
        hint.setFont(AppTheme.regular(11));
        hint.setForeground(AppTheme.muted());
        form.add(title);
        form.add(Box.createVerticalStrut(6));
        form.add(hint);
        form.add(Box.createVerticalStrut(16));
        field(form, "Username or email", username);
        code.putClientProperty("JTextField.placeholderText", "XXXXX-XXXXX");
        field(form, "Recovery code", code);
        field(form, "New password (at least 8 characters)", newPassword);
        field(form, "Confirm new password", confirmPassword);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        PrimaryButton cancel = new PrimaryButton("Cancel", PrimaryButton.Style.SECONDARY);
        cancel.addActionListener(event -> dispose());
        reset.addActionListener(event -> submit());
        actions.add(cancel);
        actions.add(reset);

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        actions.setBorder(BorderFactory.createEmptyBorder(0, 24, 18, 24));
        root.add(actions, BorderLayout.SOUTH);
        setContentPane(Motion.popIn(root));
        getRootPane().setDefaultButton(reset);
        setResizable(false);
        pack();
        setLocationRelativeTo(owner);
    }

    private static void field(JPanel form, String label, JTextField input) {
        JLabel text = new JLabel(label);
        text.setFont(AppTheme.semibold(11));
        text.setForeground(AppTheme.foreground());
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        input.setPreferredSize(new Dimension(340, 38));
        form.add(text);
        form.add(Box.createVerticalStrut(5));
        form.add(input);
        form.add(Box.createVerticalStrut(12));
    }

    private void submit() {
        char[] password = newPassword.getPassword();
        char[] confirm = confirmPassword.getPassword();
        if (!Arrays.equals(password, confirm)) {
            Arrays.fill(password, '\0');
            Arrays.fill(confirm, '\0');
            UiSupport.showMessageDialog(this, "The new passwords do not match.",
                    "Check your password", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Arrays.fill(confirm, '\0');
        if (!UiSupport.beginAction(reset)) {
            Arrays.fill(password, '\0');
            return;
        }
        String login = username.getText();
        String entered = code.getText();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    authController.resetWithRecoveryCode(login, entered, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
                return null;
            }

            @Override
            protected void done() {
                UiSupport.endAction(reset);
                try {
                    get();
                    UiSupport.showMessageDialog(RecoveryCodeLoginDialog.this,
                            "Your password was reset. Sign in with your new password.\n"
                                    + "That recovery code can no longer be used.",
                            "Password reset", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    UiSupport.showMessageDialog(RecoveryCodeLoginDialog.this, cause.getMessage(),
                            "Password not reset", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
