package za.co.neroland.neroeconomy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the gauge tooltip maths: compact units, signed rates, percentages with a
 * zero capacity, legacy permille amounts, gas units, time remaining in both directions, and the
 * sliding rate window (one sample per tick, gap and jump resets, negative rates). Pure JVM.
 */
class GaugeMathTest {

    @Test
    void compactUnits() {
        assertEquals("0", GaugeMath.compact(0));
        assertEquals("999", GaugeMath.compact(999));
        assertEquals("1k", GaugeMath.compact(1_000));
        assertEquals("1.2k", GaugeMath.compact(1_234));
        assertEquals("12.5k", GaugeMath.compact(12_500));
        assertEquals("125k", GaugeMath.compact(125_000));
        assertEquals("1M", GaugeMath.compact(999_999));
        assertEquals("3.4M", GaugeMath.compact(3_400_000));
        assertEquals("1.1G", GaugeMath.compact(1_073_741_823));
        assertEquals("-1.5k", GaugeMath.compact(-1_500));
        assertTrue(GaugeMath.compact(Long.MIN_VALUE).startsWith("-"));
    }

    @Test
    void groupedIsLocaleIndependent() {
        assertEquals("1,234,567", GaugeMath.grouped(1_234_567));
        assertEquals("-12", GaugeMath.grouped(-12));
    }

    @Test
    void signedRates() {
        assertEquals("+120", GaugeMath.signedRate(120));
        assertEquals("-80", GaugeMath.signedRate(-80));
        assertEquals("0", GaugeMath.signedRate(0));
        assertEquals("0", GaugeMath.signedRate(0.01));
        assertEquals("+2.5", GaugeMath.signedRate(2.5));
        assertEquals("+4", GaugeMath.signedRate(4.0));
        assertEquals("-1.5k", GaugeMath.signedRate(-1_500));
        assertEquals("0", GaugeMath.signedRate(Double.NaN));
        assertEquals("80", GaugeMath.rate(-80));
        assertEquals("0", GaugeMath.rate(0));
    }

    @Test
    void percentHandlesZeroAndOverflow() {
        assertEquals(0, GaugeMath.percent(500, 0));
        assertEquals(0, GaugeMath.percent(500, -1));
        assertEquals(50, GaugeMath.percent(500, 1_000));
        assertEquals(100, GaugeMath.percent(2_000, 1_000));
        assertEquals(0, GaugeMath.percent(-5, 1_000));
    }

    @Test
    void permilleAmountsAndGasUnits() {
        assertEquals(0L, GaugeMath.fromPermille(0, 16_000));
        assertEquals(8_000L, GaugeMath.fromPermille(500, 16_000));
        assertEquals(16_000L, GaugeMath.fromPermille(1_200, 16_000));
        assertEquals(0L, GaugeMath.fromPermille(500, 0));
        assertEquals(2.5D, GaugeMath.units(250, 100));
        assertEquals(0D, GaugeMath.units(250, 0));
        assertEquals("2.5", GaugeMath.unitCount(2.5));
        assertEquals("12", GaugeMath.unitCount(12));
        assertEquals("0", GaugeMath.unitCount(0));
        assertEquals("1.5k", GaugeMath.unitCount(1_500));
    }

    @Test
    void timeRemaining() {
        // 10 permille per tick from 500: 50 ticks = 2.5 s.
        assertEquals(50L, GaugeMath.ticksRemaining(500, 10));
        // A falling gauge (a generator burning down) counts to 0.
        assertEquals(25L, GaugeMath.ticksRemaining(250, -10));
        assertEquals(-1L, GaugeMath.ticksRemaining(500, 0));
        assertEquals(-1L, GaugeMath.ticksRemaining(500, Double.NaN));
        assertEquals("2.5s", GaugeMath.duration(50));
        assertEquals("0.1s", GaugeMath.duration(0));
        assertEquals("42s", GaugeMath.duration(840));
        assertEquals("3m 05s", GaugeMath.duration(185 * 20));
        assertEquals("2h 03m", GaugeMath.duration((2 * 3_600 + 3 * 60) * 20L));
        assertEquals("?", GaugeMath.duration(-1));
    }

    @Test
    void trendDeadband() {
        assertEquals(0, GaugeMath.trend(0.3, 0.5));
        assertEquals(1, GaugeMath.trend(3, 0.5));
        assertEquals(-1, GaugeMath.trend(-3, 0.5));
        assertEquals(0, GaugeMath.trend(Double.NaN, 0.5));
    }

    @Test
    void rateWindowMeasuresPerTick() {
        GaugeMath.RateWindow w = new GaugeMath.RateWindow();
        assertEquals(0D, w.perTick());
        for (int t = 0; t <= 30; t++) {
            w.sample(100 + t, 1_000 + 5L * t);
            w.sample(100 + t, 999_999); // a second frame in the same tick is ignored
        }
        assertEquals(5D, w.perTick(), 1.0E-9);
        assertEquals(100D, w.perSecond(), 1.0E-9);
        assertTrue(w.span() <= GaugeMath.RateWindow.WINDOW_TICKS);
    }

    @Test
    void rateWindowNegativeRate() {
        GaugeMath.RateWindow w = new GaugeMath.RateWindow();
        for (int t = 0; t < 10; t++) {
            w.sample(t, 1_000 - 80L * t);
        }
        assertEquals(-80D, w.perTick(), 1.0E-9);
    }

    @Test
    void rateWindowResetsOnGapAndJump() {
        GaugeMath.RateWindow w = new GaugeMath.RateWindow(300);
        w.sample(0, 0);
        w.sample(1, 10);
        w.sample(100, 20); // pause: starts again
        assertEquals(1, w.size());
        assertEquals(0D, w.perTick());
        w.sample(101, 30);
        w.sample(102, 1_000); // job boundary jump
        assertEquals(1, w.size());
        w.sample(50, 0); // time went backwards (new level)
        assertEquals(1, w.size());
        w.reset();
        assertEquals(0, w.size());
    }

    @Test
    void changeBasisPoints() {
        assertEquals(0, GaugeMath.changeBasisPoints(0, 500));
        assertEquals(1_000, GaugeMath.changeBasisPoints(1_000, 1_100));
        assertEquals(-2_500, GaugeMath.changeBasisPoints(400, 300));
        assertEquals(0, GaugeMath.changeBasisPoints(250, 250));
    }

    @Test
    void clampInt() {
        assertEquals(Integer.MAX_VALUE, GaugeMath.clampInt(Long.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, GaugeMath.clampInt(Long.MIN_VALUE));
        assertEquals(7, GaugeMath.clampInt(7));
    }
}
