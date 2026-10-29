package achievka;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/** Карточка в углу рабочего стола, поверх остальных окон. */
public final class Toasts {
    private static final List<Stage> open = new ArrayList<>();
    private static final double WIDTH = 360;
    private static final double HEIGHT = 96;

    private Toasts() {}

    public static void show(Stage main, Notifier.Notice notice) {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);

        Region accent = new Region();
        accent.setPrefWidth(6);
        accent.setStyle("-fx-background-color: linear-gradient(to bottom, #8ad7ff, #5fe0d0); -fx-background-radius: 6;");

        Text title = new Text(notice.title());
        title.setStyle("-fx-font-family: 'Segoe UI'; -fx-font-size: 16px; -fx-font-weight: 700; -fx-fill: #1b1d22;");
        Text body = new Text(notice.text());
        body.setWrappingWidth(300);
        body.setStyle("-fx-font-family: 'Segoe UI'; -fx-font-size: 13px; -fx-fill: #667085;");

        VBox words = new VBox(4, title, body);
        words.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(words, Priority.ALWAYS);

        HBox card = new HBox(12, accent, words);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(14, 16, 14, 12));
        card.setStyle("-fx-background-color: white; -fx-background-radius: 18; -fx-effect: dropshadow(gaussian, rgba(70,90,120,0.22), 22, 0.2, 0, 8);");
        card.setTranslateX(70);

        Scene scene = new Scene(card, WIDTH, HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        open.add(stage);
        place();
        stage.show();

        TranslateTransition slide = new TranslateTransition(Duration.millis(320), card);
        slide.setFromX(70);
        slide.setToX(0);
        slide.play();

        card.setOnMouseClicked(event -> {
            if (main != null) {
                main.setIconified(false);
                main.toFront();
            }
            close(stage, card);
        });

        PauseTransition wait = new PauseTransition(Duration.seconds(4.4));
        wait.setOnFinished(event -> close(stage, card));
        wait.play();
    }

    private static void close(Stage stage, HBox card) {
        if (!open.contains(stage)) {
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(220), card);
        fade.setToValue(0);
        fade.setOnFinished(event -> {
            open.remove(stage);
            stage.close();
            place();
        });
        fade.play();
    }

    private static void place() {
        var bounds = Screen.getPrimary().getVisualBounds();
        double y = bounds.getMaxY() - 24;
        for (int i = open.size() - 1; i >= 0; i--) {
            Stage stage = open.get(i);
            y -= HEIGHT;
            stage.setX(bounds.getMaxX() - WIDTH - 24);
            stage.setY(y);
            y -= 12;
        }
    }
}
