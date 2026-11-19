package src.ui;

import src.controller.AuthController;
import src.model.User;
import src.ui.components.PrimaryButton;
import src.ui.components.SurfacePanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.util.List;

/** Settings section for administrators: how many recovery codes remain, and regenerate. */
class RecoveryCodesSection extends SurfacePanel {

    private final User user;
    private final AuthController authController;
    private final JLabel status = new JLabel("Checking recovery codes...");
    private final PrimaryButton generate = new PrimaryButton("Generate recovery codes");

    RecoveryCodesSection(User user, AuthController authController) {
        this.user = user;
        this.authController = authController;
        setLayout(new BorderLayout(0, 12));
        setAlignmentX(LEFT_ALIGNMENT);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Account recovery");
        title.setFont(AppTheme.semibold(14));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel("One-time codes that reset your password if you forget it "
                + "and no other administrator can approve a reset.");
        subtitle.setFont(AppTheme.regular(10));
        subtitle.setForeground(AppTheme.muted());
        heading.add(title);
        heading.add(Box.createVerticalStrut(4));
        heading.add(subtitle);
        add(heading, BorderLayout.NORTH);

        status.setFont(AppTheme.regular(11));
        status.setForeground(AppTheme.muted());
        generate.addActionListener(event -> confirmAndGenerate());
        JPanel body = new JPanel(new BorderLayout(15, 0));
        body.setOpaque(false);
        body.add(status, BorderLayout.CENTER);
        body.add(generate, BorderLayout.EAST);
        add(body, BorderLayout.CENTER);
        refreshStatus();
    }

    private void refreshStatus() {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return authController.remainingRecoveryCodes(user);
            }

            @Override
            protected void done() {
                try {
                    int left = get();
                    status.setText(left == 0
                            ? "You have no recovery codes. Generate a set and keep it safe."
                            : left + " unused recovery code" + (left == 1 ? "" : "s") + " left.");
                    status.setForeground(left <= 2 ? AppTheme.danger() : AppTheme.muted());
                } catch (Exception exception) {
                    status.setText("Could not check recovery codes.");
                }
            }
        }.execute();
    }

    private void confirmAndGenerate() {
        if (UiSupport.showConfirmDialog(this,
                "Generate a new set of recovery codes?\nAny unused codes you saved before will stop working.",
                "Recovery codes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        if (!UiSupport.beginAction(generate)) {
            return;
        }
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return authController.generateRecoveryCodes(user);
            }

            @Override
            protected void done() {
                UiSupport.endAction(generate);
                try {
                    new RecoveryCodesDialog(SwingUtilities.getWindowAncestor(RecoveryCodesSection.this),
                            user.getUsername(), get()).setVisible(true);
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    UiSupport.showMessageDialog(RecoveryCodesSection.this, cause.getMessage(),
                            "Codes not generated", JOptionPane.ERROR_MESSAGE);
                }
                refreshStatus();
            }
        }.execute();
    }
}
