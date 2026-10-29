package achievka;

/** Достижение или награда. Условие видно и до выполнения. */
public record Mark(
    String id,
    String title,
    String condition,
    String detail,
    boolean done,
    int xp
) {}
