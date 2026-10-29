package achievka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ключи и сданные шаги лежат в файле на этом компьютере. Общего списка учеников наружу нет. */
public final class AccountStore {
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"(ach_[0-9a-f]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern NONCE = Pattern.compile("\"resetNonce\"\\s*:\\s*(\\d+)");
    private static final Pattern SOLVED = Pattern.compile("\"solved\"\\s*:\\s*\\[([^\\]]*)]");
    private static final Pattern ACTIVE = Pattern.compile("\"active\"\\s*:\\s*\"(ach_[0-9a-f]+)\"");

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private String activeToken = "";

    public AccountStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized Account active() {
        Account account = accounts.get(activeToken);
        return account == null ? Account.none() : account;
    }

    public synchronized Account find(String token) {
        Account account = accounts.get(token);
        return account == null ? Account.none() : account;
    }

    public synchronized Account register(String name, String existingToken) {
        String cleanName = cleanName(name);
        Account existing = accounts.get(existingToken);
        if (existing != null) {
            Account renamed = new Account(existing.token(), cleanName, existing.solved(), existing.resetNonce());
            accounts.put(renamed.token(), renamed);
            activeToken = renamed.token();
            write();
            return renamed;
        }
        String token = newToken();
        Account created = new Account(token, cleanName, List.of(), 0);
        accounts.put(token, created);
        activeToken = token;
        write();
        return created;
    }

    public synchronized Account saveSolved(String token, List<Integer> solved) {
        Account account = accounts.get(token);
        if (account == null) {
            return null;
        }
        Account updated = new Account(account.token(), account.name(), List.copyOf(solved), account.resetNonce());
        accounts.put(token, updated);
        write();
        return updated;
    }

    public synchronized Account setProgress(String token, List<Integer> solved, int resetNonce) {
        Account account = accounts.get(token);
        if (account == null) {
            return null;
        }
        Account updated = new Account(account.token(), account.name(), List.copyOf(solved), resetNonce);
        accounts.put(token, updated);
        write();
        return updated;
    }

    /** Вход с этого компьютера. Пустой ключ создаёт нового ученика. Чужой ключ принимается как есть, чтобы его можно было перенести на другой ПК. */
    public synchronized Account enter(String name, String token) {
        String key = token == null ? "" : token.trim().toLowerCase();
        if (key.isEmpty()) {
            return register(name, "");
        }
        if (!key.matches("ach_[0-9a-f]{8,40}")) {
            return null;
        }
        Account existing = accounts.get(key);
        String clean = name == null || name.isBlank()
            ? (existing == null ? "Ученик" : existing.name())
            : cleanName(name);
        Account next = existing == null
            ? new Account(key, clean, List.of(), 0)
            : new Account(key, clean, existing.solved(), existing.resetNonce());
        accounts.put(key, next);
        activeToken = key;
        write();
        return next;
    }

    public synchronized void clearActive() {
        activeToken = "";
        write();
    }

    public synchronized List<Account> all() {
        return List.copyOf(accounts.values());
    }

    public synchronized Account resetActive() {
        Account account = active();
        if (!account.connected()) {
            return account;
        }
        Account cleared = new Account(account.token(), account.name(), List.of(), account.resetNonce() + 1);
        accounts.put(cleared.token(), cleared);
        write();
        return cleared;
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            return "Ученик";
        }
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    private String newToken() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder("ach_");
        for (byte value : bytes) {
            token.append(String.format("%02x", value));
        }
        return token.toString();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Matcher active = ACTIVE.matcher(json);
            if (active.find()) {
                activeToken = active.group(1);
            }
            Matcher tokens = TOKEN.matcher(json);
            while (tokens.find()) {
                int at = tokens.start();
                int next = json.indexOf("\"token\"", tokens.end());
                String slice = next < 0 ? json.substring(at) : json.substring(at, next);
                String token = tokens.group(1);
                Matcher name = NAME.matcher(slice);
                Matcher nonce = NONCE.matcher(slice);
                Matcher solved = SOLVED.matcher(slice);
                accounts.put(token, new Account(
                    token,
                    name.find() ? unescape(name.group(1)) : "Ученик",
                    solved.find() ? numbers(solved.group(1)) : List.of(),
                    nonce.find() ? Integer.parseInt(nonce.group(1)) : 0
                ));
            }
            if (!accounts.containsKey(activeToken)) {
                activeToken = accounts.isEmpty() ? "" : accounts.keySet().iterator().next();
            }
        } catch (IOException ignored) {
            accounts.clear();
            activeToken = "";
        }
    }

    private static List<Integer> numbers(String body) {
        List<Integer> ids = new ArrayList<>();
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return ids;
        }
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (token.matches("-?\\d+")) {
                ids.add(Integer.parseInt(token));
            }
        }
        return List.copyOf(ids);
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void write() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"active\": ").append(Json.str(activeToken)).append(",\n  \"accounts\": [\n");
            int index = 0;
            for (Account account : accounts.values()) {
                if (index++ > 0) {
                    json.append(",\n");
                }
                json.append("    {\"token\": ").append(Json.str(account.token()));
                json.append(", \"name\": ").append(Json.str(account.name()));
                json.append(", \"resetNonce\": ").append(account.resetNonce());
                json.append(", \"solved\": [");
                for (int i = 0; i < account.solved().size(); i++) {
                    if (i > 0) {
                        json.append(", ");
                    }
                    json.append(account.solved().get(i));
                }
                json.append("]}");
            }
            json.append("\n  ]\n}\n");
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать ключи: " + file, e);
        }
    }
}
