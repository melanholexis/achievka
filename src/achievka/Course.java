package achievka;

import java.util.List;

/** Курс, считанный с сайта. */
public record Course(
    String title,
    String version,
    String updated,
    List<Task> tasks,
    boolean live,
    String sourceNote
) {
    public int xpMax() {
        int sum = 0;
        for (Task task : tasks) {
            sum += task.reward();
        }
        return sum;
    }

    public Task byId(int id) {
        for (Task task : tasks) {
            if (task.id() == id) {
                return task;
            }
        }
        return null;
    }
}
