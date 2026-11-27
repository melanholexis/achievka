package achievka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    static Map<String, Object> parseObject(String json) {
        Reader reader = new Reader(json);
        Object value = reader.value(0);
        reader.space();
        if (reader.at != json.length()) throw invalid();
        return object(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw invalid();
    }

    static List<?> array(Object value) {
        if (value instanceof List<?> items) return items;
        throw invalid();
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value instanceof String text) return text;
        throw invalid();
    }

    static int nonNegativeInt(Object value) {
        if (value instanceof Integer number && number >= 0) return number;
        throw invalid();
    }

    static List<Integer> ids(Object value) {
        return array(value).stream().map(Json::nonNegativeInt).toList();
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Неверный формат JSON");
    }

    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Object value(int depth) {
            space();
            if (at == text.length() || depth > 32) throw invalid();
            return switch (text.charAt(at)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            at++;
            Map<String, Object> values = new LinkedHashMap<>();
            if (take('}')) return values;
            do {
                space();
                String key = string();
                if (!take(':') || values.containsKey(key)) throw invalid();
                values.put(key, value(depth));
            } while (take(','));
            if (!take('}')) throw invalid();
            return values;
        }

        private List<Object> array(int depth) {
            at++;
            List<Object> values = new ArrayList<>();
            if (take(']')) return values;
            do {
                values.add(value(depth));
            } while (take(','));
            if (!take(']')) throw invalid();
            return values;
        }

        private String string() {
            if (!take('"')) throw invalid();
            StringBuilder result = new StringBuilder();
            while (at < text.length()) {
                char next = text.charAt(at++);
                if (next == '"') return result.toString();
                if (next < 32) throw invalid();
                if (next != '\\') {
                    result.append(next);
                    continue;
                }
                if (at == text.length()) throw invalid();
                result.append(switch (text.charAt(at++)) {
                    case '"', '\\', '/' -> text.charAt(at - 1);
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw invalid();
                });
            }
            throw invalid();
        }

        private char unicode() {
            if (at + 4 > text.length()) throw invalid();
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(at++), 16);
                if (digit < 0) throw invalid();
                code = 16 * code + digit;
            }
            return (char) code;
        }

        private Object number() {
            int start = at;
            if (text.charAt(at) == '-') at++;
            if (at == text.length()) throw invalid();
            if (text.charAt(at) == '0') at++;
            else digits();
            boolean whole = true;
            if (at < text.length() && text.charAt(at) == '.') {
                at++;
                digits();
                whole = false;
            }
            if (at < text.length() && "eE".indexOf(text.charAt(at)) >= 0) {
                at++;
                if (at < text.length() && "+-".indexOf(text.charAt(at)) >= 0) at++;
                digits();
                whole = false;
            }
            String number = text.substring(start, at);
            if (whole) {
                try {
                    return Integer.valueOf(number);
                } catch (NumberFormatException ignored) {
                    // IDs and versions must fit in an int.
                }
            }
            return new java.math.BigDecimal(number);
        }

        private void digits() {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (start == at) throw invalid();
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) throw invalid();
            at += word.length();
            return value;
        }

        private boolean take(char expected) {
            space();
            if (at == text.length() || text.charAt(at) != expected) return false;
            at++;
            return true;
        }

        private void space() {
            while (at < text.length() && " \n\r\t".indexOf(text.charAt(at)) >= 0) at++;
        }
    }
}
