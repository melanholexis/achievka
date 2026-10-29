package achievka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Прогресс лежит в файле на этом компьютере. */
public final class ProgressStore {
    private static final Pattern SOLVED = Pattern.compile("\"solved\"\\s*:\\s*\\[([^\\]]*)]");
    private static final Pattern NONCE = Pattern.compile("\"resetNonce\"\\s*:\\s*(\\d+)");

    private final Path file;
    private Progress progress = new Progress(List.of(), 0);

    public ProgressStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized Progress get() {
        return progress;
    }

    public synchronized Progress saveSolved(List<Integer> solved) {
        progress = new Progress(List.copyOf(solved), progress.resetNonce());
        write();
        return progress;
    }

    public synchronized Progress reset() {
        progress = new Progress(List.of(), progress.resetNonce() + 1);
        write();
        return progress;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Matcher nonce = NONCE.matcher(json);
            int resetNonce = nonce.find() ? Integer.parseInt(nonce.group(1)) : 0;
            Matcher solved = SOLVED.matcher(json);
            List<Integer> ids = new ArrayList<>();
            if (solved.find()) {
                String body = solved.group(1).trim();
                if (!body.isEmpty()) {
                    for (String part : body.split(",")) {
                        String token = part.trim();
                        if (token.matches("-?\\d+")) {
                            ids.add(Integer.parseInt(token));
                        }
                    }
                }
            }
            progress = new Progress(List.copyOf(ids), resetNonce);
        } catch (IOException ignored) {
            progress = new Progress(List.of(), 0);
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"resetNonce\": ").append(progress.resetNonce()).append(",\n  \"solved\": [");
            for (int i = 0; i < progress.solved().size(); i++) {
                if (i > 0) {
                    json.append(", ");
                }
                json.append(progress.solved().get(i));
            }
            json.append("]\n}\n");
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать прогресс: " + file, e);
        }
    }
}
