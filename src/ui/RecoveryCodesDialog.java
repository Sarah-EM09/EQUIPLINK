package src.ui;

import src.ui.components.Motion;
import src.ui.components.PrimaryButton;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Shows freshly generated recovery codes once, with copy and save options. */
public class RecoveryCodesDialog extends JDialog {

    public RecoveryCodesDialog(Window owner, String username, List<String> codes) {
        super(owner, "Your recovery codes", ModalityType.APPLICATION_MODAL);
        String text = "EquipLink recovery codes for " + username + "\n"
                + "Each code can reset your password once. Keep them somewhere safe.\n\n"
                + String.join("\n", codes) + "\n";

        JLabel intro = new JLabel("<html><div style='width:330px'><b>Save these codes now.</b> "
                + "They will not be shown again. If you forget your password, use one on the "
                + "login screen (Use a recovery code). Generating new codes cancels these.</div></html>");
        intro.setFont(AppTheme.regular(12));
        intro.setForeground(AppTheme.foreground());

        JTextArea list = new JTextArea(String.join("\n", codes));
        list.setEditable(false);
        list.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        list.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        list.setBackground(AppTheme.elevatedSurface());

        JCheckBox saved = new JCheckBox("I have saved these codes somewhere safe");
        saved.setOpaque(false);
        PrimaryButton copy = new PrimaryButton("Copy", PrimaryButton.Style.SECONDARY);
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null));
        PrimaryButton save = new PrimaryButton("Save as file", PrimaryButton.Style.SECONDARY);
        save.addActionListener(event -> saveToFile(text, username));
        PrimaryButton done = new PrimaryButton("Done");
        done.setEnabled(false);
        saved.addActionListener(event -> done.setEnabled(saved.isSelected()));
        done.addActionListener(event -> dispose());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                if (saved.isSelected() || UiSupport.showConfirmDialog(RecoveryCodesDialog.this,
                        "Close without confirming you saved the codes?", "Recovery codes",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                    dispose();
                }
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(copy);
        buttons.add(save);
        buttons.add(done);
        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        bottom.add(saved, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        root.add(intro, BorderLayout.NORTH);
        root.add(list, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(Motion.popIn(root));
        setResizable(false);
        pack();
        setLocationRelativeTo(owner);
    }

    private void saveToFile(String text, String username) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("equiplink-recovery-codes-" + username + ".txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), text, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            UiSupport.showMessageDialog(this, "Could not save the file: " + exception.getMessage(),
                    "Not saved", JOptionPane.ERROR_MESSAGE);
        }
    }
}
