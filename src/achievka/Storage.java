package achievka;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class Storage {
    private Storage() {}

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + path, e);
        }
    }

    static void write(Path path, String data) {
        Path target = path.toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".tmp");
            try {
                Files.writeString(temp, data);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить " + target, e);
        }
    }
}
