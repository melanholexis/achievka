package achievka;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import java.util.concurrent.Executors;

import javafx.application.Application;

/**
 * Локальное приложение «Ачивка».
 * Java сканирует курс на сайте и принимает закрытые шаги.
 * Страница на JS показывает достижения, опыт и награды.
 */
public final class Main {
    private static final String SITE = "https://blerebo.github.io";
    private static final String SKIP_FROM =
        "} else if (id === 'skip') {\n"
            + "      const firstUnsolved = (window.COURSE_DATA?.tasks || []).find(t => !state.solved.includes(t.id));\n"
            + "      if (firstUnsolved) {\n"
            + "        state.solved.push(firstUnsolved.id);\n"
            + "        toast(`⏭️ Пропущено: \"${firstUnsolved.title}\"`, 'success');\n"
            + "      } else {\n"
            + "        toast('✅ Все задания уже решены!', 'success');\n"
            + "      }\n"
            + "    }";
    private static final String SKIP_TO =
        "} else if (id === 'skip') {\n"
            + "      state.coins += item.price;\n"
            + "      toast('Ачивка не ставит знак за пропуск. Сдай задание в курсе.', 'error');\n"
            + "      return;\n"
            + "    }";
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final CourseScanner scanner = new CourseScanner(http);
    private final AccountStore store;
    private final CourseBook catalog;
    private final String fallbackJs;
    private volatile Course course;
    private volatile String scannedAt = "";

    public Main(AccountStore store, CourseBook catalog, String fallbackJs) {
        this.store = store;
        this.catalog = catalog;
        this.fallbackJs = fallbackJs;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.home"), "Achievka");
        Path accounts = root.resolve("accounts.json");
        Path legacy = Path.of("").toAbsolutePath().resolve("data").resolve("accounts.json");
        if (!Files.exists(accounts) && Files.exists(legacy)) {
            Files.createDirectories(root);
            Files.copy(legacy, accounts);
        }
        String fallback = readFallback();
        AccountStore store = new AccountStore(accounts);
        CourseBook catalog = new CourseBook(root.resolve("courses.json"));
        for (Account account : store.all()) {
            catalog.rememberAccount(account);
        }
        Main app = new Main(store, catalog, fallback);
        app.syncActiveProgress();
        app.rescan();
        int port = app.serve();
        AppWindow.port = port;
        Notifier.arm(app.course, store.active().solved());
        System.out.println("Ачивка запущена");
        Application.launch(AppWindow.class, args);
    }

    private int serve() throws IOException {
        int port = 8741;
        HttpServer server = null;
        IOException last = null;
        for (int candidate = port; candidate < port + 10; candidate++) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", candidate), 0);
                port = candidate;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) {
            throw last == null ? new IOException("Не удалось занять порт") : last;
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        return port;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            cors(exchange);
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("/api/state".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/register".equals(path) && "POST".equals(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Account account = store.register(jsonString(body, "name"), jsonString(body, "token"));
                send(exchange, 200, "application/json; charset=utf-8", accountJson(account, true).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/login".equals(path) && "POST".equals(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Account account = store.enter(jsonString(body, "name"), jsonString(body, "token"));
                if (account == null) {
                    send(exchange, 400, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"Ключ должен начинаться с ach_\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                catalog.rememberAccount(account);
                syncActiveProgress();
                Notifier.arm(course, store.active().solved());
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/logout".equals(path) && "POST".equals(method)) {
                store.clearActive();
                Notifier.arm(course, List.of());
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/accounts".equals(path) && "GET".equals(method)) {
                List<String> items = new java.util.ArrayList<>();
                for (Account account : store.all()) {
                    items.add(Json.obj("name", Json.str(account.name()), "token", Json.str(account.token())));
                }
                send(exchange, 200, "application/json; charset=utf-8",
                    Json.obj("accounts", Json.arr(items)).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/v1/progress".equals(path) && "POST".equals(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String token = bearer(exchange);
                if (token.isBlank()) {
                    token = jsonString(body, "token");
                }
                Account account = store.find(token);
                if (!account.connected()) {
                    send(exchange, 401, "application/json; charset=utf-8", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String courseId = query(exchange, "course");
                if (courseId.isBlank() || catalog.find(courseId) == null) {
                    courseId = catalog.active().id();
                }
                List<Integer> solved = courseId.equals(catalog.active().id())
                    ? Engine.sanitize(course, Json.solvedArray(body))
                    : Json.solvedArray(body);
                int nonce = catalog.progress(token, courseId).nonce();
                if (nonce == 0) {
                    nonce = account.resetNonce();
                }
                catalog.saveProgress(token, courseId, solved, nonce);
                if (courseId.equals(catalog.active().id()) && token.equals(store.active().token())) {
                    account = store.setProgress(token, solved, account.resetNonce());
                    Notifier.changed(course, account.solved());
                }
                send(exchange, 200, "application/json; charset=utf-8", accountJson(account, true).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/rescan".equals(path) && "POST".equals(method)) {
                rescanFresh();
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/courses/select".equals(path) && "POST".equals(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!catalog.select(jsonString(body, "id"))) {
                    send(exchange, 404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"Такого курса нет\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                syncActiveProgress();
                rescan();
                Notifier.arm(course, store.active().solved());
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/courses/remove".equals(path) && "POST".equals(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!catalog.remove(jsonString(body, "id"))) {
                    send(exchange, 400, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"Последний курс убирать нельзя\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                syncActiveProgress();
                rescan();
                Notifier.arm(course, store.active().solved());
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/courses".equals(path) && "POST".equals(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                try {
                    addCourse(jsonString(body, "url"), jsonString(body, "title"));
                } catch (CourseScanner.ScanException e) {
                    send(exchange, 400, "application/json; charset=utf-8",
                        ("{\"ok\":false,\"error\":" + Json.str(e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/reset".equals(path) && "POST".equals(method)) {
                store.resetActive();
                Account cleared = store.active();
                if (cleared.connected()) {
                    catalog.saveProgress(cleared.token(), catalog.active().id(), cleared.solved(), cleared.resetNonce());
                }
                Notifier.changed(course, cleared.solved());
                send(exchange, 200, "application/json; charset=utf-8", stateJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/course") || path.startsWith("/course/")) {
                proxyCourse(exchange, path);
                return;
            }
            if (!"GET".equals(method)) {
                send(exchange, 405, "text/plain; charset=utf-8", "Метод не поддерживается".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String file = "/".equals(path) ? "/index.html" : path;
            byte[] bytes = readWeb(file);
            if (bytes == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "Нет такой страницы".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentType(file), bytes);
        } catch (Exception e) {
            byte[] message = ("Ошибка: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            send(exchange, 500, "text/plain; charset=utf-8", message);
        } finally {
            exchange.close();
        }
    }

    private void proxyCourse(HttpExchange exchange, String path) throws IOException, InterruptedException {
        if (path.contains("..")) {
            send(exchange, 400, "text/plain; charset=utf-8", "Плохой путь".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String upstreamPath = path.equals("/course") || path.equals("/course/") ? "/index.html" : path.substring("/course".length());
        if (upstreamPath.isBlank()) {
            upstreamPath = "/index.html";
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(catalog.active().siteUrl()).resolve(upstreamPath.startsWith("/") ? upstreamPath.substring(1) : upstreamPath))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Achievka/1.0")
            .GET()
            .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body() == null ? new byte[0] : response.body();
        String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        if (type.startsWith("text/html")) {
            String html = new String(body, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (html.contains(SKIP_FROM)) {
                html = html.replace(SKIP_FROM, SKIP_TO);
            } else if (html.contains("state.solved.push(firstUnsolved.id);")) {
                html = html.replace(
                    "state.solved.push(firstUnsolved.id);",
                    "toast('Ачивка не ставит знак за пропуск. Сдай задание в курсе.', 'error');"
                );
            }
            html = html.contains("</body>")
                ? html.replace("</body>", bridge() + "</body>")
                : html + bridge();
            body = html.getBytes(StandardCharsets.UTF_8);
            type = "text/html; charset=utf-8";
        }
        send(exchange, response.statusCode(), type, body);
    }

    private synchronized void rescan() {
        CourseLink link = catalog.active();
        boolean builtin = CourseBook.PYTHON_ID.equals(link.id()) && CourseBook.PYTHON.dataUrl().equals(link.dataUrl());
        try {
            course = scanner.scanUrl(link.dataUrl(), builtin, fallbackJs).course();
        } catch (CourseScanner.ScanException e) {
            course = new Course(link.title(), "", "", List.of(), false, e.getMessage());
        }
        scannedAt = Instant.now().toString();
    }

    private synchronized void rescanFresh() {
        CourseLink link = catalog.active();
        try {
            CourseScanner.ScanResult result = scanner.scanUrl(link.dataUrl(), false, "");
            course = result.course();
            scannedAt = Instant.now().toString();
            mergeFound(result.foundDone());
        } catch (CourseScanner.ScanException e) {
            course = new Course(link.title(), "", "", course == null ? List.of() : course.tasks(), false, e.getMessage());
            scannedAt = Instant.now().toString();
        }
    }

    private void addCourse(String url, String title) throws CourseScanner.ScanException {
        CourseScanner.ScanResult result = scanner.scanUrl(url, false, "");
        for (CourseLink link : catalog.all()) {
            if (link.dataUrl().equals(result.dataUrl())) {
                catalog.select(link.id());
                syncActiveProgress();
                course = result.course();
                scannedAt = Instant.now().toString();
                Notifier.arm(course, store.active().solved());
                return;
            }
        }
        String host = URI.create(result.siteUrl()).getHost();
        String id = CourseBook.idFor(host, "");
        String base = id;
        int number = 2;
        while (catalog.find(id) != null) {
            id = base + "-" + number++;
        }
        String storageKey = "achievka_steps".equals(result.storageKey()) ? "achievka_" + id : result.storageKey();
        String name = title == null || title.isBlank() ? result.course().title() : title.trim();
        catalog.add(new CourseLink(id, name, result.dataUrl(), result.siteUrl(), storageKey));
        Account account = store.active();
        if (account.connected() && !result.foundDone().isEmpty()) {
            catalog.saveProgress(account.token(), id, result.foundDone(), 0);
        }
        syncActiveProgress();
        course = result.course();
        scannedAt = Instant.now().toString();
        Notifier.arm(course, store.active().solved());
    }

    private void syncActiveProgress() {
        Account account = store.active();
        if (!account.connected()) {
            return;
        }
        CourseBook.Progress saved = catalog.progress(account.token(), catalog.active().id());
        store.setProgress(account.token(), saved.solved(), saved.nonce());
    }

    private void mergeFound(List<Integer> found) {
        Account account = store.active();
        if (!account.connected() || found.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>(account.solved());
        int before = ids.size();
        ids.addAll(found);
        if (ids.size() == before) {
            return;
        }
        List<Integer> merged = List.copyOf(ids);
        catalog.saveProgress(account.token(), catalog.active().id(), merged, account.resetNonce());
        store.setProgress(account.token(), merged, account.resetNonce());
        Notifier.changed(course, merged);
    }

    private String bridge() {
        CourseLink link = catalog.active();
        return "<script src=\"/achievka.js\" data-game=\"" + link.storageKey()
            + "\" data-course=\"" + link.id() + "\"></script>\n";
    }

    private String stateJson() {
        Course current = course;
        Account account = store.active();
        Engine.Snapshot snap = Engine.evaluate(current, account.solved());
        String next = "null";
        if (snap.nextStep() != null) {
            Task step = snap.nextStep();
            next = Json.obj(
                "id", Json.num(step.id()),
                "module", Json.str(step.module()),
                "title", Json.str(step.title()),
                "type", Json.str(step.typeLabel()),
                "reward", Json.num(step.reward()),
                "condition", Json.str("Сдать задание " + step.id() + ": " + Engine.sentence(step.title()))
            );
        }
        List<String> tasks = new java.util.ArrayList<>();
        for (Task task : current.tasks()) {
            tasks.add(Json.obj(
                "id", Json.num(task.id()),
                "module", Json.str(task.module()),
                "title", Json.str(task.title()),
                "type", Json.str(task.typeLabel()),
                "reward", Json.num(task.reward()),
                "done", Json.bool(account.solved().contains(task.id()))
            ));
        }
        return Json.obj(
            "courseTitle", Json.str(current.title()),
            "version", Json.str(current.version()),
            "updated", Json.str(current.updated()),
            "live", Json.bool(current.live()),
            "sourceNote", Json.str(current.sourceNote()),
            "sourceUrl", Json.str(catalog.active().dataUrl()),
            "siteUrl", Json.str(catalog.active().siteUrl()),
            "courseId", Json.str(catalog.active().id()),
            "storageKey", Json.str(catalog.active().storageKey()),
            "courses", coursesJson(),
            "scannedAt", Json.str(scannedAt),
            "taskCount", Json.num(current.tasks().size()),
            "xp", Json.num(snap.xp()),
            "xpMax", Json.num(snap.xpMax()),
            "levelName", Json.str(snap.levelName()),
            "levelFrom", Json.num(snap.levelFrom()),
            "nextAt", Json.num(snap.nextAt()),
            "nextLevelName", snap.nextLevelName() == null ? "null" : Json.str(snap.nextLevelName()),
            "solvedCount", Json.num(account.solved().size()),
            "studentName", Json.str(account.name()),
            "apiToken", Json.str(account.token()),
            "connected", Json.bool(account.connected()),
            "next", next,
            "achievements", Json.marks(snap.achievements()),
            "rewards", Json.marks(snap.rewards()),
            "tasks", Json.arr(tasks),
            "summary", Json.str(snap.summary()),
            "courseOpen", Json.bool(done(snap, "course-done")),
            "note", Json.str("Общего рейтинга нет. Опыт — сумма наград за сданные задания этого курса, без удвоения за знак.")
        );
    }

    private static boolean done(Engine.Snapshot snap, String id) {
        for (Mark mark : snap.achievements()) {
            if (mark.id().equals(id) && mark.done()) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readWeb(String file) throws IOException {
        if (file.contains("..")) {
            return null;
        }
        String name = file.startsWith("/") ? file.substring(1) : file;
        InputStream stream = Main.class.getResourceAsStream("/web/" + name);
        if (stream != null) {
            try (stream) {
                return stream.readAllBytes();
            }
        }
        Path local = Path.of("web").resolve(name);
        if (Files.exists(local)) {
            return Files.readAllBytes(local);
        }
        return null;
    }

    private static String readFallback() throws IOException {
        byte[] bytes = readWeb("/fallback-course.js");
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String contentType(String file) {
        if (file.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (file.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static void cors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        headers.set("Access-Control-Allow-Origin", origin == null || origin.isBlank() ? "*" : origin);
        headers.set("Vary", "Origin");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        headers.set("Access-Control-Allow-Private-Network", "true");
    }

    private static String bearer(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return "";
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    private String coursesJson() {
        List<String> items = new java.util.ArrayList<>();
        String active = catalog.active().id();
        for (CourseLink link : catalog.all()) {
            items.add(Json.obj(
                "id", Json.str(link.id()),
                "title", Json.str(link.title()),
                "siteUrl", Json.str(link.siteUrl()),
                "dataUrl", Json.str(link.dataUrl()),
                "active", Json.bool(link.id().equals(active))
            ));
        }
        return Json.arr(items);
    }

    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getQuery();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String jsonString(String json, String field) {
        String key = "\"" + field + "\"";
        int at = json.indexOf(key);
        if (at < 0) {
            return "";
        }
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) {
            return "";
        }
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = quote + 1; i < json.length(); i++) {
            char current = json.charAt(i);
            if (current == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(i + 1));
                i++;
                continue;
            }
            if (current == '"') {
                break;
            }
            out.append(current);
        }
        return out.toString().trim();
    }

    private static String accountJson(Account account, boolean ok) {
        return Json.obj(
            "ok", Json.bool(ok),
            "token", Json.str(account.token()),
            "name", Json.str(account.name()),
            "resetNonce", Json.num(account.resetNonce())
        );
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        cors(exchange);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
