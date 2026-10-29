package achievka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Список курсов и сдача по каждому из них.
 * Лежит рядом с учётными записями, чтобы смена сайта не требовала новой сборки.
 */
public final class CourseBook {
    public static final String PYTHON_ID = "python";
    public static final CourseLink PYTHON = new CourseLink(
        PYTHON_ID,
        "Python с нуля",
        "https://blerebo.github.io/course-data.js",
        "https://blerebo.github.io/",
        "pystart_game_v1"
    );

    public record Progress(List<Integer> solved, int nonce) {
        static Progress empty() {
            return new Progress(List.of(), 0);
        }
    }

    private final Path file;
    private final Map<String, CourseLink> courses = new LinkedHashMap<>();
    private final Map<String, Map<String, Progress>> progress = new LinkedHashMap<>();
    private String activeId = PYTHON_ID;

    public CourseBook(Path file) {
        this.file = file;
        load();
        if (courses.isEmpty()) {
            courses.put(PYTHON.id(), PYTHON);
            activeId = PYTHON.id();
            write();
        }
        if (!courses.containsKey(activeId)) {
            activeId = courses.keySet().iterator().next();
            write();
        }
    }

    public synchronized CourseLink active() {
        return courses.get(activeId);
    }

    public synchronized List<CourseLink> all() {
        return List.copyOf(courses.values());
    }

    public synchronized CourseLink find(String id) {
        return courses.get(id);
    }

    public synchronized CourseLink add(CourseLink link) {
        courses.put(link.id(), link);
        activeId = link.id();
        write();
        return link;
    }

    public synchronized boolean select(String id) {
        if (!courses.containsKey(id)) {
            return false;
        }
        activeId = id;
        write();
        return true;
    }

    public synchronized boolean remove(String id) {
        if (!courses.containsKey(id) || courses.size() == 1) {
            return false;
        }
        courses.remove(id);
        for (Map<String, Progress> byCourse : progress.values()) {
            byCourse.remove(id);
        }
        if (!courses.containsKey(activeId)) {
            activeId = courses.keySet().iterator().next();
        }
        write();
        return true;
    }

    public synchronized void rememberAccount(Account account) {
        if (!account.connected() || hasProgress(account.token(), PYTHON_ID)) {
            return;
        }
        saveProgress(account.token(), PYTHON_ID, account.solved(), account.resetNonce());
    }

    public synchronized boolean hasProgress(String token, String courseId) {
        Map<String, Progress> byCourse = progress.get(token);
        return byCourse != null && byCourse.containsKey(courseId);
    }

    public synchronized Progress progress(String token, String courseId) {
        Map<String, Progress> byCourse = progress.get(token);
        if (byCourse == null) {
            return Progress.empty();
        }
        Progress found = byCourse.get(courseId);
        return found == null ? Progress.empty() : found;
    }

    public synchronized void saveProgress(String token, String courseId, List<Integer> solved, int nonce) {
        progress.computeIfAbsent(token, key -> new LinkedHashMap<>())
            .put(courseId, new Progress(List.copyOf(solved), nonce));
        write();
    }

    public static String idFor(String host, String existingId) {
        String base = host == null ? "course" : host.toLowerCase().replaceFirst("^www\\.", "");
        base = base.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) {
            base = "course";
        }
        if (existingId == null || existingId.isBlank()) {
            return base;
        }
        return existingId;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            courses.clear();
            progress.clear();
        }
    }

    private void read(String json) {
        String active = stringAfter(json, "\"active\"");
        if (!active.isBlank()) {
            activeId = active;
        }
        int coursesAt = json.indexOf("\"courses\"");
        int progressAt = json.indexOf("\"progress\"");
        if (coursesAt >= 0) {
            int from = json.indexOf('[', coursesAt);
            int to = progressAt > from ? progressAt : json.length();
            String slice = json.substring(from, to);
            int cursor = 0;
            while (true) {
                int idAt = slice.indexOf("\"id\"", cursor);
                if (idAt < 0) {
                    break;
                }
                String id = stringAfter(slice.substring(idAt), "\"id\"");
                String title = stringAfter(slice.substring(idAt), "\"title\"");
                String dataUrl = stringAfter(slice.substring(idAt), "\"dataUrl\"");
                String siteUrl = stringAfter(slice.substring(idAt), "\"siteUrl\"");
                String storageKey = stringAfter(slice.substring(idAt), "\"storageKey\"");
                if (!id.isBlank() && !dataUrl.isBlank()) {
                    courses.put(id, new CourseLink(
                        id,
                        title.isBlank() ? id : title,
                        dataUrl,
                        siteUrl.isBlank() ? dataUrl : siteUrl,
                        storageKey.isBlank() ? "achievka_" + id : storageKey
                    ));
                }
                cursor = idAt + 4;
            }
        }
        if (progressAt >= 0) {
            readProgress(json.substring(progressAt));
        }
    }

    private void readProgress(String json) {
        int cursor = 0;
        while (true) {
            int at = json.indexOf("\"ach_", cursor);
            if (at < 0) {
                break;
            }
            String token = readJsonString(json, at);
            int body = json.indexOf('{', at + token.length());
            if (body < 0) {
                break;
            }
            int end = skipObject(json, body);
            String slice = json.substring(body, end);
            int inner = 0;
            while (true) {
                int courseAt = indexOfKey(slice, inner);
                if (courseAt < 0) {
                    break;
                }
                String courseId = readJsonString(slice, courseAt);
                int colon = slice.indexOf(':', courseAt + courseId.length() + 2);
                int valueAt = colon < 0 ? -1 : nextSolid(slice, colon + 1);
                if (courseId.isBlank() || courseId.startsWith("ach_") || valueAt < 0 || slice.charAt(valueAt) != '{') {
                    inner = courseAt + courseId.length() + 2;
                    continue;
                }
                int nonce = intAfter(slice.substring(courseAt), "\"resetNonce\"");
                List<Integer> solved = new ArrayList<>();
                int bracket = slice.indexOf('[', valueAt);
                int objectEnd = skipObject(slice, valueAt);
                int bracketEnd = bracket < 0 ? -1 : slice.indexOf(']', bracket);
                if (bracket >= valueAt && bracket < objectEnd && bracketEnd > bracket && bracketEnd < objectEnd) {
                    for (String part : slice.substring(bracket + 1, bracketEnd).split(",")) {
                        String value = part.trim();
                        if (value.matches("\\d+")) {
                            solved.add(Integer.parseInt(value));
                        }
                    }
                }
                progress.computeIfAbsent(token, key -> new LinkedHashMap<>())
                    .put(courseId, new Progress(List.copyOf(solved), nonce));
                inner = objectEnd;
            }
            cursor = end;
        }
    }

    private static int nextSolid(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            if (!Character.isWhitespace(json.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfKey(String json, int from) {
        int at = json.indexOf('"', from);
        while (at >= 0) {
            String value = readJsonString(json, at);
            int after = at + value.length() + 2;
            int colon = json.indexOf(':', after);
            if (colon >= 0 && json.substring(after, colon).isBlank() && !value.isBlank()) {
                return at;
            }
            at = json.indexOf('"', at + 1);
        }
        return -1;
    }

    private static int skipObject(String json, int open) {
        int depth = 0;
        boolean quote = false;
        for (int i = open; i < json.length(); i++) {
            char current = json.charAt(i);
            if (current == '\\' && quote) {
                i++;
                continue;
            }
            if (current == '"') {
                quote = !quote;
            } else if (!quote && current == '{') {
                depth++;
            } else if (!quote && current == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return json.length();
    }

    private static String stringAfter(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return "";
        }
        int quote = json.indexOf('"', at + key.length());
        if (quote < 0) {
            return "";
        }
        return readJsonString(json, quote);
    }

    private static int intAfter(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return 0;
        }
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char current = json.charAt(i);
            if (Character.isDigit(current)) {
                digits.append(current);
            } else if (!digits.isEmpty() || !Character.isWhitespace(current)) {
                break;
            }
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }

    private static String readJsonString(String json, int quote) {
        StringBuilder out = new StringBuilder();
        for (int i = quote + 1; i < json.length(); i++) {
            char current = json.charAt(i);
            if (current == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(i + 1));
                i++;
                continue;
            }
            if (current == '"') {
                return out.toString();
            }
            out.append(current);
        }
        return out.toString();
    }

    private void write() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"active\": ").append(Json.str(activeId)).append(",\n  \"courses\": [\n");
            int index = 0;
            for (CourseLink link : courses.values()) {
                if (index++ > 0) {
                    json.append(",\n");
                }
                json.append("    {\"id\": ").append(Json.str(link.id()));
                json.append(", \"title\": ").append(Json.str(link.title()));
                json.append(", \"dataUrl\": ").append(Json.str(link.dataUrl()));
                json.append(", \"siteUrl\": ").append(Json.str(link.siteUrl()));
                json.append(", \"storageKey\": ").append(Json.str(link.storageKey())).append('}');
            }
            json.append("\n  ],\n  \"progress\": {\n");
            int tokenIndex = 0;
            for (Map.Entry<String, Map<String, Progress>> entry : progress.entrySet()) {
                if (tokenIndex++ > 0) {
                    json.append(",\n");
                }
                json.append("    ").append(Json.str(entry.getKey())).append(": {\n");
                int courseIndex = 0;
                for (Map.Entry<String, Progress> course : entry.getValue().entrySet()) {
                    if (courseIndex++ > 0) {
                        json.append(",\n");
                    }
                    json.append("      ").append(Json.str(course.getKey())).append(": {\"resetNonce\": ");
                    json.append(course.getValue().nonce()).append(", \"solved\": [");
                    for (int i = 0; i < course.getValue().solved().size(); i++) {
                        if (i > 0) {
                            json.append(", ");
                        }
                        json.append(course.getValue().solved().get(i));
                    }
                    json.append("]}");
                }
                json.append("\n    }");
            }
            json.append("\n  }\n}\n");
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать курсы: " + file, e);
        }
    }
}
