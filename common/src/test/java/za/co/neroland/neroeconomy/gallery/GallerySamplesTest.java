package za.co.neroland.neroeconomy.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The gallery's sample data: tickers that wobble and an Auto-Trader loop that only counts. */
class GallerySamplesTest {
    @Test void theLoopSellsThenBuysThenWaitsAndCountsEachPhaseWhenItEnds() {
        long phase = GallerySamples.LOOP_PHASE_MILLIS;
        assertEquals(new GallerySamples.LoopState(GallerySamples.LoopPhase.SELLING, 0L, 0L), GallerySamples.loopAt(0L));
        assertEquals(new GallerySamples.LoopState(GallerySamples.LoopPhase.SELLING, 0L, 0L),
                GallerySamples.loopAt(phase - 1L));
        assertEquals(new GallerySamples.LoopState(GallerySamples.LoopPhase.BUYING, 16L, 0L), GallerySamples.loopAt(phase));
        assertEquals(new GallerySamples.LoopState(GallerySamples.LoopPhase.WAITING, 16L, 4L),
                GallerySamples.loopAt(2L * phase));
        assertEquals(new GallerySamples.LoopState(GallerySamples.LoopPhase.SELLING, 16L, 4L),
                GallerySamples.loopAt(3L * phase));
        assertEquals(new GallerySamples.LoopState(GallerySamples.LoopPhase.WAITING, 160L, 40L),
                GallerySamples.loopAt(29L * phase));
        // A clock that went back reads as the start, and a very long run does not overflow.
        assertEquals(GallerySamples.loopAt(0L), GallerySamples.loopAt(-5_000L));
        assertTrue(GallerySamples.loopAt(Long.MAX_VALUE).sold() > 0L);
    }

    @Test void tickersWobbleAroundTheirBaseAndNeverReachZero() {
        for (int index = 0; index < GallerySamples.TICKERS.size(); index++) {
            long base = GallerySamples.TICKERS.get(index).basePrice();
            boolean moved = false;
            for (long step = -30L; step < 30L; step++) {
                long price = GallerySamples.tickerPrice(index, step);
                assertTrue(price >= 1L && Math.abs(price - base) <= 4L, "ticker " + index + " at " + step);
                int move = GallerySamples.tickerMove(index, step);
                assertEquals(Long.signum(price - GallerySamples.tickerPrice(index, step - 1L)), move);
                moved |= move != 0;
            }
            assertTrue(moved);
        }
        assertEquals(0L, GallerySamples.tickerPrice(-1, 0L));
        assertEquals(0L, GallerySamples.tickerPrice(GallerySamples.TICKERS.size(), 0L));
        assertEquals("command.neroeconomy.gallery.label.move.up", GallerySamples.moveKey(1));
        assertEquals("command.neroeconomy.gallery.label.move.down", GallerySamples.moveKey(-3));
        assertEquals("command.neroeconomy.gallery.label.move.flat", GallerySamples.moveKey(0));
    }

    @Test void theSampleBoardRowsHoldNothingRealAndAreAllPositive() {
        for (GallerySamples.BoardRow row : GallerySamples.BOARD_ROWS) {
            assertTrue(row.quantity() > 0 && row.unitPrice() > 0L);
            assertTrue(row.itemId().startsWith("minecraft:"));
        }
    }
}
