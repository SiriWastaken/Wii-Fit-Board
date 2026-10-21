import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/** Minimal leveled logger. Writes to stderr, which systemd/journald captures. */
final class Log {

    enum Level { DEBUG, INFO, WARN, ERROR }

    private static volatile Level min = Level.INFO;

    private Log() {
    }

    static void setLevel(String level) {
        min = Level.valueOf(level.toUpperCase());
    }

    static void debug(String format, Object... args) {
        log(Level.DEBUG, format, args);
    }

    static void info(String format, Object... args) {
        log(Level.INFO, format, args);
    }

    static void warn(String format, Object... args) {
        log(Level.WARN, format, args);
    }

    static void error(String format, Object... args) {
        log(Level.ERROR, format, args);
    }

    private static void log(Level level, String format, Object[] args) {
        if (level.compareTo(min) < 0) {
            return;
        }
        String message = args.length == 0 ? format : String.format(format, args);
        System.err.println(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " " + level + " " + message);
    }
}
