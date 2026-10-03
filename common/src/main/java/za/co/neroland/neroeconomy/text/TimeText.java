package za.co.neroland.neroeconomy.text;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How durations and dates are put in front of a player. Nothing here builds a sentence: a duration
 * comes back as a lang key plus its numbers, so the wording stays in the lang file, and a date comes
 * back as digits only. Pure Java so it is unit tested directly and usable on both sides.
 */
public final class TimeText {
    /** A duration as a lang key and its two zero-padded-where-needed numbers. */
    public record Remaining(String key, String first, String second) { }

    public static final String DAYS_HOURS = "gui.neroeconomy.time.days_hours";
    public static final String HOURS_MINUTES = "gui.neroeconomy.time.hours_minutes";
    public static final String MINUTES_SECONDS = "gui.neroeconomy.time.minutes_seconds";

    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60L;
    private static final long HOUR = 3_600L;
    private static final long DAY = 86_400L;
    private static final DateTimeFormatter UTC_MINUTES =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    private TimeText() { }

    /**
     * Time left, at the coarsest two units that still say something: days and hours from two days
     * up, hours and minutes from one hour up, otherwise minutes and seconds. Never negative.
     */
    public static Remaining remaining(long millis) {
        long seconds = Math.max(0L, millis) / SECOND;
        if (seconds >= 2L * DAY)
            return new Remaining(DAYS_HOURS, Long.toString(seconds / DAY), Long.toString(seconds % DAY / HOUR));
        if (seconds >= HOUR)
            return new Remaining(HOURS_MINUTES, Long.toString(seconds / HOUR), pad(seconds % HOUR / MINUTE));
        return new Remaining(MINUTES_SECONDS, pad(seconds / MINUTE), pad(seconds % MINUTE));
    }

    /**
     * What is left of a countdown the server sent as "{@code remainingAtReceipt} milliseconds from
     * now". The client counts down from the moment the page arrived, on its own monotonic clock, so
     * neither side's wall clock nor the difference between them enters into it.
     */
    public static long remainingNow(long remainingAtReceipt, long receivedNanos, long nowNanos) {
        long elapsedMillis = Math.max(0L, nowNanos - receivedNanos) / 1_000_000L;
        return Math.max(0L, remainingAtReceipt - elapsedMillis);
    }

    /** A wall-clock instant as {@code yyyy-MM-dd HH:mm} in UTC; the lang string names the zone. */
    public static String utcMinutes(long wallMillis) {
        return UTC_MINUTES.format(Instant.ofEpochMilli(wallMillis));
    }

    private static String pad(long value) {
        return value < 10L ? "0" + value : Long.toString(value);
    }
}
