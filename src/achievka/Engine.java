package achievka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Считает опыт, достижения и награды по уже сданным шагам. Рейтинга нет. */
public final class Engine {
    private Engine() {}

    public record Snapshot(
        int xp,
        int xpMax,
        String levelName,
        int levelFrom,
        int nextAt,
        String nextLevelName,
        Task nextStep,
        List<Mark> achievements,
        List<Mark> rewards,
        String summary
    ) {}

    public static Snapshot evaluate(Course course, List<Integer> solvedOrder) {
        List<Integer> solved = sanitize(course, solvedOrder);
        int xp = 0;
        for (int id : solved) {
            xp += course.byId(id).reward();
        }
        int xpMax = course.xpMax();
        Level level = level(xp, xpMax);

        Map<String, List<Task>> modules = new LinkedHashMap<>();
        List<Task> codeTasks = new ArrayList<>();
        for (Task task : course.tasks()) {
            modules.computeIfAbsent(task.module(), key -> new ArrayList<>()).add(task);
            if ("code".equals(task.type())) {
                codeTasks.add(task);
            }
        }

        List<Mark> achievements = new ArrayList<>();
        if (!course.tasks().isEmpty()) {
            Task first = course.tasks().get(0);
            achievements.add(taskMark("first-step", "Первый шаг", first, contains(solved, first.id())));
        }
        int moduleIndex = 1;
        for (Map.Entry<String, List<Task>> entry : modules.entrySet()) {
            achievements.add(moduleMark("module-" + moduleIndex, entry.getKey(), entry.getValue(), solved));
            moduleIndex++;
        }
        if (!codeTasks.isEmpty()) {
            Task firstCode = codeTasks.get(0);
            achievements.add(taskMark("practice-first", "Первая программа", firstCode, contains(solved, firstCode.id())));
            achievements.add(groupMark(
                "practice-all",
                "Все практики",
                "Сдать каждое задание, где нужно написать код.",
                codeTasks,
                solved
            ));
        }
        Order order = order(course, solved);
        achievements.add(new Mark(
            "in-order",
            "Без пропусков",
            "Закрывать задания с первого по списку курса, не перескакивая.",
            order.detail(),
            order.ok(),
            0
        ));
        achievements.add(groupMark(
            "course-done",
            "Курс пройден",
            "Сдать все " + course.tasks().size() + " заданий курса «" + course.title() + "».",
            course.tasks(),
            solved
        ));

        List<Mark> rewards = rewards(solved, achievements, order);
        String summary = summary(course, solved, xp, xpMax, level.name());
        return new Snapshot(
            xp,
            xpMax,
            level.name(),
            level.from(),
            level.nextAt(),
            level.nextName(),
            nextStep(course, solved),
            List.copyOf(achievements),
            List.copyOf(rewards),
            summary
        );
    }

    private static List<Mark> rewards(List<Integer> solved, List<Mark> achievements, Order order) {
        List<Mark> rewards = new ArrayList<>();
        rewards.add(new Mark(
            "title-student",
            "Титул «Ученик»",
            "Закрыть первый модуль курса.",
            "Личная отметка. Другим студентам её не показывают.",
            done(achievements, "module-1"),
            0
        ));
        rewards.add(new Mark(
            "title-practice",
            "Титул «Практик»",
            "Сдать первое задание, где нужно написать код.",
            "Код проверяет сам курс, не клик по странице.",
            done(achievements, "practice-first"),
            0
        ));
        rewards.add(new Mark(
            "mark-straight",
            "Отметка «Иду по программе»",
            "Пять заданий подряд с начала курса, без пропусков.",
            order.ok() && solved.size() >= 5
                ? "Сейчас подряд: " + solved.size()
                : "Сейчас подряд с начала: " + order.prefix(),
            order.ok() && solved.size() >= 5,
            0
        ));
        rewards.add(new Mark(
            "mark-practice",
            "Знак практики",
            "Сдать все задания с кодом.",
            "Практики курса, не отдельная игра.",
            done(achievements, "practice-all"),
            0
        ));
        rewards.add(new Mark(
            "summary",
            "Сводка закрытых шагов",
            "Сдать все задания курса. Сводку можно показать себе и преподавателю.",
            "Это не оценка и не строка в журнале.",
            done(achievements, "course-done"),
            0
        ));
        return rewards;
    }

    private static String summary(Course course, List<Integer> solved, int xp, int xpMax, String level) {
        StringBuilder text = new StringBuilder();
        text.append("Ачивка. Курс «").append(course.title()).append("».\n");
        text.append("Это не оценка и не запись в журнале. Места среди других студентов нет.\n");
        text.append("Закрыто заданий: ").append(solved.size()).append(" из ").append(course.tasks().size()).append(".\n");
        text.append("Опыт: ").append(xp).append(" из ").append(xpMax).append(". Свой уровень: ").append(level).append(".\n");
        String currentModule = null;
        for (Task task : course.tasks()) {
            if (!task.module().equals(currentModule)) {
                currentModule = task.module();
                text.append("\n").append(currentModule).append("\n");
            }
            text.append(contains(solved, task.id()) ? "сдано" : "ещё нет");
            text.append("  ").append(task.id()).append(". ").append(task.title()).append("\n");
        }
        return text.toString().strip();
    }

    static String sentence(String text) {
        if (text.endsWith(".") || text.endsWith("?") || text.endsWith("!")) {
            return text;
        }
        return text + ".";
    }

    private static Mark taskMark(String id, String title, Task task, boolean done) {
        return new Mark(
            id,
            title,
            "Сдать задание " + task.id() + ": " + sentence(task.title()),
            task.module() + " · " + task.typeLabel() + " · " + task.reward() + " XP",
            done,
            task.reward()
        );
    }

    private static Mark moduleMark(String id, String module, List<Task> tasks, List<Integer> solved) {
        return groupMark(id, module + " закрыт", "Сдать все задания блока «" + module + "».", tasks, solved);
    }

    private static Mark groupMark(String id, String title, String condition, List<Task> tasks, List<Integer> solved) {
        int xp = 0;
        int closed = 0;
        StringBuilder names = new StringBuilder();
        for (Task task : tasks) {
            if (names.length() > 0) {
                names.append("; ");
            }
            names.append(task.id()).append(". ").append(task.title());
            xp += task.reward();
            if (contains(solved, task.id())) {
                closed++;
            }
        }
        return new Mark(
            id,
            title,
            condition,
            closed + " из " + tasks.size() + " · " + names,
            closed == tasks.size() && !tasks.isEmpty(),
            xp
        );
    }

    private static Task nextStep(Course course, List<Integer> solved) {
        for (Task task : course.tasks()) {
            if (!contains(solved, task.id())) {
                return task;
            }
        }
        return null;
    }

    private static boolean done(List<Mark> marks, String id) {
        for (Mark mark : marks) {
            if (mark.id().equals(id)) {
                return mark.done();
            }
        }
        return false;
    }

    private static boolean contains(List<Integer> solved, int id) {
        return solved.contains(id);
    }

    static List<Integer> sanitize(Course course, List<Integer> solvedOrder) {
        List<Integer> clean = new ArrayList<>();
        if (solvedOrder == null) {
            return clean;
        }
        for (Integer id : solvedOrder) {
            if (id == null || clean.contains(id) || course.byId(id) == null) {
                continue;
            }
            clean.add(id);
        }
        return clean;
    }

    private record Order(boolean ok, int prefix, String detail) {}

    private static Order order(Course course, List<Integer> solved) {
        if (solved.isEmpty()) {
            return new Order(false, 0, "Пока ни одного задания. Первым идёт задание 1.");
        }
        int prefix = 0;
        for (int i = 0; i < solved.size() && i < course.tasks().size(); i++) {
            if (solved.get(i) != course.tasks().get(i).id()) {
                Task expected = course.tasks().get(i);
                return new Order(
                    false,
                    prefix,
                    "Порядок сбит на шаге " + (i + 1) + ": ожидалось задание " + expected.id()
                        + ", а закрыто " + solved.get(i) + ". Сбрось более позднее задание на сайте и сдай пропущенное."
                );
            }
            prefix++;
        }
        if (solved.size() > course.tasks().size()) {
            return new Order(false, prefix, "В списке больше шагов, чем в курсе.");
        }
        return new Order(true, prefix, "Подряд с начала: " + prefix + ".");
    }

    private record Level(String name, int from, int nextAt, String nextName) {}

    private static Level level(int xp, int xpMax) {
        int max = Math.max(xpMax, 1);
        int[] bounds = {0, ceil(max, 0.15), ceil(max, 0.40), ceil(max, 0.70), max};
        String[] names = {"Новичок", "Ученик", "Практикант", "Кодер", "Курс пройден"};
        if (xp <= 0) {
            return new Level(names[0], 0, bounds[1], names[1]);
        }
        if (xp >= max) {
            return new Level(names[4], max, max, null);
        }
        int index = 0;
        for (int i = 0; i < bounds.length; i++) {
            if (xp >= bounds[i]) {
                index = i;
            }
        }
        int nextAt = index + 1 < bounds.length ? bounds[index + 1] : max;
        String nextName = index + 1 < names.length ? names[index + 1] : null;
        return new Level(names[index], bounds[index], nextAt, nextName);
    }

    private static int ceil(int max, double part) {
        return Math.max(1, (int) Math.ceil(max * part));
    }
}
