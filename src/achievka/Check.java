package achievka;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Проверка правил на сохранённой копии курса. Запуск: java -cp out achievka.Check */
public final class Check {
    public static void main(String[] args) throws Exception {
        String js = Files.readString(Path.of("web", "fallback-course.js"));
        Course course = CourseScanner.parse(js, true, "тест");
        expect(course.tasks().size() == 15, "15 заданий");
        expect(course.xpMax() == 235, "235 XP, а вышло " + course.xpMax());
        expect(course.byId(5).type().equals("code"), "задание 5 — код");
        expect(course.byId(15).reward() == 50, "финал 50 XP");

        Engine.Snapshot empty = Engine.evaluate(course, List.of());
        expect(empty.xp() == 0, "пустой опыт");
        expect(countDone(empty) == 0, "на пустом курсе знаков нет");
        expect(empty.nextStep().id() == 1, "следующий шаг — 1");
        expect(allHaveCondition(empty), "у каждого знака есть условие");

        Engine.Snapshot first = Engine.evaluate(course, List.of(1));
        expect(isDone(first, "first-step"), "первый шаг");
        expect(!isDone(first, "module-1"), "модуль 1 ещё открыт");
        expect(isDone(first, "in-order"), "одно задание с начала — без пропусков");
        expect(first.xp() == 10, "10 XP");

        Engine.Snapshot module = Engine.evaluate(course, List.of(1, 2));
        expect(isDone(module, "module-1"), "модуль 1 закрыт");
        expect(isDone(module, "title-student"), "титул ученик");

        Engine.Snapshot jumped = Engine.evaluate(course, List.of(1, 2, 4));
        expect(!isDone(jumped, "in-order"), "перескок не считается порядком");
        expect(!isDone(jumped, "mark-straight"), "награды за порядок нет");

        Engine.Snapshot practice = Engine.evaluate(course, List.of(1, 2, 3, 4, 5));
        expect(isDone(practice, "practice-first"), "первая программа");
        expect(isDone(practice, "module-2"), "модуль 2");
        expect(isDone(practice, "title-practice"), "титул практик");
        expect(isDone(practice, "mark-straight"), "пять подряд");
        expect(practice.xp() == 65, "65 XP");

        Engine.Snapshot all = Engine.evaluate(course, ids(course));
        expect(isDone(all, "course-done"), "курс пройден");
        expect(isDone(all, "practice-all"), "все практики");
        expect(isDone(all, "summary"), "сводка");
        expect(all.xp() == 235, "полный опыт");
        expect(all.nextStep() == null, "шагов не осталось");
        expect(!all.summary().contains("место"), "в сводке нет места в группе");

        Engine.Snapshot junk = Engine.evaluate(course, List.of(99, 1, 1));
        expect(junk.xp() == 10, "чужой номер не даёт опыт");
        System.out.println("Проверка правил прошла");
    }

    private static List<Integer> ids(Course course) {
        return course.tasks().stream().map(Task::id).toList();
    }

    private static int countDone(Engine.Snapshot snap) {
        int count = 0;
        for (Mark mark : snap.achievements()) {
            if (mark.done()) {
                count++;
            }
        }
        for (Mark mark : snap.rewards()) {
            if (mark.done()) {
                count++;
            }
        }
        return count;
    }

    private static boolean allHaveCondition(Engine.Snapshot snap) {
        for (Mark mark : snap.achievements()) {
            if (mark.condition() == null || mark.condition().isBlank()) {
                return false;
            }
        }
        for (Mark mark : snap.rewards()) {
            if (mark.condition() == null || mark.condition().isBlank()) {
                return false;
            }
        }
        return !snap.achievements().isEmpty();
    }

    private static boolean isDone(Engine.Snapshot snap, String id) {
        for (Mark mark : snap.achievements()) {
            if (mark.id().equals(id)) {
                return mark.done();
            }
        }
        for (Mark mark : snap.rewards()) {
            if (mark.id().equals(id)) {
                return mark.done();
            }
        }
        return false;
    }

    private static void expect(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }
}
