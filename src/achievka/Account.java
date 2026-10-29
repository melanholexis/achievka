package achievka;

import java.util.List;

/** Один ученик на этом компьютере. Ключ выдаёт приложение, сайт присылает его вместе с шагами. */
public record Account(String token, String name, List<Integer> solved, int resetNonce) {
    public static Account none() {
        return new Account("", "", List.of(), 0);
    }

    public boolean connected() {
        return token != null && !token.isBlank();
    }
}
