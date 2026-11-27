package achievka;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AccountStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private String activeToken = "";

    public AccountStore(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        var data = Json.parseObject(Storage.read(file));
        activeToken = Json.string(data, "active");
        for (Object value : Json.array(data.get("accounts"))) {
            var row = Json.object(value);
            String token = Json.string(row, "token");
            if (!validToken(token)) throw Json.invalid();
            accounts.put(token, new Account(token, Json.string(row, "name"),
                Json.ids(row.get("solved")), Json.nonNegativeInt(row.get("resetNonce"))));
        }
    }

    public synchronized Account active() {
        return accounts.getOrDefault(activeToken, Account.none());
    }

    public synchronized List<Account> all() {
        return List.copyOf(accounts.values());
    }

    public synchronized Account enter(String name, String token, String profile) {
        String key = token.trim().toLowerCase(Locale.ROOT);
        Account existing;
        if (!profile.isBlank()) {
            existing = accounts.values().stream().filter(account -> profileId(account).equals(profile)).findFirst().orElse(null);
            if (existing == null) return null;
            key = existing.token();
        } else {
            if (!key.isEmpty() && !validToken(key)) return null;
            if (key.isEmpty()) key = "ach_" + randomKey(16);
            existing = accounts.get(key);
        }
        String clean = name.isBlank() ? (existing == null ? "Ученик" : existing.name()) : cleanName(name);
        Account next = existing == null
            ? new Account(key, clean, List.of(), 0)
            : new Account(key, clean, existing.solved(), existing.resetNonce());
        var updated = new LinkedHashMap<>(accounts);
        updated.put(key, next);
        write(key, updated);
        accounts.put(key, next);
        activeToken = key;
        return next;
    }

    public synchronized void clearActive() {
        write("", accounts);
        activeToken = "";
    }

    static String profileId(Account account) {
        try {
            byte[] bytes = ("achievka-profile:" + account.token()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomKey(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static boolean validToken(String key) {
        return key.matches("ach_[0-9a-f]{8,40}");
    }

    private static String cleanName(String name) {
        String clean = name.trim().replaceAll("\\s+", " ");
        int length = Math.min(clean.codePointCount(0, clean.length()), 40);
        return clean.substring(0, clean.offsetByCodePoints(0, length));
    }

    private void write(String active, Map<String, Account> rows) {
        List<String> items = rows.values().stream().map(account -> Json.obj(
            "token", Json.str(account.token()), "name", Json.str(account.name()),
            "resetNonce", Json.num(account.resetNonce()), "solved", account.solved().toString()
        )).toList();
        Storage.write(file, Json.obj("active", Json.str(active), "accounts", Json.arr(items)));
    }
}
