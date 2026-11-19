
package src.ui;

import src.controller.AuthController;
import src.model.User;
import src.ui.components.AuthBrandPanel;
import src.ui.components.PrimaryButton;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.function.Consumer;

public class LoginFrame extends LoginFramePart1 {

    public LoginFrame(
            AuthController authController,
            Consumer<User> onLoginSuccess) {

        this.authController = authController;
        this.onLoginSuccess = onLoginSuccess;

        setTitle("EquipLink | Login");
        setIconImages(src.ui.BrandLogo.windowIcons());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 720));
        setSize(1040, 760);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(AppTheme.background());

        root.add(
                new AuthBrandPanel(
                        "Equipment that keeps campus moving.",
                        "Request the resources you need, follow your request status, and keep every campus activity organized."
                ),
                BorderLayout.WEST
        );

        root.add(createLoginForm(), BorderLayout.CENTER);

        setContentPane(root);
    }

    // =========================================
    // GLASS LOGIN FORM
    // =========================================

    JPanel createLoginForm() {

        GlassBackgroundPanel area = new GlassBackgroundPanel();
        area.setLayout(new GridBagLayout());

        // Glass card
        GlassCard glassCard = new GlassCard();
        glassCard.setLayout(new BorderLayout());
        glassCard.setBorder(new EmptyBorder(24, 22, 24, 22));
        glassCard.setPreferredSize(new Dimension(420, 630));

        // Form content
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(20, 18, 20, 18));
        form.setPreferredSize(new Dimension(376, 580));

        JLabel eyebrow = new JLabel("WELCOME BACK");
        eyebrow.setFont(AppTheme.semibold(10.5f));
        eyebrow.setForeground(AppTheme.primary());
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel heading = new JLabel("Sign in to your account");
        heading.setFont(AppTheme.semibold(24));
        heading.setForeground(AppTheme.foreground());
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel(
                "Enter your details to continue to EquipLink."
        );
        subtitle.setFont(AppTheme.regular(12));
        subtitle.setForeground(AppTheme.muted());
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        ImageIcon logoImage = BrandLogo.icon(96);
        if (logoImage != null) {
            // The splash already played the animation; show its resting frame here.
            JLabel logo = new JLabel(logoImage);
            JPanel logoRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            logoRow.setOpaque(false);
            logoRow.add(logo);
            logoRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, logoImage.getIconHeight()));
            logoRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(logoRow);
            form.add(Box.createVerticalStrut(16));
        }

        form.add(eyebrow);
        form.add(Box.createVerticalStrut(8));

        form.add(heading);
        form.add(Box.createVerticalStrut(7));

        form.add(subtitle);
        form.add(Box.createVerticalStrut(27));

        // Input fields
        addField(form, "Username or email", username);
        JPanel passwordRow = new JPanel(new BorderLayout(0, 0));
        passwordRow.setOpaque(false);
        passwordRow.add(password, BorderLayout.CENTER);
        char defaultEcho = password.getEchoChar();
        JToggleButton togglePassword = new JToggleButton(
                new src.ui.components.EyeIcon(true, AppTheme.muted(), 18));
        togglePassword.setSelectedIcon(
                new src.ui.components.EyeIcon(false, AppTheme.primary(), 18));
        togglePassword.setToolTipText("Show password");
        togglePassword.getAccessibleContext().setAccessibleName("Show or hide password");
        togglePassword.setPreferredSize(new Dimension(44, 45));
        togglePassword.setFocusable(false);
        togglePassword.setBorder(BorderFactory.createEmptyBorder());
        togglePassword.setContentAreaFilled(false);
        togglePassword.setOpaque(false);
        togglePassword.setForeground(AppTheme.muted());
        togglePassword.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        togglePassword.addActionListener(event -> {
            if (togglePassword.isSelected()) {
                password.setEchoChar((char) 0);
                togglePassword.setToolTipText("Hide password");
            } else {
                password.setEchoChar(defaultEcho);
                togglePassword.setToolTipText("Show password");
            }
        });
        passwordRow.add(togglePassword, BorderLayout.EAST);
        addField(form, "Password", passwordRow);

        // Password assistance
        JPanel passwordHelp = new JPanel(new BorderLayout());
        passwordHelp.setOpaque(false);

        JLabel securityHint = new JLabel(
                "Your account is protected."
        );

        securityHint.setFont(AppTheme.regular(10.5f));
        securityHint.setForeground(AppTheme.muted());

        JButton forgotPassword = new JButton("Forgot password?");
        styleLink(forgotPassword);

        forgotPassword.addActionListener(event ->
                new ForgotPasswordDialog(
                        this,
                        authController
                ).setVisible(true)
        );

        passwordHelp.add(securityHint, BorderLayout.WEST);
        passwordHelp.add(forgotPassword, BorderLayout.EAST);

        passwordHelp.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordHelp.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 25)
        );

        form.add(passwordHelp);

        // Administrators who saved recovery codes can reset without another approver.
        JButton recoveryCode = new JButton("Admin? Use a recovery code");
        styleLink(recoveryCode);
        recoveryCode.addActionListener(event ->
                new RecoveryCodeLoginDialog(this, authController).setVisible(true));
        JPanel recoveryRow = new JPanel(new BorderLayout());
        recoveryRow.setOpaque(false);
        recoveryRow.add(recoveryCode, BorderLayout.EAST);
        recoveryRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        recoveryRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        form.add(recoveryRow);
        form.add(Box.createVerticalStrut(16));

        // Login button
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 46)
        );
        loginButton.setPreferredSize(new Dimension(330, 46));

        loginButton.setCursor(
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        );

        loginButton.addActionListener(event -> doLogin());

        form.add(loginButton);
        form.add(Box.createVerticalStrut(18));

        // Registration
        JPanel registerRow = new JPanel(
                new FlowLayout(FlowLayout.CENTER, 4, 0)
        );

        registerRow.setOpaque(false);

        JLabel prompt = new JLabel("New to EquipLink?");
        prompt.setFont(AppTheme.regular(10));
        prompt.setForeground(AppTheme.muted());

        JButton register = new JButton(
                "Create a student account"
        );

        styleLink(register);
        register.setFont(AppTheme.semibold(10));

        register.addActionListener(event ->
                new RegisterFrame(
                        this,
                        authController
                ).setVisible(true)
        );

        registerRow.add(prompt);
        registerRow.add(register);

        registerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        form.add(registerRow);

        glassCard.add(form, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.weighty = 1;
        constraints.anchor = GridBagConstraints.CENTER;

        area.add(src.ui.components.Motion.popIn(glassCard, 120), constraints);

        return area;
    }

    // =========================================
    // STANDARD BUTTON STYLE
    // =========================================

    static void styleButton(JButton button) {

        button.setBackground(AppTheme.primary());
        button.setForeground(Color.WHITE);
        button.setFont(AppTheme.semibold(12));

        button.setFocusPainted(false);

        button.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(
                                AppTheme.primary()
                        ),
                        new EmptyBorder(10, 18, 10, 18)
                )
        );

        button.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );
    }
}
