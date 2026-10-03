package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.text.TimeText.Remaining;

class TimeTextTest {
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;

    @Test void underAnHourIsMinutesAndSeconds() {
        assertEquals(new Remaining(TimeText.MINUTES_SECONDS, "00", "00"), TimeText.remaining(0L));
        assertEquals(new Remaining(TimeText.MINUTES_SECONDS, "00", "09"), TimeText.remaining(9L * SECOND + 999L));
        assertEquals(new Remaining(TimeText.MINUTES_SECONDS, "59", "59"), TimeText.remaining(HOUR - 1L));
    }

    @Test void fromAnHourIsHoursAndMinutes() {
        assertEquals(new Remaining(TimeText.HOURS_MINUTES, "1", "00"), TimeText.remaining(HOUR));
        assertEquals(new Remaining(TimeText.HOURS_MINUTES, "47", "59"), TimeText.remaining(2L * DAY - 1L));
    }

    @Test void fromTwoDaysIsDaysAndHours() {
        assertEquals(new Remaining(TimeText.DAYS_HOURS, "2", "0"), TimeText.remaining(2L * DAY));
        assertEquals(new Remaining(TimeText.DAYS_HOURS, "3", "5"), TimeText.remaining(3L * DAY + 5L * HOUR + 30L * MINUTE));
    }

    @Test void aPassedDeadlineReadsAsZeroNotAsANegativeTime() {
        assertEquals(new Remaining(TimeText.MINUTES_SECONDS, "00", "00"), TimeText.remaining(-5L * HOUR));
        assertEquals(new Remaining(TimeText.MINUTES_SECONDS, "00", "00"), TimeText.remaining(Long.MIN_VALUE));
    }

    @Test void aCountdownRunsFromWhenThePageArrived() {
        long received = 1_000_000_000_000L;
        assertEquals(90L * SECOND, TimeText.remainingNow(90L * SECOND, received, received));
        assertEquals(60L * SECOND, TimeText.remainingNow(90L * SECOND, received, received + 30_000_000_000L));
        assertEquals(0L, TimeText.remainingNow(90L * SECOND, received, received + 3_600_000_000_000L));
    }

    @Test void aCountdownNeverRunsUpIfTheLocalClockLooksEarlier() {
        long received = 1_000_000_000_000L;
        assertEquals(90L * SECOND, TimeText.remainingNow(90L * SECOND, received, received - 5_000_000_000L));
    }

    @Test void datesAreUtcToTheMinute() {
        assertEquals("1970-01-01 00:00", TimeText.utcMinutes(0L));
        assertEquals("2026-10-03 14:05", TimeText.utcMinutes(1_791_036_300_000L + 59_999L));
    }
}
