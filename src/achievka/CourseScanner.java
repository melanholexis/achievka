package achievka;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Скачивает course-data.js с сайта и разбирает шаги курса. */
public final class CourseScanner {
    public static final String COURSE_URL = "https://blerebo.github.io/course-data.js";

    private static final Pattern FIELD_STRING = Pattern.compile("['\"]?%s['\"]?\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\")");
    private static final Pattern FIELD_INT = Pattern.compile("['\"]?%s['\"]?\\s*:\\s*(\\d+)");
    private static final Pattern SCRIPT_SRC = Pattern.compile("(?i)<script[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?is)<li([^>]*)>(.*?)</li>");
    private static final Pattern HEADING = Pattern.compile("(?is)<h3([^>]*)>(.*?)</h3>");
    private static final int TASK_LIMIT = 80;

    private final HttpClient http;

    public CourseScanner(HttpClient http) {
        this.http = http;
    }

    public ScanResult scanUrl(String url, boolean allowFallback, String fallbackJs) throws ScanException {
        String address = normalize(url);
        try {
            return interpret(address, fetch(address));
        } catch (ScanException e) {
            if (!allowFallback) {
                throw e;
            }
            Course course = fromFallback(fallbackJs, e.getMessage());
            return new ScanResult(course, COURSE_URL, "https://blerebo.github.io/", "pystart_game_v1", List.of());
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "нет связи с сайтом" : e.getMessage();
            if (!allowFallback) {
                throw new ScanException("Сайт недоступен (" + reason + ")");
            }
            Course course = fromFallback(fallbackJs, reason);
            return new ScanResult(course, COURSE_URL, "https://blerebo.github.io/", "pystart_game_v1", List.of());
        }
    }

    public Course scan(String fallbackJs) throws ScanException {
        return scanUrl(COURSE_URL, true, fallbackJs).course();
    }

    private ScanResult interpret(String url, String body) throws ScanException, IOException, InterruptedException {
        if (looksLikeCourse(body)) {
            return result(parse(body, true, "Считано с " + url), url, siteOf(url), storageKey(body, url), List.of());
        }
        if (looksLikeHtml(body)) {
            String linked = scriptWithCourse(body);
            if (linked != null) {
                String next = URI.create(url).resolve(linked).toString();
                String script = fetch(next);
                if (looksLikeCourse(script)) {
                    return result(parse(script, true, "Считано с " + next), next, siteOf(url), storageKey(script, url), List.of());
                }
            }
            String inline = inlineCourse(body);
            if (inline != null) {
                return result(parse(inline, true, "Считано со страницы " + url), url, siteOf(url), storageKey(body, url), List.of());
            }
            URI page = URI.create(url);
            if (page.getPath() == null || !page.getPath().endsWith("/course-data.js")) {
                String guess = page.resolve("/course-data.js").toString();
                if (!guess.equals(url)) {
                    try {
                        String script = fetch(guess);
                        if (looksLikeCourse(script)) {
                            return result(parse(script, true, "Считано с " + guess), guess, siteOf(url), storageKey(script, url), List.of());
                        }
                    } catch (Exception ignored) {
                        // Страница без файла курса читается дальше как список шагов.
                    }
                }
            }
            return fromHtml(body, url);
        }
        throw new ScanException("По этому адресу нет ни файла курса, ни списка шагов");
    }

    private String fetch(String url) throws IOException, InterruptedException, ScanException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "Achievka/1.0")
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            throw new ScanException("Сайт ответил кодом " + response.statusCode());
        }
        return response.body();
    }

    private Course fromFallback(String fallbackJs, String reason) throws ScanException {
        if (fallbackJs == null || fallbackJs.isBlank()) {
            throw new ScanException("Сайт недоступен (" + reason + "), и запасной копии курса нет");
        }
        return parse(fallbackJs, false, "Сайт сейчас недоступен (" + reason + "). Показана сохранённая копия курса.");
    }

    public static Course parse(String js, boolean live, String sourceNote) throws ScanException {
        String title = fieldString(js, "title");
        String version = fieldString(js, "version");
        String updated = fieldString(js, "updated");
        int tasksAt = js.indexOf("tasks:");
        if (tasksAt < 0) {
            throw new ScanException("В данных курса нет списка tasks");
        }
        String body = js.substring(tasksAt);
        Matcher ids = Pattern.compile("['\"]?id['\"]?\\s*:\\s*(\\d+)").matcher(body);
        List<Integer> starts = new ArrayList<>();
        List<Integer> idValues = new ArrayList<>();
        while (ids.find()) {
            starts.add(ids.start());
            idValues.add(Integer.parseInt(ids.group(1)));
        }
        if (starts.isEmpty()) {
            throw new ScanException("В данных курса нет заданий");
        }
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : body.length();
            String slice = body.substring(from, to);
            String module = fieldString(slice, "module");
            String type = fieldString(slice, "type");
            String taskTitle = fieldString(slice, "title");
            String difficulty = fieldString(slice, "difficulty");
            int reward = fieldInt(slice, "reward", 0);
            if (module == null || type == null || taskTitle == null) {
                continue;
            }
            tasks.add(new Task(idValues.get(i), module, type, reward, difficulty == null ? "" : difficulty, taskTitle));
        }
        if (tasks.isEmpty()) {
            throw new ScanException("Задания нашлись, но разобрать их не удалось");
        }
        return new Course(
            title == null ? "Курс" : title,
            version == null ? "" : version,
            updated == null ? "" : updated,
            List.copyOf(tasks),
            live,
            sourceNote
        );
    }

    private static String fieldString(String slice, String name) {
        Matcher matcher = Pattern.compile(String.format(FIELD_STRING.pattern(), name)).matcher(slice);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private ScanResult fromHtml(String html, String url) throws ScanException {
        String plain = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ").replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        String module = "Курс";
        List<Task> tasks = new ArrayList<>();
        List<Integer> done = new ArrayList<>();
        Matcher sections = Pattern.compile("(?is)<h2[^>]*>(.*?)</h2>").matcher(plain);
        List<Integer> cuts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (sections.find() && names.size() < 30) {
            cuts.add(sections.start());
            names.add(cleanText(sections.group(1)));
        }
        if (cuts.isEmpty()) {
            collectTasks(plain, module, tasks, done);
        } else {
            if (cuts.get(0) > 0) {
                collectTasks(plain.substring(0, cuts.get(0)), module, tasks, done);
            }
            for (int i = 0; i < cuts.size() && tasks.size() < TASK_LIMIT; i++) {
                int from = cuts.get(i);
                int to = i + 1 < cuts.size() ? cuts.get(i + 1) : plain.length();
                String name = names.get(i);
                collectTasks(plain.substring(from, to), name.isBlank() ? module : name, tasks, done);
            }
        }
        if (tasks.size() < 2) {
            tasks.clear();
            done.clear();
            collectHeadings(plain, tasks, done);
        }
        if (tasks.size() < 2) {
            throw new ScanException("На странице не видно списка заданий. Укажи адрес файла course-data.js или страницу, где шаги идут списком.");
        }
        String title = "Курс";
        Matcher titleMatcher = HTML_TITLE.matcher(html);
        if (titleMatcher.find()) {
            String found = cleanText(titleMatcher.group(1));
            if (!found.isBlank()) {
                title = found.length() > 80 ? found.substring(0, 80) : found;
            }
        }
        Course course = new Course(title, "", "", List.copyOf(tasks), true, "Шаги прочитаны со страницы " + url);
        return result(course, url, siteOf(url), storageKey(html, url), done);
    }

    private static void collectTasks(String html, String module, List<Task> tasks, List<Integer> done) {
        Matcher items = LIST_ITEM.matcher(html);
        while (items.find() && tasks.size() < TASK_LIMIT) {
            String text = cleanText(items.group(2));
            if (text.length() < 6 || text.length() > 180) {
                continue;
            }
            int id = tasks.size() + 1;
            String type = taskType(text);
            tasks.add(new Task(id, module, type, "code".equals(type) ? 25 : 10, "", text));
            if (isDone(items.group(1), text)) {
                done.add(id);
            }
        }
    }

    private static void collectHeadings(String html, List<Task> tasks, List<Integer> done) {
        Matcher items = HEADING.matcher(html);
        while (items.find() && tasks.size() < TASK_LIMIT) {
            String text = cleanText(items.group(2));
            if (text.length() < 6 || text.length() > 180) {
                continue;
            }
            int id = tasks.size() + 1;
            String type = taskType(text);
            tasks.add(new Task(id, "Курс", type, "code".equals(type) ? 25 : 10, "", text));
            if (isDone(items.group(1), text)) {
                done.add(id);
            }
        }
    }

    private static boolean isDone(String attributes, String text) {
        String mark = (attributes + " " + text).toLowerCase();
        return mark.contains("done") || mark.contains("complete") || mark.contains("solved") || mark.contains("passed") || text.contains("✓");
    }

    private static String taskType(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("код") || lower.contains("программ") || lower.contains("напиши") || lower.contains("function")) {
            return "code";
        }
        if (lower.contains("тест") || lower.contains("выбери")) {
            return "choice";
        }
        return "text";
    }

    private static String cleanText(String html) {
        String text = html.replaceAll("(?is)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static boolean looksLikeCourse(String body) {
        return body.contains("tasks") && (body.contains("id:") || body.contains("\"id\"") || body.contains("'id'"));
    }

    private static boolean looksLikeHtml(String body) {
        String start = body.stripLeading().toLowerCase();
        return start.startsWith("<!doctype html") || start.startsWith("<html") || start.contains("<body") || start.contains("<head");
    }

    private static String scriptWithCourse(String html) {
        Matcher matcher = SCRIPT_SRC.matcher(html);
        String fallback = null;
        while (matcher.find()) {
            String src = matcher.group(1);
            if (src.contains("course-data")) {
                return src;
            }
            if (fallback == null && src.endsWith(".js") && !src.contains("achievka")) {
                fallback = null;
            }
        }
        return fallback;
    }

    private static String inlineCourse(String html) {
        int at = html.indexOf("COURSE_DATA");
        if (at < 0) {
            at = html.indexOf("tasks:");
        }
        if (at < 0 || !html.substring(at).contains("id:")) {
            return null;
        }
        return html.substring(at);
    }

    private static String storageKey(String body, String url) {
        if (body.contains("pystart_game_v1") || url.contains("blerebo.github.io")) {
            return "pystart_game_v1";
        }
        return "achievka_steps";
    }

    private static String siteOf(String url) {
        URI uri = URI.create(url);
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        return uri.getScheme() + "://" + uri.getHost() + port + "/";
    }

    private static String normalize(String url) throws ScanException {
        String value = url == null ? "" : url.trim();
        if (value.isBlank()) {
            throw new ScanException("Нужен адрес сайта");
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ScanException("Адрес сайта не разбирается");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new ScanException("Адрес сайта не разбирается");
        }
    }

    private static ScanResult result(Course course, String dataUrl, String siteUrl, String storageKey, List<Integer> done) throws ScanException {
        if (course.tasks().isEmpty()) {
            throw new ScanException("В ответе сайта нет заданий");
        }
        return new ScanResult(course, dataUrl, siteUrl, storageKey, List.copyOf(done));
    }

    public record ScanResult(Course course, String dataUrl, String siteUrl, String storageKey, List<Integer> foundDone) {}

    private static int fieldInt(String slice, String name, int fallback) {
        Matcher matcher = Pattern.compile(String.format(FIELD_INT.pattern(), name)).matcher(slice);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    public static final class ScanException extends Exception {
        public ScanException(String message) {
            super(message);
        }
    }
}
