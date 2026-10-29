package achievka;

import java.util.ArrayList;
import java.util.List;

final class Json {
    private Json() {}

    static String str(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    static String num(int value) {
        return Integer.toString(value);
    }

    static String bool(boolean value) {
        return value ? "true" : "false";
    }

    static String obj(String... pairs) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append(str(pairs[i])).append(':').append(pairs[i + 1]);
        }
        out.append('}');
        return out.toString();
    }

    static String arr(List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    static String mark(Mark mark) {
        return obj(
            "id", str(mark.id()),
            "title", str(mark.title()),
            "condition", str(mark.condition()),
            "detail", str(mark.detail()),
            "done", bool(mark.done()),
            "xp", num(mark.xp())
        );
    }

    static String marks(List<Mark> marks) {
        List<String> items = new ArrayList<>();
        for (Mark mark : marks) {
            items.add(mark(mark));
        }
        return arr(items);
    }

    static List<Integer> solvedArray(String json) {
        List<Integer> ids = new ArrayList<>();
        if (json == null) {
            return ids;
        }
        int key = json.indexOf("\"solved\"");
        if (key < 0) {
            return ids;
        }
        int open = json.indexOf('[', key);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return ids;
        }
        String body = json.substring(open + 1, close).trim();
        if (body.isEmpty()) {
            return ids;
        }
        for (String part : body.split(",")) {
            String token = part.trim();
            if (token.matches("-?\\d+")) {
                ids.add(Integer.parseInt(token));
            }
        }
        return ids;
    }
}
