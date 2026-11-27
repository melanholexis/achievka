package achievka;

import java.nio.file.*;
import java.util.function.*;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.concurrent.Worker;
import javafx.scene.web.*;
import javafx.stage.*;
import javafx.util.Duration;

public class UiCheck extends Application {
    static int result = 1;
    Main app;
    AppWindow ui;
    Stage dashboard;
    WebEngine engine;
    private com.sun.net.httpserver.HttpServer example;
    long deadline = System.nanoTime() + 50_000_000_000L;

    public static void main(String[] args) {
        launch(args);
        System.exit(result);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Path root = Files.createTempDirectory(Path.of("out"), "ui-check-");
        System.setProperty("achievka.profile", root.toString());
        app = new Main(new AccountStore(root.resolve("accounts.json")),
            new CourseBook(root.resolve("courses.json")),
            Files.readString(Path.of("course-site/course-data.js")));
        example = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        example.createContext("/", exchange -> {
            byte[] data = "<html><body><li class='incomplete'>Lesson Alpha</li><li class='done'>Lesson Bravo</li></body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/") ? 200 : 404,data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        example.start();
        app.initialCourse();
        app.start();
        AppWindow.dashboardUrl = app.launchUrl();
        dashboard = stage;
        ui = new AppWindow();
        ui.start(stage);
        engine = ((WebView) stage.getScene().getRoot()).getEngine();
        waitFor("приложение", () -> loaded(engine) && truth(engine, "Boolean(window.achievkaDesktop && document.getElementById('gate-form'))"), () -> {
            engine.executeScript("""
                document.getElementById('gate-name').value = 'Проверка';
                document.querySelector('#gate-form [type=submit]').click();
                """);
            waitFor("вход", () -> truth(engine, "document.getElementById('gate').hidden"), () -> {
                previewImport();
            });
        });
    }

    private void previewImport() {
        String address = "http://127.0.0.1:" + example.getAddress().getPort() + "/";
        engine.executeScript("""
            show('courses');
            const form=document.querySelector('.course-form');
            form.querySelector('input').value=%s;
            form.querySelector('input').dispatchEvent(new Event('input'));
            form.querySelector('[type=submit]').click();
            """.formatted(Json.str(address)));
        waitFor("предпросмотр", () -> truth(engine,"document.querySelectorAll('.import-list input').length===2"), () -> {
            engine.executeScript("""
                const boxes=document.querySelectorAll('.import-list input');
                if(boxes[0].checked || !boxes[1].checked || state.courseId!=='python') throw Error('Preview mismatch');
                document.querySelector('.import-preview .actions button').click();
                """);
            waitFor("импорт сайта", () -> truth(engine,"state.courseId!=='python' && state.solvedCount===1 && state.xp===10"), () -> {
                engine.executeScript("selectCourse('python')");
                waitFor("встроенный курс", () -> truth(engine,"state.courseId==='python' && !document.getElementById('view-home').hidden"), this::startCourse);
            });
        });
    }

    private void startCourse() {
        engine.executeScript("document.querySelector('.quest-hero .primary').click()");
        waitFor("страница курса", () -> course() != null && loaded(course()) &&
            truth(course(),"Boolean(document.querySelector('.task.open [data-action=check]'))"), () -> {
            WebEngine page = course(); original = page;
            page.executeScript("""
                document.querySelector('.task.open .option[data-index="1"]').click();
                document.querySelector('.task.open [data-action=check]').click();
                """);
            waitFor("сдача теста", () -> truth(engine,"state.xp === 10"), () -> visitShop(page));
        });
    }

    private void visitShop(WebEngine page) {
        page.executeScript("""
            window.AchievkaCourse.save({...window.AchievkaCourse.read(), coins:150});
            document.querySelector('a[href="game.html"]').click();
            """);
        waitFor("магазин", () -> loaded(page) && truth(page,"typeof buyItem === 'function' && state.coins === 150"), () -> {
            page.setPromptHandler(prompt -> null);
            page.executeScript("buyItem('rename'); if (state.coins !== 150) throw Error('Cancel charged coins')");
            page.setPromptHandler(prompt -> "Лис");
            page.executeScript("""
                buyItem('rename'); buyItem('double');
                if (state.coins !== 35 || state.characterName !== 'Лис') throw Error('Purchase failed');
                location.href='index.html?task=5';
                """);
            waitFor("практика", () -> loaded(page) && truth(page,"Boolean(document.querySelector('.task.open .code-editor'))"), () -> solveCode(page));
        });
    }

    private void solveCode(WebEngine page) {
        page.executeScript("""
            const editor = document.querySelector('.task.open .code-editor');
            document.querySelector('.task.open [data-action=check]').click();
            if (state.solved.includes(5)) throw Error('Starter accepted');
            editor.value='print(5+7)';
            editor.dispatchEvent(new Event('input'));
            document.querySelector('.task.open [data-action=check]').click();
            if (state.coins !== 85) throw Error('Boost failed');
            """);
        waitFor("сдача кода", () -> truth(engine,"state.xp === 35"), () -> {
            engine.executeScript("""
                (async () => {
                    const copy = await api('/api/backup');
                    const row = copy.courses.find(c=>c.course.id==='python');
                    const room=JSON.parse(row.progress.room);
                    if(room.coins!==85 || room.characterName!=='Лис' || room.answers[5]!=='print(5+7)' || room.inventory.double!==1)
                        throw Error('Incomplete backup');
                    await api('/api/logout','POST');
                    await login({name:'Второй профиль'},false);
                    const restored=await api('/api/backup','POST',copy);
                    apply(restored.state);
                    await openCourse();
                    window.restored=true;
                })().catch(err => window.auditError=String(err));
                """);
            waitFor("восстановление комнаты", () -> truth(engine,"Boolean(window.restored)") &&
                restoredWindow()!=null, () -> {
                var restored = restoredWindow();
                engine.executeScript("window.confirm=()=>true; resetProgress()");
                waitFor("сброс", () -> truth(engine,"state.xp === 0") && truth(restored,"state.solved.length === 0 && state.coins === 0"), this::rewardCheck);
            });
        });
    }

    private WebEngine restoredWindow() {
        for (var window : Window.getWindows()) {
            if (!(window instanceof Stage stage) || stage == dashboard || !(stage.getScene().getRoot() instanceof WebView page)) continue;
            var web=page.getEngine();
            if (web == original || !loaded(web) || !web.getLocation().contains("/course/")) continue;
            if (truth(web,"typeof state !== 'undefined' && state.coins===85 && state.characterName==='Лис' && state.answers[5]==='print(5+7)'")) return web;
        }
        return null;
    }

    private WebEngine original;

    private void rewardCheck() {
        engine.executeScript("""
            (async () => {
                for(const [count,id] of [[1,'buddy-ember'],[8,'buddy-luna'],[15,'buddy-amethyst']]) {
                    await postCourse('/api/courses/import',{id:state.courseId,solved:state.tasks.slice(0,count).map(t=>t.id)});
                    show('rewards');
                    document.querySelector('[data-buddy="'+id+'"]').click();
                    while(state.companion!==id) await new Promise(f=>setTimeout(f,50));
                }
                show('home');
                copyText=async text=>window.winner=text;
                document.querySelector('.buddy-panel button').click();
            })().catch(err=>window.auditError=String(err));
            """);
        waitFor("спутники", () -> truth(engine,"Boolean(window.winner && winner.includes('235 XP'))"), () -> {
            System.out.println("UI checks passed: purchase, cancel, restore, reset, rewards");
            result=0;
            Platform.exit();
        });
    }
    private WebEngine course() {
        return Window.getWindows().stream().filter(window -> window != dashboard && window instanceof Stage)
            .map(window -> ((Stage) window).getScene().getRoot())
            .filter(WebView.class::isInstance).map(WebView.class::cast).map(WebView::getEngine)
            .filter(web -> web.getLocation().contains("/course/")).findFirst().orElse(null);
    }

    private static boolean loaded(WebEngine web) {
        return web.getLoadWorker().getState() == Worker.State.SUCCEEDED;
    }

    private static boolean truth(WebEngine web, String script) {
        return Boolean.TRUE.equals(web.executeScript(script));
    }

    private void waitFor(String phase, BooleanSupplier condition, Runnable next) {
        try {
            if (engine != null && loaded(engine) && truth(engine, "Boolean(window.auditError)")) throw new IllegalStateException(String.valueOf(engine.executeScript("window.auditError")));
            if (condition.getAsBoolean()) {
                next.run();
                return;
            }
            if (System.nanoTime() > deadline) throw new IllegalStateException("Не прошёл этап " + phase);
            var tick = new PauseTransition(Duration.millis(150));
            tick.setOnFinished(event -> waitFor(phase, condition, next));
            tick.play();
        } catch (Exception failure) {
            failure.printStackTrace();
            Platform.exit();
        }
    }

    @Override
    public void stop() {
        if (ui != null) ui.stop();
        if (app != null) app.close();
        if (example != null) example.stop(0);
    }
}
