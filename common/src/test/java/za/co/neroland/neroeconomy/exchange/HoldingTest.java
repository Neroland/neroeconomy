package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

/** The lot arithmetic of one holding: oldest first, the holding period, merging and its bounds. */
class HoldingTest {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = PriceModel.DAY_MILLIS;
    /** The start of an economy-day, so the day arithmetic in the tests is easy to read. */
    private static final long T0 = 20_834L * DAY;

    private static Holding holding() { return new Holding("test:calm"); }

    @Test void sellingTakesTheOldestLotFirstAndItsCostWithIt() {
        Holding holding = holding();
        holding.add(10L, 1_000L, T0);
        holding.add(5L, 750L, T0 + 10L);
        assertEquals(15L, holding.units());
        assertEquals(1_750L, holding.cost());
        // Four of the first lot: four tenths of its cost.
        assertEquals(400L, holding.basisOf(4L, T0 + 20L, 0L));
        assertEquals(400L, holding.take(4L, T0 + 20L, 0L));
        assertEquals(List.of(new Holding.Lot(6L, 600L, T0), new Holding.Lot(5L, 750L, T0 + 10L)), holding.lots());
        // The rest of the first lot and two of the second.
        assertEquals(600L + 300L, holding.take(8L, T0 + 20L, 0L));
        assertEquals(List.of(new Holding.Lot(3L, 450L, T0 + 10L)), holding.lots());
        assertEquals(450L, holding.take(3L, T0 + 20L, 0L));
        assertTrue(holding.isEmpty());
        assertEquals(0L, holding.cost());
    }

    @Test void aPartOfALotTakesItsShareOfTheCostRoundedDownAndTheLotKeepsTheRest() {
        Holding holding = holding();
        holding.add(3L, 100L, T0);
        // A third of 100 is 33; the next third of what is left (67 for 2) is 33; the last gets 34.
        assertEquals(33L, holding.take(1L, T0, 0L));
        assertEquals(67L, holding.cost());
        assertEquals(33L, holding.take(1L, T0, 0L));
        assertEquals(34L, holding.take(1L, T0, 0L));
        assertTrue(holding.isEmpty());
    }

    @Test void whateverIsSoldInWhateverPiecesTheCostTakenAddsUpToTheCostPaid() {
        Random random = new Random(5L);
        for (int round = 0; round < 500; round++) {
            Holding holding = holding();
            long paid = 0L;
            long units = 0L;
            for (int lot = 0; lot < 1 + random.nextInt(6); lot++) {
                long lotUnits = 1L + random.nextInt(50);
                long cost = random.nextInt(5_000);
                holding.add(lotUnits, cost, T0 + lot);
                paid += cost;
                units += lotUnits;
            }
            long taken = 0L;
            while (!holding.isEmpty()) {
                long piece = 1L + random.nextInt((int) Math.min(40L, holding.units()));
                long before = holding.cost();
                long basis = holding.take(piece, T0 + 100L, 0L);
                assertEquals(before - basis, holding.cost());
                assertTrue(basis >= 0L);
                taken += basis;
                units -= piece;
                assertEquals(units, holding.units());
            }
            assertEquals(paid, taken);
        }
    }

    @Test void aLotCannotBeSoldUntilTheHoldingPeriodHasPassed() {
        Holding holding = holding();
        holding.add(10L, 1_000L, T0);
        holding.add(4L, 400L, T0 + 30L * 60_000L);
        // At 59 minutes nothing may be sold; the first lot is due at the hour.
        assertEquals(0L, holding.sellableUnits(T0 + HOUR - 1L, HOUR));
        assertEquals(T0 + HOUR, holding.earliestSellAt(T0 + HOUR - 1L, HOUR));
        assertEquals(-1L, holding.basisOf(1L, T0 + HOUR - 1L, HOUR));
        assertThrows(IllegalStateException.class, () -> holding.take(1L, T0 + HOUR - 1L, HOUR));
        // At the hour exactly the first lot may be sold, and only it.
        assertEquals(10L, holding.sellableUnits(T0 + HOUR, HOUR));
        assertEquals(T0 + 90L * 60_000L, holding.earliestSellAt(T0 + HOUR, HOUR));
        assertEquals(-1L, holding.basisOf(11L, T0 + HOUR, HOUR));
        assertEquals(1_000L, holding.take(10L, T0 + HOUR, HOUR));
        assertEquals(1, holding.youngLots(T0 + HOUR, HOUR));
        // And half an hour later the second one; then nothing is waiting any more.
        assertEquals(4L, holding.sellableUnits(T0 + 90L * 60_000L, HOUR));
        assertEquals(0L, holding.earliestSellAt(T0 + 90L * 60_000L, HOUR));
        assertEquals(0, holding.youngLots(T0 + 90L * 60_000L, HOUR));
        // A holding period of nothing never holds anything back.
        assertEquals(4L, holding.sellableUnits(T0, 0L));
    }

    @Test void lotsPastTheHoldingPeriodAreMergedPerDayAndJoinTheFirstLotOnceADividendDayHasPassed() {
        Holding holding = holding();
        // Three purchases on the first day, two on the second, one just now.
        holding.add(1L, 10L, T0 + HOUR);
        holding.add(2L, 20L, T0 + 2L * HOUR);
        holding.add(3L, 30L, T0 + 3L * HOUR);
        holding.add(4L, 40L, T0 + DAY + HOUR);
        holding.add(5L, 50L, T0 + DAY + 2L * HOUR);
        long now = T0 + DAY + 2L * HOUR + 30L * 60_000L;
        holding.add(6L, 60L, now);
        // No dividend day has been paid since any of them was bought: one lot per day, and the young one.
        assertTrue(holding.settle(now, HOUR, T0));
        assertEquals(List.of(new Holding.Lot(6L, 60L, T0 + 3L * HOUR), new Holding.Lot(4L, 40L, T0 + DAY + HOUR),
                new Holding.Lot(5L, 50L, T0 + DAY + 2L * HOUR), new Holding.Lot(6L, 60L, now)), holding.lots());
        // The lot of two hours ago is past the holding period a little later, and joins its day.
        assertTrue(holding.settle(now + HOUR, HOUR, T0));
        assertEquals(List.of(new Holding.Lot(6L, 60L, T0 + 3L * HOUR),
                new Holding.Lot(15L, 150L, now)), holding.lots());
        // Settling again changes nothing.
        assertFalse(holding.settle(now + HOUR, HOUR, T0));
        // The dividend day at the start of the third day has been paid: everything bought before it is one lot.
        assertTrue(holding.settle(T0 + 2L * DAY + 1L, HOUR, T0 + 2L * DAY));
        assertEquals(List.of(new Holding.Lot(21L, 210L, now)), holding.lots());
        assertEquals(21L, holding.units());
        assertEquals(210L, holding.cost());
    }

    @Test void mergingNeverChangesWhatADividendCounts() {
        // Lots merged per day are told apart by no day roll; a lot bought on the stroke of a day
        // belongs to the day before.
        Holding holding = holding();
        holding.add(7L, 70L, T0);
        holding.add(1L, 10L, T0 + 1L);
        holding.add(2L, 20L, T0 + 5L * HOUR);
        long before = holding.unitsHeldSince(T0);
        assertEquals(7L, before);
        holding.settle(T0 + 6L * HOUR, 0L, T0 - DAY);
        assertEquals(List.of(new Holding.Lot(7L, 70L, T0), new Holding.Lot(3L, 30L, T0 + 5L * HOUR)), holding.lots());
        assertEquals(7L, holding.unitsHeldSince(T0));
        assertEquals(10L, holding.unitsHeldSince(T0 + DAY));
        assertEquals(0L, holding.unitsHeldSince(T0 - 1L));
    }

    @Test void aHoldingStaysShortHoweverOftenItsOwnerBuys() {
        Holding holding = holding();
        long now = T0;
        int most = 0;
        long units = 0L;
        long paid = 0L;
        long seasoned = Long.MIN_VALUE;
        // A purchase every ten minutes for thirty days, with a dividend day paid at every day roll.
        for (int purchase = 0; purchase < 30 * 144; purchase++) {
            if (now % DAY == 0L) seasoned = now;
            holding.settle(now, HOUR, seasoned);
            holding.add(1L, 100L, now);
            units++;
            paid += 100L;
            most = Math.max(most, holding.lotCount());
            assertTrue(holding.youngLots(now, HOUR) <= Holding.MAX_YOUNG_LOTS);
            now += 600_000L;
        }
        // The first lot, one for the day so far, and the six of the last hour.
        assertTrue(most <= 9, "a holding grew to " + most + " lots");
        assertEquals(units, holding.units());
        assertEquals(paid, holding.cost());
    }

    @Test void aSplitMultipliesTheUnitsAndLeavesTheCostAndTheDatesAlone() {
        Holding holding = holding();
        holding.add(3L, 6_000L, T0);
        holding.add(1L, 2_100L, T0 + 5L);
        holding.split(10);
        assertEquals(List.of(new Holding.Lot(30L, 6_000L, T0), new Holding.Lot(10L, 2_100L, T0 + 5L)), holding.lots());
        holding.split(1);
        assertEquals(40L, holding.units());
        assertEquals(8_100L, holding.cost());
    }

    @Test void aHoldingSurvivesASaveAndALoadAndBadRowsAreLeftOut() {
        Holding holding = holding();
        holding.add(3L, 300L, T0);
        holding.add(2L, 0L, T0 + 9L);
        JsonElement saved = Holding.CODEC.encodeStart(JsonOps.INSTANCE, holding).getOrThrow();
        Holding loaded = Holding.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(holding.lots(), loaded.lots());
        assertEquals("test:calm", loaded.stockId());
        // A lot of no units, or of a negative cost, is not a lot.
        saved.getAsJsonObject().getAsJsonArray("lots").get(0).getAsJsonObject().addProperty("units", 0L);
        saved.getAsJsonObject().getAsJsonArray("lots").get(1).getAsJsonObject().addProperty("cost", -5L);
        assertTrue(Holding.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().isEmpty());
    }

    @Test void takingEverythingIgnoresTheHoldingPeriod() {
        Holding holding = holding();
        holding.add(3L, 300L, T0);
        holding.add(2L, 250L, T0 + 9L);
        assertEquals(550L, holding.takeAll());
        assertTrue(holding.isEmpty());
    }
}
