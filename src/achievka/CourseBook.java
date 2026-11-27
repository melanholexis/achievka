package achievka;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Хранит каталог и прогресс по паре «аккаунт, курс». */
public final class CourseBook {
    public static final String PYTHON_ID = "python";
    public static final CourseLink PYTHON = new CourseLink(
        PYTHON_ID, "Python с нуля", "https://blerebo.github.io/course-data.js",
        "https://blerebo.github.io/", "pystart_game_v1"
    );
    static final int MAX_STEPS = 5_000;

    public record Progress(List<Integer> solved, int nonce, String companion, String room) {
        Progress(List<Integer> solved, int nonce) { this(solved, nonce, "", ""); }
    }
    private record BackupEntry(CourseLink course, Progress progress) {}
    private static final Progress EMPTY = new Progress(List.of(), 0);

    private final Path file;
    private final Map<String, CourseLink> courses = new LinkedHashMap<>();
    private final LinkedHashSet<String> hidden = new LinkedHashSet<>();
    private final Map<String, Map<String, Progress>> progress = new LinkedHashMap<>();
    private String activeId = PYTHON_ID;

    public CourseBook(Path file) {
        this.file = file;
        if (Files.exists(file)) read(Storage.read(file));
        if (courses.isEmpty()) courses.put(PYTHON_ID, PYTHON);
        if (find(activeId) == null) activeId = all().get(0).id();
    }

    public synchronized CourseLink active() {
        return courses.get(activeId);
    }

    public synchronized List<CourseLink> all() {
        return courses.values().stream().filter(link -> !hidden.contains(link.id())).toList();
    }

    public synchronized CourseLink find(String id) {
        return hidden.contains(id) ? null : courses.get(id);
    }

    public synchronized CourseLink add(String title, String dataUrl, String siteUrl, String storageKey,
                                       Course snapshot, String token, List<Integer> checked) {
        var previous = byDataUrl(dataUrl);
        String id = previous == null ? availableId(dataUrl) : previous.id();
        CourseLink link = new CourseLink(id, title, dataUrl, siteUrl,
            storageKey.equals("achievka_steps") ? "achievka_" + id : storageKey, snapshot, java.time.Instant.now().toString());
        change(() -> {
            courses.put(id, link);
            hidden.remove(id);
            activeId = id;
            Progress p = progress(token, id);
            putProgress(token, id, new Progress(mergeIds(p.solved(), checked), p.nonce(), p.companion(), p.room()));
        });
        return link;
    }

    public synchronized boolean select(String id) {
        if (find(id) == null) return false;
        if (!id.equals(activeId)) change(() -> activeId = id);
        return true;
    }

    public synchronized boolean hide(String id) {
        if (find(id) == null || all().size() == 1) return false;
        change(() -> {
            hidden.add(id);
            if (id.equals(activeId)) activeId = all().get(0).id();
        });
        return true;
    }

    public synchronized void migrate(Account account) {
        if (!account.connected() || progress.getOrDefault(account.token(), Map.of()).containsKey(PYTHON_ID)) return;
        change(() -> putProgress(account.token(), PYTHON_ID,
            new Progress(account.solved(), account.resetNonce())));
    }

    public synchronized Progress progress(String token, String courseId) {
        return progress.getOrDefault(token, Map.of()).getOrDefault(courseId, EMPTY);
    }

    public synchronized Progress merge(String token, String courseId, int nonce, List<Integer> solved) {
        Progress previous = progress(token, courseId);
        if (nonce != previous.nonce()) return previous;
        return merge(token, courseId, nonce, solved, previous.room());
    }

    synchronized Progress merge(String token, String courseId, int nonce, List<Integer> solved, String room) {
        Progress previous = progress(token, courseId);
        if (previous.nonce() != nonce) return previous;
        Progress next = new Progress(mergeIds(previous.solved(), solved), nonce, previous.companion(), room);
        if (!next.equals(previous)) change(() -> putProgress(token, courseId, next));
        return next;
    }

    public synchronized Progress reset(String token, String courseId) {
        Progress next = new Progress(List.of(), Math.incrementExact(progress(token, courseId).nonce()));
        change(() -> putProgress(token, courseId, next));
        return next;
    }

    public synchronized void selectCompanion(String token, String courseId, String id) {
        Progress previous = progress(token, courseId);
        if (!id.equals(previous.companion())) {
            change(() -> putProgress(token, courseId, new Progress(previous.solved(), previous.nonce(), id, previous.room())));
        }
    }

    public synchronized String exportProgress(String token) {
        List<String> items = new ArrayList<>();
        for (CourseLink link : courses.values()) {
            Progress saved = progress(token, link.id());
            if (saved.equals(EMPTY) && link.snapshot() == null) continue;
            items.add(Json.obj("course", link.encode(), "progress", encodeProgress(saved)));
        }
        return Json.obj("version", Json.num(2), "courses", Json.arr(items));
    }

    public synchronized int importProgress(String token, Map<String, Object> backup) {
        int version = Json.nonNegativeInt(backup.get("version"));
        if (version != 1 && version != 2) throw Json.invalid();
        List<BackupEntry> entries = new ArrayList<>();
        for (Object item : Json.array(backup.get("courses"))) {
            var entry = Json.object(item);
            entries.add(new BackupEntry(readCourse(Json.object(entry.get("course")), false),
                readProgress(Json.object(entry.get("progress")))));
        }
        change(() -> {
            for (BackupEntry entry : entries) {
                CourseLink incoming = entry.course();
                CourseLink local = byDataUrl(incoming.dataUrl());
                if (local == null) {
                    String id = availableId(incoming.dataUrl());
                    local = new CourseLink(id, incoming.title(), incoming.dataUrl(), incoming.siteUrl(), incoming.storageKey(), incoming.snapshot(), incoming.savedAt());
                    courses.put(id, local);
                } else if (local.snapshot() == null && incoming.snapshot() != null && !local.id().equals(PYTHON_ID)) {
                    local = new CourseLink(local.id(), local.title(), local.dataUrl(), local.siteUrl(), local.storageKey(),
                        incoming.snapshot(), incoming.savedAt());
                    courses.put(local.id(), local);
                }
                Progress previous = progress(token, local.id());
                Progress newer = entry.progress();
                if (newer.nonce() < previous.nonce()) continue;
                if (newer.nonce() == previous.nonce()) {
                    String companion = previous.companion().isEmpty() ? newer.companion() : previous.companion();
                    newer = new Progress(mergeIds(previous.solved(), newer.solved()), newer.nonce(), companion,
                        newer.room().isEmpty() ? previous.room() : newer.room());
                }
                putProgress(token, local.id(), newer);
            }
        });
        return entries.size();
    }

    private static List<Integer> mergeIds(List<Integer> previous, List<Integer> additions) {
        var merged = new LinkedHashSet<>(previous);
        merged.addAll(additions);
        if (merged.size() > MAX_STEPS) throw new IllegalArgumentException("Слишком много заданий");
        return List.copyOf(merged);
    }

    synchronized CourseLink byDataUrl(String url) {
        return courses.values().stream().filter(link -> link.dataUrl().equals(url)).findFirst().orElse(null);
    }

    private String availableId(String url) {
        if (url.equals(PYTHON.dataUrl()) && !courses.containsKey(PYTHON_ID)) return PYTHON_ID;
        String base = URI.create(url).getHost().toLowerCase(Locale.ROOT)
            .replaceFirst("^www\\.", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) base = "course";
        String id = base;
        int number = 2;
        while (courses.containsKey(id) || id.equals(PYTHON_ID)) id = base + "-" + number++;
        return id;
    }

    private void putProgress(String token, String courseId, Progress update) {
        var byCourse = new LinkedHashMap<>(progress.getOrDefault(token, Map.of()));
        byCourse.put(courseId, update);
        progress.put(token, byCourse);
    }

    private void change(Runnable operation) {
        var previousCourses = new LinkedHashMap<>(courses);
        var previousHidden = new LinkedHashSet<>(hidden);
        var previousProgress = new LinkedHashMap<>(progress);
        String previousActive = activeId;
        try {
            operation.run();
            write();
        } catch (RuntimeException e) {
            courses.clear();
            courses.putAll(previousCourses);
            hidden.clear();
            hidden.addAll(previousHidden);
            progress.clear();
            progress.putAll(previousProgress);
            activeId = previousActive;
            throw e;
        }
    }

    private void read(String json) {
        var data = Json.parseObject(json);
        activeId = Json.string(data, "active");
        for (Object value : Json.array(data.get("courses"))) {
            CourseLink link = readCourse(Json.object(value), !data.containsKey("hidden"));
            courses.put(link.id(), link);
        }
        if (data.containsKey("hidden")) {
            for (Object value : Json.array(data.get("hidden"))) {
                if (value instanceof String id && courses.containsKey(id)) hidden.add(id);
            }
        }
        if (all().isEmpty()) hidden.clear();
        Json.object(data.get("progress")).forEach((token, byCourse) ->
            Json.object(byCourse).forEach((id, value) -> putProgress(token, id, readProgress(Json.object(value)))));
    }

    private static CourseLink readCourse(Map<String, Object> value, boolean legacy) {
        String id = Json.string(value, "id");
        String title = Json.string(value, "title");
        String data = Json.string(value, "dataUrl");
        String site = Json.string(value, "siteUrl");
        String key = Json.string(value, "storageKey");
        URI dataUri = webUri(data);
        URI siteUri = webUri(site);
        // Старые версии сохраняли только корень. Восстанавливаем путь по адресу файла.
        if (legacy && "/".equals(siteUri.getPath()) && sameOrigin(dataUri, siteUri)
            && !dataUri.getPath().isEmpty() && !dataUri.getPath().equals("/")) {
            site = dataUri.getPath().endsWith(".js") ? dataUri.resolve(".").toString() : data;
        }
        if (!id.matches("[a-z0-9-]{1,253}") || !key.matches("[a-zA-Z0-9_-]{1,253}")) throw Json.invalid();
        return new CourseLink(id, title, data, site, key, CourseLink.readSnapshot(value, title),
            value.get("savedAt") instanceof String time ? time : "");
    }

    static URI webUri(String value) {
        URI uri = URI.create(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Укажи адрес http:// или https://");
        }
        return uri;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
            && first.getHost().equalsIgnoreCase(second.getHost()) && first.getPort() == second.getPort();
    }

    private static Progress readProgress(Map<String, Object> value) {
        List<Integer> ids = Json.ids(value.get("solved"));
        if (ids.size() > MAX_STEPS) throw new IllegalArgumentException("Слишком много заданий");
        String companion = value.get("companion") instanceof String id && id.matches("buddy-[a-z]{1,24}") ? id : "";
        return new Progress(List.copyOf(new LinkedHashSet<>(ids)), Json.nonNegativeInt(value.get("resetNonce")), companion, readRoom(value));
    }

    static String readRoom(Map<String, Object> data) {
        if (!data.containsKey("room")) return "";
        String room = Json.string(data, "room");
        if (room.isEmpty()) return room;
        if (room.length() > 256000) throw new IllegalArgumentException("Сохранение комнаты слишком большое");
        var fields = Json.parseObject(room);
        for (var entry : fields.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "avatar", "characterName" -> {
                    if (!(value instanceof String s) || s.length() > 100) throw Json.invalid();
                }
                case "coins", "purchasedCount", "totalEarned" -> Json.nonNegativeInt(value);
                case "doubleUntil" -> {
                    if (!(value instanceof Number number) || number.doubleValue() < 0 || number.doubleValue() > 9e15) throw Json.invalid();
                }
                case "answers", "inventory" -> {
                    boolean answers = entry.getKey().equals("answers");
                    for (var item : Json.object(value).entrySet()) {
                        if (!item.getKey().matches(answers ? "[0-9]+(_choice)?" : "[a-z][a-z0-9_]{0,40}")) throw Json.invalid();
                        if (!(answers && item.getValue() instanceof String)) Json.nonNegativeInt(item.getValue());
                    }
                }
                default -> throw Json.invalid();
            }
        }
        return room;
    }

    private void write() {
        List<String> accounts = new ArrayList<>();
        progress.forEach((token, byCourse) -> {
            List<String> fields = new ArrayList<>();
            byCourse.forEach((id, saved) -> fields.add(Json.str(id) + ":" + encodeProgress(saved)));
            accounts.add(Json.str(token) + ":{" + String.join(",", fields) + "}");
        });
        Storage.write(file, Json.obj(
            "active", Json.str(activeId),
            "courses", Json.arr(courses.values().stream().map(CourseLink::encode).toList()),
            "hidden", Json.arr(hidden.stream().map(Json::str).toList()),
            "progress", "{" + String.join(",", accounts) + "}"
        ));
    }


    static String encodeProgress(Progress value) {
        return Json.obj("resetNonce", Json.num(value.nonce()), "solved", value.solved().toString(),
            "companion", Json.str(value.companion()), "room", Json.str(value.room()));
    }
}
