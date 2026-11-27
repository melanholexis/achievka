package achievka;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class IntegrationCheck {
    private final HttpClient http = HttpClient.newHttpClient();
    private String dashboard;
    private String course;
    private String session;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory(Path.of("out"), "integration-");
        try {
            new IntegrationCheck().run(temp);
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("Интеграционные проверки прошли");
    }

    private void run(Path temp) throws Exception {
        Path accountsFile = temp.resolve("accounts.json");
        Path courseFile = temp.resolve("courses.json");
        String script = Files.readString(Path.of("course-site", "course-data.js"));
        String legacy = "ach_180011ee";
        Files.writeString(accountsFile, """
            {"active":"","accounts":[{"token":"%s","name":"Прежний профиль","solved":[1],"resetNonce":0}]}
            """.formatted(legacy));
        var accounts = new AccountStore(accountsFile);
        var catalog = new CourseBook(courseFile);
        accounts.all().forEach(catalog::migrate);
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/training/", exchange -> {
            String name = exchange.getRequestURI().getPath();
            String body = name.endsWith(".js") ? script : """
                <!doctype html><html><head>
                <meta name="achievka-storage-key" content="training-progress">
                </head><body><script src="course-data.js"></script></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        var html = new java.util.concurrent.atomic.AtomicReference<>(
            "<li class='incomplete'>Lesson Alpha</li><li class='completed'>Lesson Bravo</li>");
        fixture.createContext("/lessons/", exchange -> {
            boolean page = exchange.getRequestURI().getPath().equals("/lessons/");
            byte[] bytes = ("<html><body><h2>Lessons</h2>" + html.get() + "</body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(page ? 200 : 404, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        fixture.start();
        try (Main app = new Main(accounts, catalog, script)) {
            app.initialCourse();
            app.start();
            URI launch = URI.create(app.launchUrl());
            dashboard = "http://" + launch.getAuthority();
            session = launch.getFragment();
            course = app.courseOrigin();
            check(!dashboard.equals(course), "курс и приложение разделены");
            expectStatus(call(dashboard + "/api/accounts", "GET", null, null, null), 403);
            expectStatus(call(course + "/api/accounts", "GET", null, null, null), 404);
            expectStatus(call(dashboard + "/api/accounts", "GET", null, session, course), 403);
            var profiles = Json.array(app("/api/accounts", "GET", null).get("accounts"));
            var savedProfile = Json.object(profiles.get(0));
            check(!savedProfile.containsKey("token"), "список профилей без ключей");
            var state = app("/api/login", "POST", Json.obj("profile", Json.str(Json.string(savedProfile, "id"))));
            check(state.get("solvedCount").equals(1), "прежний прогресс перенесён");
            expectStatus(call(dashboard + "/api/companion", "POST", companion(state, "buddy-amethyst"), session, null), 400);
            check(app("/api/companion", "POST", companion(state, "buddy-ember")).get("companion").equals("buddy-ember"),
                "спутник доступен по прежнему прогрессу");
            check(app("/api/companion", "POST", companion(state, "")).get("companion").equals(""), "спутника можно снять");
            app("/api/companion", "POST", companion(state, "buddy-ember"));
            String firstPair = pair();
            String secondPair = pair();
            expectStatus(progress(firstPair, 0, "[1,2]", 200), 200);
            expectStatus(progress(secondPair, 0, "[1]", 200), 200);
            check(state().get("xp").equals(20), "старый снимок не стирает новое задание");
            check(state().get("companion").equals("buddy-ember"), "синхронизация сохраняет выбор");
            String oldBackup = call(dashboard + "/api/backup", "GET", null, session, null).body();
            check(!oldBackup.contains(legacy), "в резервной копии нет ключа");
            app("/api/reset", "POST", null);
            expectStatus(progress(firstPair, 0, "[1,2]", 409), 409);
            check(state().get("solvedCount").equals(0), "старый снимок не отменяет сброс");
            app("/api/backup", "POST", oldBackup);
            check(state().get("solvedCount").equals(0), "устаревшая копия не отменяет сброс");
            check(state().get("companion").equals(""), "сброс убирает спутника");
            progress(secondPair, 1, "[2]", 200);
            check(state().get("solvedCount").equals(1), "после сброса можно отправить новое задание");
            expectStatus(call(course + "/course/", "GET", null, null, null), 200);

            String fixtureUrl = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/training/";
            var custom = app("/api/courses", "POST", Json.obj("url", Json.str(fixtureUrl)));
            check(custom.get("courseUrl").equals(fixtureUrl), "страница курса осталась в подпапке");
            check(custom.get("storageKey").equals("training-progress"), "ключ курса считан со страницы");
            String id = Json.string(custom, "courseId");
            expectStatus(call(dashboard + "/api/companion", "POST", companion(state, "buddy-ember"), session, null), 409);
            app("/api/courses/import", "POST", Json.obj("id", Json.str(id), "solved", "[1,2]"));
            app("/api/companion", "POST", companion(custom, "buddy-ember"));
            app("/api/courses/remove", "POST", Json.obj("id", Json.str(id)));
            var hiddenCatalog = new CourseBook(courseFile);
            check(hiddenCatalog.find(id) == null, "скрытие сохраняется после чтения каталога");
            check(hiddenCatalog.progress(legacy, id).solved().equals(List.of(1, 2)), "скрытый курс сохранил прогресс");
            custom = app("/api/courses", "POST", Json.obj("url", Json.str(fixtureUrl)));
            check(custom.get("solvedCount").equals(2), "повторное добавление вернуло прогресс");
            check(custom.get("companion").equals("buddy-ember"), "курс помнит своего спутника");

            String backup = call(dashboard + "/api/backup", "GET", null, session, null).body();
            app("/api/logout", "POST", null);
            progress(firstPair, 1, "[3]", 401);
            app("/api/login", "POST", Json.obj("name", Json.str("Второй профиль")));
            check(state().get("companion").equals(""), "выбор отдельный у каждого профиля");
            app("/api/backup", "POST", backup);
            check(state().get("solvedCount").equals(2), "копия загрузилась в другой профиль");
            var reopened = new CourseBook(courseFile);
            check(reopened.progress(accounts.active().token(), id).companion().equals("buddy-ember"),
                "резервная копия переносит выбор спутника");
            check(reopened.progress(accounts.active().token(), id).solved().equals(List.of(1, 2)),
                "копия записана на диск");

            String lessons = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/lessons/";
            var review = app("/api/scan/preview", "POST", Json.obj("url", Json.str(lessons)));
            var rows = Json.array(review.get("tasks"));
            var alpha = Json.object(rows.get(0));
            var bravo = Json.object(rows.get(1));
            check(Boolean.FALSE.equals(alpha.get("found")) && Boolean.TRUE.equals(bravo.get("found")), "точные HTML-статусы");
            check(state().get("courseId").equals(id), "предпросмотр не изменил текущий курс");
            String completed = "[" + bravo.get("id") + "]";
            var lessonsState = app("/api/scan/commit","POST",Json.obj("key",Json.str(Json.string(review,"key")),"solved",completed));
            check(lessonsState.get("solvedCount").equals(1), "подтверждённый импорт");
            html.set("<li>Lesson Bravo</li><li>Lesson Alpha</li>");
            review = app("/api/scan/preview","POST","{}");
            var first = Json.object(Json.array(review.get("tasks")).get(0));
            check(first.get("id").equals(bravo.get("id")) && first.get("saved").equals(true) && first.get("changed").equals(true),
                "перестановка не переносит отметку");
            app("/api/scan/commit","POST",Json.obj("key",Json.str(Json.string(review,"key")),"solved","[]"));
            var copy = app("/api/backup","GET",null);
            var cleanBook = new CourseBook(temp.resolve("transferred.json"));
            cleanBook.importProgress("target",copy);
            var transferred = cleanBook.byDataUrl(lessons);
            check(transferred.snapshot().tasks().size() == 2 && cleanBook.progress("target",transferred.id()).solved().size() == 1,
                "на чистом ПК из копии восстанавливаются уроки и сдачи");
            var v1Course = Json.obj("id",Json.str("python"),"title",Json.str("Python"),"dataUrl",Json.str(CourseBook.PYTHON.dataUrl()),
                "siteUrl",Json.str(CourseBook.PYTHON.siteUrl()),"storageKey",Json.str(CourseBook.PYTHON.storageKey()));
            var oldCopy = Json.obj("version","1","courses",Json.arr(List.of(Json.obj("course",v1Course,"progress",
                Json.obj("resetNonce","0","solved","[1]")))));
            cleanBook.importProgress("v1",Json.parseObject(oldCopy));
            check(cleanBook.progress("v1","python").solved().equals(List.of(1)), "поддержка файла из 1.2");
        } finally {
            fixture.stop(0);
        }
        try (Main offline = new Main(new AccountStore(accountsFile), new CourseBook(courseFile), script)) {
            offline.initialCourse();
            offline.start();
            var url = URI.create(offline.launchUrl());
            dashboard = "http://" + url.getAuthority();
            session = url.getFragment();
            var local = state();
            check(local.get("solvedCount").equals(1) && local.get("taskCount").equals(2) && local.get("xp").equals(10),
                "офлайн-копия сохраняет уроки и XP после перезапуска");
        }
    }

    private Map<String, Object> state() throws Exception {
        return app("/api/state", "GET", null);
    }

    private static String companion(Map<String, Object> state, String id) {
        return Json.obj("profileId", Json.str(Json.string(state, "profileId")),
            "courseId", Json.str(Json.string(state, "courseId")), "id", Json.str(id));
    }

    private String pair() throws Exception {
        var result = app("/api/courses/pair", "POST", Json.obj("id", Json.str(CourseBook.PYTHON_ID)));
        return Json.string(result, "key");
    }

    private Map<String, Object> app(String path, String method, String body) throws Exception {
        var result = call(dashboard + path, method, body, session, null);
        expectStatus(result, 200);
        return Json.parseObject(result.body());
    }

    private HttpResponse<String> progress(String key, int nonce, String solved, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(course + "/api/v1/progress"))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.obj("resetNonce", Json.num(nonce), "solved", solved)))
            .build();
        var result = http.send(request, HttpResponse.BodyHandlers.ofString());
        expectStatus(result, status);
        return result;
    }

    private HttpResponse<String> call(String url, String method, String body, String key, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (key != null) request.header("X-Achievka-Session", key);
        if (origin != null) request.header("Origin", origin);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void expectStatus(HttpResponse<String> result, int expected) {
        check(result.statusCode() == expected,
            result.request().uri().getPath() + ": HTTP " + result.statusCode() + ", ожидалось " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
