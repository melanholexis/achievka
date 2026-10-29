package achievka;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/** Окно приложения. Страница та же, но живёт внутри окна, не в браузере. */
public final class AppWindow extends Application {
    static volatile int port = 8741;

    @Override
    public void start(Stage stage) {
        WebView web = new WebView();
        web.getEngine().load("http://127.0.0.1:" + port + "/");
        web.setContextMenuEnabled(false);
        web.getEngine().setCreatePopupHandler(config -> openCourse(stage).getEngine());
        web.getEngine().setConfirmHandler(message -> confirm(stage, message));

        Scene scene = new Scene(web, 1280, 840);
        stage.setTitle("Ачивка");
        applyIcon(stage);
        stage.setMinWidth(980);
        stage.setMinHeight(680);
        stage.setScene(scene);
        installTray(stage);
        stage.setOnCloseRequest(event -> {
            Platform.exit();
            System.exit(0);
        });
        stage.show();
        Notifier.attach(notice -> Platform.runLater(() -> Toasts.show(stage, notice)));
    }

    private static WebView openCourse(Stage owner) {
        WebView popup = new WebView();
        Stage course = new Stage();
        course.initOwner(owner);
        course.setTitle("Курс — Ачивка");
        applyIcon(course);
        course.setScene(new Scene(popup, 1100, 800));
        course.show();
        return popup;
    }

    private static void applyIcon(Stage stage) {
        var stream = AppWindow.class.getResourceAsStream("/web/icon.png");
        if (stream != null) {
            stage.getIcons().add(new Image(stream));
        }
    }

    private static void installTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                var stream = AppWindow.class.getResourceAsStream("/web/icon.png");
                if (stream == null) {
                    return;
                }
                BufferedImage image = ImageIO.read(stream);
                TrayIcon tray = new TrayIcon(image.getScaledInstance(32, 32, java.awt.Image.SCALE_SMOOTH), "Ачивка");
                tray.setImageAutoSize(true);
                PopupMenu menu = new PopupMenu();
                MenuItem open = new MenuItem("Открыть");
                open.addActionListener(event -> Platform.runLater(() -> {
                    stage.setIconified(false);
                    stage.show();
                    stage.toFront();
                }));
                MenuItem exit = new MenuItem("Выход");
                exit.addActionListener(event -> Platform.runLater(() -> {
                    Platform.exit();
                    System.exit(0);
                }));
                menu.add(open);
                menu.add(exit);
                tray.setPopupMenu(menu);
                tray.addActionListener(event -> Platform.runLater(() -> {
                    stage.setIconified(false);
                    stage.show();
                    stage.toFront();
                }));
                SystemTray.getSystemTray().add(tray);
            } catch (Exception ignored) {
                // Трей на этой системе может быть недоступен. Окно при этом остаётся.
            }
        });
    }

    private static boolean confirm(Stage owner, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setTitle("Ачивка");
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
