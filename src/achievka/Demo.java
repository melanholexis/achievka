package achievka;

import java.util.List;

/** Снимки для показа. Рассчитываются теми же правилами, что и личный прогресс. */
final class Demo {
    private Demo() {}

    static String data(Course python) {
        var external = new Course("Основы веб-дизайна", "", "", List.of(
            new Task(1, "Композиция", "text", 10, "", "Найти центр композиции"),
            new Task(2, "Композиция", "text", 10, "", "Собрать модульную сетку"),
            new Task(3, "Типографика", "text", 10, "", "Выбрать пару шрифтов"),
            new Task(4, "Типографика", "text", 10, "", "Сверстать первый экран")
        ), false, "Сохранённая копия курса");
        return Json.obj(
            "start", frame(python, 0, "", false, false), "external", frame(external, 2, "", true, true),
            "practice", frame(python, 4, "", false, true), "earned", frame(python, 5, "", false, true),
            "equipped", frame(python, 5, "buddy-ember", false, true),
            "finished", frame(python, python.tasks().size(), "buddy-amethyst", false, true));
    }

    private static String frame(Course course, int count, String buddy, boolean custom, boolean hasExternal) {
        var ids = course.tasks().stream().limit(count).map(Task::id).toList();
        var score = Engine.evaluate(course, ids);
        var tasks = course.tasks().stream().map(t -> Json.obj("id",Json.num(t.id()), "title",Json.str(t.title()),
            "module",Json.str(t.module()), "reward",Json.num(t.reward()), "type",Json.str(t.typeLabel()),
            "done",Json.bool(ids.contains(t.id())))).toList();
        String example = "https://studio.example/lessons/";
        String builtin = Json.obj("id",Json.str("python"), "title",Json.str(CourseBook.PYTHON.title()),
            "siteUrl",Json.str(CourseBook.PYTHON.siteUrl()), "active",Json.bool(!custom));
        String other = Json.obj("id",Json.str("studio"), "title",Json.str("Основы веб-дизайна"),
            "siteUrl",Json.str(example), "active",Json.bool(custom));
        return Json.obj("studentName",Json.str("Алекс"), "connected","true", "profileId",Json.str("demo"),
            "courseId",Json.str(custom ? "studio" : "python"), "builtIn",Json.bool(!custom), "courseTitle",Json.str(course.title()),
            "courses",Json.arr(hasExternal ? List.of(builtin,other) : List.of(builtin)), "tasks",Json.arr(tasks),
            "taskCount",Json.num(tasks.size()), "solvedCount",Json.num(count), "xp",Json.num(score.xp()), "xpMax",Json.num(score.xpMax()),
            "levelName",Json.str(score.levelName()), "next",score.nextStep() == null ? "null" : tasks.get(count),
            "achievements",Json.marks(score.achievements()), "rewards",Json.marks(score.rewards()), "companion",Json.str(buddy),
            "courseOpen",Json.bool(score.nextStep()==null), "summary",Json.str(score.summary()), "sourceNote",Json.str(course.sourceNote()));
    }
}
