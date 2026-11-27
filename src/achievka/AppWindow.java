package achievka;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import javax.imageio.ImageIO;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;

/** Окно приложения. Страница та же, но живёт внутри окна, не в браузере. */
public final class AppWindow extends Application {
    static volatile String dashboardUrl;
    private TrayIcon tray;
    private NativeActions actions;

    @Override
    public void start(Stage stage) {
        WebView web = new WebView();
        useProfileDirectory(web);
        actions = new NativeActions(stage);
        URI dashboard = URI.create(dashboardUrl);
        web.getEngine().getLoadWorker().stateProperty().addListener((observable, before, after) -> {
            if (after != javafx.concurrent.Worker.State.SUCCEEDED) return;
            URI loaded = URI.create(web.getEngine().getLocation());
            if (dashboard.getScheme().equals(loaded.getScheme()) && dashboard.getAuthority().equals(loaded.getAuthority())
                && "/".equals(loaded.getPath())) {
                JSObject window = (JSObject) web.getEngine().executeScript("window");
                window.setMember("achievkaDesktop", actions);
                WindowsChrome.apply(stage.getTitle(), "dark".equals(
                    web.getEngine().executeScript("document.documentElement.dataset.theme")));
            }
        });
        web.setContextMenuEnabled(false);
        web.getEngine().setConfirmHandler(message -> confirm(stage, message));

        Scene scene = new Scene(web, 1280, 840);
        stage.setTitle("Ачивка");
        applyIcon(stage);
        stage.setMinWidth(980);
        stage.setMinHeight(680);
        stage.setScene(scene);
        installTray(stage);
        stage.setOnCloseRequest(event -> Platform.exit());
        stage.show();
        WindowsChrome.apply(stage.getTitle(), true);
        web.getEngine().load(dashboardUrl);
        Notifier.attach(notice -> Platform.runLater(() -> Toasts.show(stage, notice)));
    }

    private static void useProfileDirectory(WebView web) {
        String profile = System.getProperty("achievka.profile");
        if (profile != null) web.getEngine().setUserDataDirectory(new File(profile, "webview"));
    }

    private static void openCourse(Stage owner, String url) {
        WebView popup = new WebView();
        useProfileDirectory(popup);
        Stage course = new Stage();
        course.initOwner(owner);
        course.setTitle("Курс — Ачивка");
        applyIcon(course);
        popup.getEngine().setPromptHandler(request -> {
            var dialog = new javafx.scene.control.TextInputDialog(request.getDefaultValue());
            dialog.initOwner(course);
            dialog.setTitle("Комната героя");
            dialog.setHeaderText(request.getMessage());
            return dialog.showAndWait().orElse(null);
        });
        popup.getEngine().setConfirmHandler(message -> confirm(course, message));
        course.setScene(new Scene(popup, 1100, 800));
        course.show();
        String query = URI.create(url).getQuery();
        boolean light = query != null && Arrays.asList(query.split("&")).contains("theme=light");
        WindowsChrome.apply(course.getTitle(), !light);
        popup.getEngine().load(url);
    }

    private static void applyIcon(Stage stage) {
        var stream = AppWindow.class.getResourceAsStream("/web/icon.png");
        if (stream != null) {
            stage.getIcons().add(new Image(stream));
        }
    }

    @Override
    public void stop() {
        if (tray != null) SystemTray.getSystemTray().remove(tray);
    }

    private void installTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            try (var stream = AppWindow.class.getResourceAsStream("/web/icon.png")) {
                if (stream == null) {
                    return;
                }
                tray = new TrayIcon(ImageIO.read(stream), "Ачивка");
                tray.setImageAutoSize(true);
                PopupMenu menu = new PopupMenu();
                MenuItem open = new MenuItem("Открыть");
                open.addActionListener(event -> Platform.runLater(() -> {
                    stage.setIconified(false);
                    stage.show();
                    stage.toFront();
                }));
                MenuItem exit = new MenuItem("Выход");
                exit.addActionListener(event -> Platform.runLater(Platform::exit));
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

    public final class NativeActions {
        private final Stage owner;

        NativeActions(Stage owner) {
            this.owner = owner;
        }

        public void open(String url, boolean external) {
            URI uri = CourseBook.webUri(url);
            if (external) getHostServices().showDocument(uri.toString());
            else if ("127.0.0.1".equals(uri.getHost()) && "/course/".equals(uri.getPath())) openCourse(owner, url);
        }

        public void setDarkTitle(boolean dark) {
            WindowsChrome.apply(owner.getTitle(), dark);
        }

        public void copy(String text) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        }

        public boolean saveBackup(String json) {
            FileChooser dialog = new FileChooser();
            dialog.setTitle("Сохранить резервную копию");
            dialog.setInitialFileName("achievka-progress.json");
            dialog.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
            File file = dialog.showSaveDialog(owner);
            if (file == null) return false;
            Path path = file.toPath();
            Storage.write(path, json);
            return true;
        }
    }

    private static boolean confirm(Stage owner, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setTitle("Ачивка");
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
