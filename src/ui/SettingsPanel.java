package src.ui;

import src.controller.AuthController;
import src.model.User;
import src.sync.SyncService;
import src.ui.components.PrimaryButton;
import src.ui.components.SurfacePanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.function.Consumer;

public class SettingsPanel extends JPanel {

    public SettingsPanel(
            User user,
            AuthController authController,
            boolean notificationsEnabled,
            Runnable onThemeChange,
            Consumer<Boolean> onNotificationsChange) {

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.background());
        setBorder(new EmptyBorder(2, 2, 22, 2));
        JLabel heading = new JLabel("Preferences & account");
        heading.setFont(AppTheme.semibold(18));
        heading.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel("Personalize how EquipLink works for you.");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        add(heading);
        add(Box.createVerticalStrut(4));
        add(subtitle);
        add(Box.createVerticalStrut(14));

        add(appearanceSettings(onThemeChange));
        add(Box.createVerticalStrut(10));
        add(notificationSettings(notificationsEnabled, onNotificationsChange));
        add(Box.createVerticalStrut(10));
        add(securitySettings(user, authController));
        add(Box.createVerticalStrut(10));
        if (AuthController.isAdministrator(user)) {
            add(new RecoveryCodesSection(user, authController));
            add(Box.createVerticalStrut(10));
        }
        add(systemInformation());
        add(Box.createVerticalStrut(10));
        add(syncSettings());
    }

    private JPanel appearanceSettings(Runnable onThemeChange) {
        SurfacePanel panel = section("Appearance", "Choose a comfortable look for EquipLink.");
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.setOpaque(false);
        PrimaryButton light = new PrimaryButton(
                "Light mode", AppTheme.isDarkMode()
                        ? PrimaryButton.Style.SECONDARY : PrimaryButton.Style.PRIMARY);
        PrimaryButton dark = new PrimaryButton(
                "Dark mode", AppTheme.isDarkMode()
                        ? PrimaryButton.Style.PRIMARY : PrimaryButton.Style.SECONDARY);
        light.addActionListener(event -> {
            AppTheme.setDarkMode(false);
            onThemeChange.run();
        });
        dark.addActionListener(event -> {
            AppTheme.setDarkMode(true);
            onThemeChange.run();
        });
        options.add(light);
        options.add(dark);
        panel.add(options, BorderLayout.CENTER);
        return panel;
    }

    private JPanel notificationSettings(
            boolean enabled,
            Consumer<Boolean> onNotificationsChange) {
        SurfacePanel panel = section(
                "Notification settings", "Choose whether to receive request updates.");
        JCheckBox toggle = new JCheckBox("Enable notifications", enabled);
        styleOption(toggle);
        PrimaryButton save = new PrimaryButton("Save");
        save.setToolTipText("Save your notification preference");
        save.addActionListener(event -> {
            if (!UiSupport.beginAction(save)) {
                return;
            }
            try {
                onNotificationsChange.accept(toggle.isSelected());
                UiSupport.showMessageDialog(this,
                        "Notification preference saved.",
                        "Settings saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (RuntimeException exception) {
                UiSupport.showMessageDialog(this,
                        "Unable to save notification preference: " + exception.getMessage(),
                        "Settings not saved", JOptionPane.ERROR_MESSAGE);
            } finally {
                UiSupport.endAction(save);
            }
        });
        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.setOpaque(false);
        body.add(toggle, BorderLayout.CENTER);
        body.add(save, BorderLayout.EAST);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel securitySettings(User user, AuthController authController) {
        SurfacePanel panel = section(
                "Security settings", "Keep your account credentials up to date.");
        JPanel body = new JPanel(new BorderLayout(15, 0));
        body.setOpaque(false);
        JLabel note = new JLabel("Change your password using your current password.");
        note.setFont(AppTheme.regular(11));
        note.setForeground(AppTheme.muted());
        PrimaryButton changePassword = new PrimaryButton("Change password");
        changePassword.addActionListener(event -> {
            Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
            new PasswordChangeDialog(owner, user.getUserId(), authController).setVisible(true);
        });
        body.add(note, BorderLayout.CENTER);
        body.add(changePassword, BorderLayout.EAST);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel systemInformation() {
        SurfacePanel panel = section(
                "System information", "About this campus equipment borrowing system.");
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        addInfo(details, "Application name", "EquipLink");
        addInfo(details, "Application version", "1.0");
        addInfo(details, "Database", "SQLite (stored on this computer)");
        addInfo(details, "About EquipLink",
                "A single place to request and manage campus equipment.");
        addInfo(details, "Help & support",
                "Contact your campus system administrator for assistance.");
        panel.add(details, BorderLayout.CENTER);
        return panel;
    }

    private JPanel syncSettings() {
        SurfacePanel panel = section(
                "Synchronization", "Changes on this computer sync to Neon.");
        JLabel status = new JLabel("Working offline (Neon sync is not configured)");
        status.setFont(AppTheme.regular(11));
        status.setForeground(AppTheme.foreground());
        panel.add(status, BorderLayout.CENTER);
        SyncService.get().addListener(
                latest -> SwingUtilities.invokeLater(() -> updateSyncStatus(status, latest)));
        return panel;
    }

    private void updateSyncStatus(JLabel status, SyncService.Status latest) {
        String text = switch (latest.state()) {
            case ONLINE -> "Online · synced";
            case SYNCING -> "Syncing with Neon...";
            case DISABLED -> "Working offline (Neon sync is not configured)";
            case OFFLINE -> "Offline · changes are saved on this computer";
        };
        if (latest.pending() > 0) {
            text += " · " + latest.pending() + " pending";
        }
        status.setText(text);
    }

    private SurfacePanel section(String titleText, String subtitleText) {
        SurfacePanel panel = new SurfacePanel();
        panel.setLayout(new BorderLayout(0, 12));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(titleText);
        title.setFont(AppTheme.semibold(14));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel(subtitleText);
        subtitle.setFont(AppTheme.regular(10));
        subtitle.setForeground(AppTheme.muted());
        heading.add(title);
        heading.add(Box.createVerticalStrut(4));
        heading.add(subtitle);
        panel.add(heading, BorderLayout.NORTH);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private void styleOption(JCheckBox option) {
        option.setOpaque(false);
        option.setFont(AppTheme.regular(12));
        option.setForeground(AppTheme.foreground());
        option.setBorder(new EmptyBorder(0, 0, 0, 18));
    }

    private void addInfo(JPanel panel, String labelText, String valueText) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        JLabel label = new JLabel(labelText);
        label.setFont(AppTheme.regular(10));
        label.setForeground(AppTheme.muted());
        JLabel value = new JLabel(valueText);
        value.setFont(AppTheme.regular(11));
        value.setForeground(AppTheme.foreground());
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        panel.add(row);
    }
}
