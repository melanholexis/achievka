package achievka;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Замечает новый закрытый этап и просит окно показать уведомление. */
public final class Notifier {
    public record Notice(String title, String text) {}

    private static final List<Notice> queue = new ArrayList<>();
    private static final Set<Integer> seenTasks = new HashSet<>();
    private static final Set<String> seenMarks = new HashSet<>();
    private static Consumer<Notice> sink;
    private static boolean armed;

    private Notifier() {}

    public static synchronized void arm(Course course, List<Integer> solved) {
        remember(course, solved);
        armed = true;
    }

    public static synchronized void changed(Course course, List<Integer> solved) {
        if (!armed || course == null) {
            return;
        }
        Engine.Snapshot snap = Engine.evaluate(course, solved);
        List<Notice> fresh = new ArrayList<>();
        for (Mark mark : snap.achievements()) {
            if (!mark.done()) {
                continue;
            }
            if (!seenMarks.contains(mark.id())) {
                fresh.add(new Notice(mark.title(), mark.condition()));
            }
        }
        if (fresh.isEmpty()) {
            for (int id : solved) {
                if (seenTasks.contains(id)) {
                    continue;
                }
                Task task = course.byId(id);
                if (task != null) {
                    fresh.add(new Notice("Задание " + id + " сдано", task.title() + "  ·  +" + task.reward() + " XP"));
                }
            }
        }
        if (fresh.size() > 3) {
            int extra = fresh.size() - 2;
            fresh = new ArrayList<>(fresh.subList(0, 2));
            fresh.add(new Notice("Ещё этапы", extra + " сразу"));
        }
        remember(course, solved);
        for (Notice notice : fresh) {
            publish(notice);
        }
    }

    public static void attach(Consumer<Notice> consumer) {
        List<Notice> pending;
        synchronized (Notifier.class) {
            sink = consumer;
            pending = List.copyOf(queue);
            queue.clear();
        }
        pending.forEach(consumer);
    }

    private static void remember(Course course, List<Integer> solved) {
        seenTasks.clear();
        if (solved != null) {
            seenTasks.addAll(solved);
        }
        seenMarks.clear();
        if (course == null) {
            return;
        }
        for (Mark mark : Engine.evaluate(course, solved == null ? List.of() : solved).achievements()) {
            if (mark.done()) {
                seenMarks.add(mark.id());
            }
        }
    }

    private static void publish(Notice notice) {
        if (sink == null) {
            queue.add(notice);
            return;
        }
        sink.accept(notice);
    }
}
