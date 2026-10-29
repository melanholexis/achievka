package achievka;

/** Один сайт с курсом, который пользователь добавил на этом компьютере. */
public record CourseLink(String id, String title, String dataUrl, String siteUrl, String storageKey) {}
