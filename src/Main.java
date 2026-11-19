
package src;

import src.database.DatabaseInitializer;
import src.database.DatabaseSeeder;
import src.controller.AuthController;
import src.controller.BorrowRequestController;
import src.controller.EquipmentController;
import src.controller.NotificationController;
import src.layout.MainLayout;
import src.model.User;
import src.service.AuthService;
import src.service.BorrowRequestService;
import src.service.EquipmentService;
import src.service.NotificationService;
import src.ui.LoginFrame;
import src.ui.SplashWindow;
import src.util.AppInfo;
import src.util.Updater;
import src.ui.UiSupport;

import javax.swing.*;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "--reset-password".equals(args[0])) {
            EmergencyReset.run(args);
            return;
        }

        SwingUtilities.invokeAndWait(src.ui.AppTheme::install);

        // Splash plays the logo animation once while the database and update check run.
        CountDownLatch animationDone = new CountDownLatch(1);
        SplashWindow[] splash = new SplashWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            splash[0] = new SplashWindow(animationDone::countDown);
            splash[0].setVisible(true);
        });
        CompletableFuture<Updater.Release> update = CompletableFuture
                .supplyAsync(Updater::check)
                .completeOnTimeout(null, 15, TimeUnit.SECONDS);

        try {

            DatabaseInitializer.initializeDatabase();

            DatabaseSeeder.seedAccounts();

            System.out.println("EquipLink is ready.");

        } catch (SQLException exception) {

            exception.printStackTrace();
            animationDone.await();
            SwingUtilities.invokeAndWait(() -> splash[0].dispose());

            UiSupport.showMessageDialog(
                    null,
                    "Unable to initialize EquipLink database.\n"
                            + exception.getMessage(),
                    "Database Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        animationDone.await();
        Updater.Release release = update.join();

        SwingUtilities.invokeLater(() -> {
            Runnable openLogin = () -> {
                splash[0].dispose();

                AuthService authService = new AuthService();

                AuthController authController = new AuthController(
                        authService);
                BorrowRequestController requestController =
                        new BorrowRequestController(new BorrowRequestService());
                EquipmentController equipmentController =
                        new EquipmentController(new EquipmentService());
                NotificationController notificationController =
                        new NotificationController(new NotificationService());

                showLogin(authController, requestController, equipmentController,
                        notificationController);
            };
            if (release == null) {
                openLogin.run();
            } else {
                offerUpdate(release, splash[0], openLogin);
            }
        });
    }

    /** Asks to install a newer release; installs and exits, or continues to login. */
    private static void offerUpdate(Updater.Release release, SplashWindow splash, Runnable skip) {
        int choice = JOptionPane.showOptionDialog(
                splash,
                "EquipLink " + release.version() + " is available (you have "
                        + AppInfo.version() + ").\nInstall it now? EquipLink will restart.",
                "Update available",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                new Object[] { "Update now", "Later" },
                "Update now");
        if (choice != 0) {
            skip.run();
            return;
        }
        splash.setStatus("Downloading EquipLink " + release.version() + "...");
        Thread worker = new Thread(() -> {
            try {
                Updater.downloadAndInstall(release);
                System.exit(0);
            } catch (Exception exception) {
                exception.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    UiSupport.showMessageDialog(
                            splash,
                            "The update could not be installed.\n" + exception.getMessage(),
                            "Update failed",
                            JOptionPane.WARNING_MESSAGE);
                    skip.run();
                });
            }
        }, "equiplink-updater");
        worker.setDaemon(true);
        worker.start();
    }

    private static void showLogin(
            AuthController authController,
            BorrowRequestController requestController,
            EquipmentController equipmentController,
            NotificationController notificationController) {
        LoginFrame loginFrame = new LoginFrame(
                authController,
                user -> showDashboard(
                        user, authController, requestController,
                        equipmentController, notificationController));
        loginFrame.setVisible(true);
    }

    private static void showDashboard(
            User user,
            AuthController authController,
            BorrowRequestController requestController,
            EquipmentController equipmentController,
            NotificationController notificationController) {
        MainLayout dashboard = new MainLayout(
                user, authController, requestController,
                equipmentController, notificationController);
        JFrame frame = new JFrame("EquipLink | " + user.getRole());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImages(src.ui.BrandLogo.windowIcons());
        frame.setMinimumSize(new java.awt.Dimension(960, 640));
        frame.setSize(1280, 800);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(dashboard);
        dashboard.setLogoutAction(() -> {
            frame.dispose();
            showLogin(authController, requestController,
                    equipmentController, notificationController);
        });
        frame.setVisible(true);
        requirePasswordChangeIfNeeded(user, authController, frame, () -> {
            frame.dispose();
            showLogin(authController, requestController,
                    equipmentController, notificationController);
        });
    }

    /**
     * After an emergency reset the account holds a temporary password; make the user
     * choose a new one before working. Closing without changing signs them out.
     */
    private static void requirePasswordChangeIfNeeded(
            User user, AuthController authController, JFrame frame, Runnable signOut) {
        try {
            if (!authController.mustChangePassword(user)) {
                return;
            }
            UiSupport.showMessageDialog(frame,
                    "You signed in with a temporary password.\nChoose a new password to continue.",
                    "Change your password", JOptionPane.INFORMATION_MESSAGE);
            new src.ui.PasswordChangeDialog(frame, user.getUserId(), authController).setVisible(true);
            if (authController.mustChangePassword(user)) {
                UiSupport.showMessageDialog(frame,
                        "Your password was not changed, so you have been signed out.",
                        "Signed out", JOptionPane.WARNING_MESSAGE);
                signOut.run();
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
