package achievka;

public record Task(
    int id,
    String module,
    String type,
    int reward,
    String difficulty,
    String title
) {
    public String typeLabel() {
        return switch (type) {
            case "choice" -> "тест";
            case "text" -> "короткий ответ";
            case "code" -> "практика с кодом";
            default -> type;
        };
    }
}
