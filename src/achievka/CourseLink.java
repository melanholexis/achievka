package achievka;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record CourseLink(String id, String title, String dataUrl, String siteUrl, String storageKey,
                         Course snapshot, String savedAt) {
    CourseLink(String id, String title, String dataUrl, String siteUrl, String storageKey) {
        this(id, title, dataUrl, siteUrl, storageKey, null, "");
    }

    static Course readSnapshot(Map<String, Object> data, String title) {
        if (data.get("tasks") == null) return null;
        var rows = Json.array(data.get("tasks"));
        if (rows.size() > CourseBook.MAX_STEPS) throw Json.invalid();
        List<Task> tasks = new ArrayList<>();
        var ids = new HashSet<Integer>();
        for (var row : rows) {
            var t = Json.object(row);
            int id = Json.nonNegativeInt(t.get("id"));
            int xp = Json.nonNegativeInt(t.get("reward"));
            if (id == 0 || !ids.add(id) || xp > 100000) throw Json.invalid();
            tasks.add(new Task(id, Json.string(t,"module"), Json.string(t,"type"), xp,
                Json.string(t,"difficulty"), Json.string(t,"title")));
        }
        return new Course(title, Json.string(data,"version"), Json.string(data,"updated"), List.copyOf(tasks),
            false, "Сохранённая копия курса");
    }

    String encode() {
        String tasks = snapshot == null ? "null" : Json.arr(snapshot.tasks().stream().map(t -> Json.obj(
            "id", Json.num(t.id()), "module", Json.str(t.module()), "type", Json.str(t.type()),
            "reward", Json.num(t.reward()), "difficulty", Json.str(t.difficulty()), "title", Json.str(t.title()))).toList());
        return Json.obj("id", Json.str(id), "title", Json.str(title), "dataUrl", Json.str(dataUrl),
            "siteUrl", Json.str(siteUrl), "storageKey", Json.str(storageKey), "tasks", tasks,
            "version", Json.str(snapshot == null ? "" : snapshot.version()),
            "updated", Json.str(snapshot == null ? "" : snapshot.updated()), "savedAt", Json.str(savedAt));
    }
}
