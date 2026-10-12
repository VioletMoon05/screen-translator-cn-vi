package vn.st;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;

/** Log don gian ra ~/.screen-translator/app.log de tim loi khi app "khong chay". */
public final class AppLog {
    private AppLog() {}

    private static final Path FILE = Config.dir().resolve("app.log");

    static {
        try {
            Files.createDirectories(Config.dir());
            if (Files.exists(FILE) && Files.size(FILE) > 1_000_000) Files.delete(FILE);
        } catch (Exception ignored) {
        }
    }

    public static Path file() {
        return FILE;
    }

    public static synchronized void info(String msg) {
        write("INFO  " + msg);
    }

    public static synchronized void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        write("ERROR " + msg + (t != null ? "\n" + sw : ""));
    }

    private static void write(String line) {
        String s = LocalTime.now().withNano(0) + " " + line + "\n";
        System.err.print(s);
        try {
            Files.writeString(FILE, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
