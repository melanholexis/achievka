package achievka;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Application;

public final class Main implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final Set<String> APP_FILES = Set.of("/index.html", "/styles.css", "/app.js", "/icon.png", "/quest-portal.png",
        "/buddy-ember.png", "/buddy-luna.png", "/buddy-amethyst.png", "/demo.html", "/tour.js", "/tour.css");
    private static final Set<String> COURSE_FILES = Set.of("index.html", "game.html", "achievka.js", "python-exercises.js", "theme.css");
    private static final int MAX_BODY = 1_048_576;
    private static final int MAX_BACKUP = 64 * MAX_BODY;
    private static final String SESSION_HEADER = "X-Achievka-Session";
    private static final String PROGRESS_PATH = "/api/v1/progress";

    private record Pair(String token, String courseId, Instant expires) {}
    private final Map<String, Pair> pairs = new LinkedHashMap<>();
    private final Map<String, Course> scanned = new LinkedHashMap<>();
    private final CourseScanner scanner = new CourseScanner(HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(4)).build());
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final AccountStore store;
    private final CourseBook catalog;
    private final String session = AccountStore.randomKey(32);
    private HttpServer dashboard;
    private HttpServer courseServer;
    private Course course;
    private final byte[] pythonData;
    private final Course bundled;
    private record Scan(String key, String account, CourseLink active, CourseLink previous, String title,
                        CourseScanner.ScanResult result, int nonce) {}
    private Scan preview;
    private String scannedAt = "";

    Main(AccountStore store, CourseBook catalog, String fallbackJs) throws CourseScanner.ScanException {
        this.store = store;
        this.catalog = catalog;
        bundled = CourseScanner.parse(fallbackJs, false, "Встроенная версия курса");
        pythonData = fallbackJs.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        for (String argument : args) {
            if (argument.startsWith("--profile=")) {
                String profile = argument.substring("--profile=".length());
                if (!profile.isBlank()) System.setProperty("achievka.profile", profile);
            }
        }
        Path root = Path.of(System.getProperty("achievka.profile",
            Path.of(System.getProperty("user.home"), "Achievka").toString()));
        Files.createDirectories(root);
        try (var channel = FileChannel.open(root.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Ачивка уже запущена с этим профилем");
            Path accounts = root.resolve("accounts.json");
            Path legacy = Path.of("data", "accounts.json");
            if (!Files.exists(accounts) && Files.exists(legacy)) Files.copy(legacy, accounts);
            byte[] fallback = resource("course-site/course-data.js");
            if (fallback == null) throw new IOException("В сборке нет данных курса");
            try (var app = new Main(new AccountStore(accounts), new CourseBook(root.resolve("courses.json")),
                new String(fallback, StandardCharsets.UTF_8))) {
                for (Account account : app.store.all()) app.catalog.migrate(account);
                app.initialCourse();
                app.start();
                app.arm();
                AppWindow.dashboardUrl = app.launchUrl();
                System.out.println("Ачивка запущена");
                Application.launch(AppWindow.class, args);
            }
        }
    }

    void initialCourse() {
        refresh();
    }

    void start() throws IOException {
        courseServer = listen(8741, this::handleCourse);
        dashboard = listen(8751, this::handleDashboard);
        courseServer.start();
        dashboard.start();
    }

    String launchUrl() {
        return dashboardOrigin() + "/#" + session;
    }

    String courseOrigin() {
        return origin(courseServer);
    }

    private String dashboardOrigin() {
        return origin(dashboard);
    }

    private static String origin(HttpServer server) {
        return "http://" + HOST + ":" + server.getAddress().getPort();
    }

    private HttpServer listen(int firstPort, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        IOException failure = null;
        for (int port = firstPort; port < firstPort + 10; port++) {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress(HOST, port), 0);
            } catch (IOException e) {
                failure = e;
                continue;
            }
            server.createContext("/", handler);
            server.setExecutor(workers);
            return server;
        }
        throw new IOException("Нет свободного порта рядом с " + firstPort, failure);
    }

    @Override
    public void close() {
        if (dashboard != null) dashboard.stop(0);
        if (courseServer != null) courseServer.stop(0);
        workers.shutdownNow();
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        try {
            if (!validHost(exchange, dashboardOrigin())) {
                error(exchange, 403, "Доступ запрещён");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                String supplied = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (!session.equals(supplied) || (origin != null && !dashboardOrigin().equals(origin))) {
                    error(exchange, 403, "Открой приложение Ачивка");
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod()) && (path.equals("/api/scan/preview")
                    || path.equals("/api/courses") || path.equals("/api/rescan"))) scan(exchange, path);
                else management(exchange, path);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/tour-data.json".equals(path)) {
                json(exchange, 200, Demo.data(bundled));
                return;
            }
            boolean demo = "/demo.html".equals(path);
            if ("/".equals(path)) path = "/index.html";
            if (!"GET".equals(exchange.getRequestMethod()) || !APP_FILES.contains(path)) {
                error(exchange, 404, "Страница не найдена");
                return;
            }
            exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self'; connect-src 'self'; frame-src 'self'; frame-ancestors "
                + (demo ? "'self'" : "'none'") + "; object-src 'none'; base-uri 'none'");
            file(exchange, path, resource(demo ? "web/index.html" : "web" + path));
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            error(exchange, 500, "Не удалось выполнить действие");
        } finally {
            exchange.close();
        }
    }

    private synchronized void management(HttpExchange exchange, String path) throws IOException {
        String route = exchange.getRequestMethod() + " " + path;
        if (route.equals("GET /api/accounts")) {
            List<String> items = store.all().stream().map(account ->
                Json.obj("name", Json.str(account.name()), "id", Json.str(AccountStore.profileId(account)))).toList();
            json(exchange, 200, Json.obj("accounts", Json.arr(items)));
            return;
        }
        if (route.equals("POST /api/login")) {
            var body = request(exchange);
            Account previous = store.active();
            Account account = store.enter(optional(body, "name"), optional(body, "token"), optional(body, "profile"));
            if (account == null) {
                error(exchange, 400, "Проверь ключ или выбери сохранённый профиль");
                return;
            }
            if (!previous.token().equals(account.token())) revoke(previous.token());
            catalog.migrate(account);
            arm();
            json(exchange, 200, stateJson());
            return;
        }
        Account account = store.active();
        if (!account.connected()) {
            error(exchange, 401, "Войди в профиль");
            return;
        }
        switch (route) {
            case "GET /api/state" -> json(exchange, 200, stateJson());
            case "POST /api/companion" -> {
                var body = request(exchange);
                String courseId = Json.string(body, "courseId");
                if (!catalog.active().id().equals(courseId) || !AccountStore.profileId(account).equals(Json.string(body, "profileId"))) {
                    error(exchange, 409, "Профиль или курс изменился. Открой награды заново.");
                    return;
                }
                String id = Json.string(body, "id");
                if (!id.isEmpty() && !Engine.companionUnlocked(Engine.evaluate(course, activeProgress().solved()), id)) {
                    error(exchange, 400, "Этот спутник ещё закрыт.");
                    return;
                }
                catalog.selectCompanion(account.token(), courseId, id);
                json(exchange, 200, stateJson());
            }
            case "POST /api/logout" -> {
                store.clearActive();
                revoke(account.token());
                arm();
                json(exchange, 200, Json.obj("ok", "true"));
            }
            case "POST /api/scan/commit" -> {
                var body = request(exchange);
                if (preview == null || !preview.key().equals(Json.string(body, "key"))) {
                    error(exchange, 409, "Открой предпросмотр заново");
                    return;
                }
                commitScan(exchange, preview, Json.ids(body.get("solved")));
            }
            case "POST /api/courses/select" -> {
                String id = Json.string(request(exchange), "id");
                if (!catalog.select(id)) {
                    error(exchange, 404, "Такого курса нет");
                    return;
                }
                refresh();
                arm();
                json(exchange, 200, stateJson());
            }
            case "POST /api/courses/remove" -> {
                String id = Json.string(request(exchange), "id");
                String previous = catalog.active().id();
                if (!catalog.hide(id)) {
                    error(exchange, 400, "Последний курс убирать нельзя");
                    return;
                }
                pairs.values().removeIf(pair -> pair.courseId().equals(id));
                if (!previous.equals(catalog.active().id())) refresh();
                arm();
                json(exchange, 200, stateJson());
            }
            case "POST /api/courses/pair" -> pair(exchange, account);
            case "POST /api/courses/import" -> {
                var body = request(exchange);
                CourseLink link = catalog.active();
                if (!link.id().equals(Json.string(body, "id"))) {
                    error(exchange, 409, "Курс изменился. Открой импорт заново");
                    return;
                }
                if ((body.containsKey("profileId") && !AccountStore.profileId(account).equals(body.get("profileId")))
                    || (body.containsKey("resetNonce") && activeProgress().nonce() != Json.nonNegativeInt(body.get("resetNonce")))
                    || (body.containsKey("savedAt") && !link.savedAt().equals(body.get("savedAt")))) {
                    error(exchange, 409, "Профиль или список уроков изменился. Проверь снимок ещё раз."); return;
                }
                List<Integer> ids = Json.ids(body.get("solved"));
                if (ids.size() > CourseBook.MAX_STEPS) throw new IllegalArgumentException("Слишком много заданий");
                var previous = activeProgress();
                var merged = catalog.merge(account.token(), link.id(), previous.nonce(), knownSteps(link.id(), ids));
                Notifier.changed(course, merged.solved());
                json(exchange, 200, stateJson());
            }
            case "POST /api/reset" -> {
                catalog.reset(account.token(), catalog.active().id());
                arm();
                json(exchange, 200, stateJson());
            }
            case "GET /api/backup" -> {
                byte[] data = catalog.exportProgress(account.token()).getBytes(StandardCharsets.UTF_8);
                if (data.length > MAX_BACKUP) error(exchange, 413, "Размер копии превышает 64 МБ");
                else send(exchange, 200, "application/json; charset=utf-8", data);
            }
            case "POST /api/backup" -> {
                int imported = catalog.importProgress(account.token(), request(exchange));
                refresh();
                arm();
                json(exchange, 200, Json.obj("imported", Json.num(imported), "state", stateJson()));
            }
            default -> error(exchange, 404, "Действие не найдено");
        }
    }

    private void pair(HttpExchange exchange, Account account) throws IOException {
        var body = request(exchange);
        CourseLink link = catalog.find(Json.string(body, "id"));
        if (link == null) {
            error(exchange, 404, "Такого курса нет");
            return;
        }
        if (!builtin(link)) {
            error(exchange, 400, "Для этого курса используй импорт с сайта");
            return;
        }
        String key = AccountStore.randomKey(32);
        Instant now = Instant.now();
        pairs.values().removeIf(pair -> !pair.expires().isAfter(now));
        if (pairs.size() >= 256) {
            error(exchange, 429, "Слишком много открытых подключений");
            return;
        }
        pairs.put(key, new Pair(account.token(), link.id(), now.plus(Duration.ofHours(24))));
        json(exchange, 200, Json.obj("key", Json.str(key), "profile", Json.str(AccountStore.profileId(account)),
            "resetNonce", Json.num(catalog.progress(account.token(), link.id()).nonce())));
    }

    private void revoke(String token) {
        pairs.values().removeIf(pair -> pair.token().equals(token));
    }

    private void handleCourse(HttpExchange exchange) throws IOException {
        try {
            if (!validHost(exchange, courseOrigin())) {
                error(exchange, 403, "Доступ запрещён");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (PROGRESS_PATH.equals(path)) {
                sync(exchange);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                error(exchange, 405, "Метод не поддерживается");
                return;
            }
            if (path.equals("/course")) {
                exchange.getResponseHeaders().set("Location", "/course/");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (!path.startsWith("/course/")) {
                error(exchange, 404, "Страница не найдена");
                return;
            }
            String name = path.substring("/course/".length());
            if (name.isEmpty()) name = "index.html";
            exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'");
            if (name.equals("course-data.js")) {
                file(exchange, name, pythonData);
            } else if (COURSE_FILES.contains(name)) {
                file(exchange, name, resource("course-site/" + name));
            } else {
                error(exchange, 404, "Страница не найдена");
            }
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            error(exchange, 500, "Не удалось выполнить действие");
        } finally {
            exchange.close();
        }
    }

    private synchronized void sync(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        String requestOrigin = headers.getFirst("Origin");
        String method = exchange.getRequestMethod();
        Instant now = Instant.now();
        if (requestOrigin != null && !courseOrigin().equals(requestOrigin)) {
            error(exchange, 403, "Источник курса не разрешён");
            return;
        }
        String header = headers.getFirst("Authorization");
        Pair pair = header != null && header.startsWith("Bearer ") ? pairs.get(header.substring(7)) : null;
        if (pair == null || !pair.expires().isAfter(now) || catalog.find(pair.courseId()) == null) {
            error(exchange, 401, "Подключи курс из Ачивки");
            return;
        }
        CourseBook.Progress saved = catalog.progress(pair.token(), pair.courseId());
        if ("GET".equals(method)) {
            json(exchange, 200, CourseBook.encodeProgress(saved));
            return;
        }
        if (!"POST".equals(method)) {
            error(exchange, 405, "Метод не поддерживается");
            return;
        }
        var body = request(exchange);
        int version = Json.nonNegativeInt(body.get("resetNonce"));
        if (version != saved.nonce()) {
            json(exchange, 409, CourseBook.encodeProgress(saved));
            return;
        }
        List<Integer> solved = Json.ids(body.get("solved"));
        if (solved.size() > CourseBook.MAX_STEPS) throw new IllegalArgumentException("Слишком много заданий");
        if (body.containsKey("room") && !saved.room().equals(Json.string(body,"roomBase"))) {
            json(exchange, 409, CourseBook.encodeProgress(saved));
            return;
        }
        String room = body.containsKey("room") ? CourseBook.readRoom(body) : saved.room();
        CourseBook.Progress merged = catalog.merge(pair.token(), pair.courseId(), version, knownSteps(pair.courseId(), solved), room);
        if (catalog.active().id().equals(pair.courseId()) && store.active().token().equals(pair.token())) {
            Notifier.changed(course, merged.solved());
        }
        json(exchange, 200, CourseBook.encodeProgress(merged));
    }

    private List<Integer> knownSteps(String id, List<Integer> solved) {
        Course candidate = scanned.get(id);
        if (id.equals(CourseBook.PYTHON_ID)) candidate = bundled;
        if (candidate == null || candidate.tasks().isEmpty()) throw new IllegalArgumentException("Сначала обнови список уроков");
        return Engine.sanitize(candidate, solved);
    }

    private void refresh() {
        CourseLink link = catalog.active();
        course = builtin(link) ? bundled : link.snapshot();
        if (course == null) course = new Course(link.title(), "", "", List.of(), false, "Обнови список уроков для офлайн-доступа.");
        scanned.put(link.id(), course);
        scannedAt = link.savedAt();
    }

    private void scan(HttpExchange exchange, String path) throws IOException {
        CourseLink active;
        Account account;
        synchronized (this) {
            active = catalog.active();
            account = store.active();
        }
        if (!account.connected()) { error(exchange, 401, "Войди в профиль"); return; }
        var body = path.equals("/api/rescan") ? Map.<String,Object>of() : request(exchange);
        boolean rescan = !body.containsKey("url");
        String address = rescan ? active.dataUrl() : Json.string(body,"url");
        CourseScanner.ScanResult result;
        try {
            if ((rescan && builtin(active)) || address.equals(CourseBook.PYTHON.dataUrl()) || address.equals(CourseBook.PYTHON.siteUrl())) {
                result = new CourseScanner.ScanResult(bundled, CourseBook.PYTHON.dataUrl(), CourseBook.PYTHON.siteUrl(),
                    CourseBook.PYTHON.storageKey(), List.of(), "");
            } else {
                var fetched = scanner.scanUrl(address, false, "");
                result = rescan ? new CourseScanner.ScanResult(fetched.course(), active.dataUrl(), active.siteUrl(),
                    active.storageKey(), fetched.foundDone(), fetched.script()) : fetched;
            }
        } catch (CourseScanner.ScanException ex) {
            error(exchange, 400, ex.getMessage());
            return;
        }
        synchronized (this) {
            if (!store.active().equals(account) || catalog.active() != active) {
                error(exchange, 409, "Профиль или курс изменился"); return;
            }
            CourseLink previous = rescan ? active : catalog.byDataUrl(result.dataUrl());
            String title = optional(body,"title").trim();
            if (title.isEmpty()) title = previous == null ? result.course().title() : previous.title();
            var progress = previous == null ? new CourseBook.Progress(List.of(), 0) : catalog.progress(account.token(), previous.id());
            Scan scan = new Scan(AccountStore.randomKey(16), account.token(), active, previous, title, result, progress.nonce());
            if (!path.equals("/api/scan/preview")) { commitScan(exchange, scan, List.of()); return; }
            preview = scan;
            List<String> rows = new ArrayList<>();
            Course before = previous == null ? null : previous.snapshot();
            var tasks = result.course().tasks();
            for (int i = 0; i < tasks.size(); i++) {
                var t = tasks.get(i);
                Task old = before == null ? null : before.byId(t.id());
                boolean changed = before != null && (old == null || !old.equals(t) || before.tasks().indexOf(old) != i);
                boolean legacy = result.script().isEmpty() && previous != null && !builtin(previous) && t.id() >= 65536
                    && progress.solved().contains(i + 1);
                rows.add(Json.obj("id", Json.num(t.id()), "title", Json.str(t.title()), "changed", Json.bool(changed),
                    "legacy", Json.bool(legacy), "saved", Json.bool(progress.solved().contains(t.id())),
                    "found", Json.bool(result.foundDone().contains(t.id()))));
            }
            var removed = before == null ? List.<String>of() : before.tasks().stream()
                .filter(t -> result.course().byId(t.id()) == null).map(t -> Json.str(t.title())).toList();
            json(exchange, 200, Json.obj("key", Json.str(scan.key()), "title", Json.str(title), "tasks", Json.arr(rows),
                "removed", Json.arr(removed), "reset", Json.bool(progress.nonce() > 0)));
        }
    }

    private void commitScan(HttpExchange exchange, Scan scan, List<Integer> checked) throws IOException {
        CourseLink previous = scan.previous();
        if (!scan.account().equals(store.active().token()) || scan.active() != catalog.active()
            || (previous != null && catalog.progress(scan.account(), previous.id()).nonce() != scan.nonce())) {
            error(exchange, 409, "Прогресс изменился. Обнови предпросмотр."); return;
        }
        var result = scan.result();
        var accepted = Engine.sanitize(result.course(), checked);
        if (accepted.size() != checked.size()) { error(exchange, 400, "Список уроков изменился"); return; }
        String dataUrl = previous == null ? result.dataUrl() : previous.dataUrl();
        if (dataUrl.equals(CourseBook.PYTHON.dataUrl())) {
            catalog.add(scan.title(), dataUrl, CourseBook.PYTHON.siteUrl(), CourseBook.PYTHON.storageKey(),
                null, scan.account(), List.of());
        } else {
            catalog.add(scan.title(), dataUrl, result.siteUrl(), result.storageKey(), result.course(), scan.account(), accepted);
        }
        preview = null;
        refresh();
        arm();
        json(exchange, 200, stateJson());
    }

    private static boolean builtin(CourseLink link) {
        return CourseBook.PYTHON_ID.equals(link.id()) && CourseBook.PYTHON.dataUrl().equals(link.dataUrl());
    }

    private void arm() {
        Notifier.arm(course, activeProgress().solved());
    }

    private CourseBook.Progress activeProgress() {
        return catalog.progress(store.active().token(), catalog.active().id());
    }

    private String stateJson() {
        CourseLink link = catalog.active();
        Account account = store.active();
        var progress = activeProgress();
        List<Integer> solved = Engine.sanitize(course, progress.solved());
        Engine.Snapshot snap = Engine.evaluate(course, solved);
        String next = "null";
        if (snap.nextStep() != null) {
            Task step = snap.nextStep();
            next = Json.obj("id", Json.num(step.id()), "module", Json.str(step.module()),
                "title", Json.str(step.title()), "type", Json.str(step.typeLabel()), "reward", Json.num(step.reward()),
                "condition", Json.str("Сдать задание «" + step.title() + "»."));
        }
        List<String> tasks = new ArrayList<>();
        for (Task task : course.tasks()) {
            tasks.add(Json.obj("id", Json.num(task.id()), "module", Json.str(task.module()),
                "title", Json.str(task.title()), "type", Json.str(task.typeLabel()), "reward", Json.num(task.reward()),
                "done", Json.bool(solved.contains(task.id()))));
        }
        return Json.obj(
            "courseTitle", Json.str(link.title()),
            "version", Json.str(course.version()),
            "updated", Json.str(course.updated()),
            "live", Json.bool(course.live()),
            "sourceNote", Json.str(course.sourceNote()),
            "sourceUrl", Json.str(link.dataUrl()),
            "siteUrl", Json.str(link.siteUrl()),
            "courseUrl", Json.str(builtin(link) ? courseOrigin() + "/course/" : link.siteUrl()),
            "builtIn", Json.bool(builtin(link)),
            "courseId", Json.str(link.id()),
            "storageKey", Json.str(link.storageKey()),
            "courses", coursesJson(link.id()),
            "scannedAt", Json.str(scannedAt),
            "taskCount", Json.num(course.tasks().size()),
            "xp", Json.num(snap.xp()),
            "xpMax", Json.num(snap.xpMax()),
            "levelName", Json.str(snap.levelName()),
            "levelFrom", Json.num(snap.levelFrom()),
            "nextAt", Json.num(snap.nextAt()),
            "nextLevelName", Json.str(snap.nextLevelName()),
            "solvedCount", Json.num(solved.size()), "resetNonce", Json.num(progress.nonce()),
            "studentName", Json.str(account.name()),
            "apiToken", Json.str(account.token()),
            "profileId", Json.str(AccountStore.profileId(account)),
            "connected", Json.bool(account.connected()),
            "next", next,
            "achievements", Json.marks(snap.achievements()),
            "rewards", Json.marks(snap.rewards()),
            "companion", Json.str(Engine.companionUnlocked(snap, progress.companion()) ? progress.companion() : ""),
            "tasks", Json.arr(tasks),
            "summary", Json.str(snap.summary()),
            "courseOpen", Json.bool(snap.achievements().stream().anyMatch(mark -> mark.id().equals("course-done") && mark.done())),
            "note", Json.str("Опыт — сумма наград за сданные задания этого курса.")
        );
    }

    private String coursesJson(String activeId) {
        return Json.arr(catalog.all().stream().map(link -> Json.obj("id", Json.str(link.id()),
            "title", Json.str(link.title()), "siteUrl", Json.str(link.siteUrl()), "dataUrl", Json.str(link.dataUrl()),
            "active", Json.bool(link.id().equals(activeId)))).toList());
    }

    private static String optional(Map<String, Object> data, String key) {
        return data.containsKey(key) ? Json.string(data, key) : "";
    }

    private static Map<String, Object> request(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new IllegalArgumentException("Ожидается application/json");
        }
        int limit = exchange.getRequestURI().getPath().equals("/api/backup") ? MAX_BACKUP : MAX_BODY;
        byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
        if (body.length > limit) throw new IllegalArgumentException("Превышен лимит размера запроса");
        return Json.parseObject(new String(body, StandardCharsets.UTF_8));
    }

    private static boolean validHost(HttpExchange exchange, String origin) {
        return origin.substring("http://".length()).equals(exchange.getRequestHeaders().getFirst("Host"));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = Main.class.getResourceAsStream("/" + name)) {
            if (stream != null) return stream.readAllBytes();
        }
        Path local = Path.of(name);
        return Files.isRegularFile(local) ? Files.readAllBytes(local) : null;
    }

    private static void file(HttpExchange exchange, String name, byte[] body) throws IOException {
        if (body == null) {
            error(exchange, 404, "Страница не найдена");
            return;
        }
        String type = name.endsWith(".js") ? "text/javascript; charset=utf-8"
            : name.endsWith(".css") ? "text/css; charset=utf-8"
            : name.endsWith(".png") ? "image/png" : "text/html; charset=utf-8";
        send(exchange, 200, type, body);
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Json.obj("error", Json.str(message)));
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
