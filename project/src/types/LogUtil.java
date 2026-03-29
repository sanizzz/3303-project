package types;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * This small helper adds the same timestamp format to every log message.
 * That makes it easier to follow what each thread is doing during the simulation.
 */
public final class LogUtil {

    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private LogUtil() {
    }

    /**
     * This adds the current time in front of a message.
     */
    public static String stamp(String message) {
        return "[" + LocalTime.now().format(LOG_TIME_FORMAT) + "] " + message;
    }
}
