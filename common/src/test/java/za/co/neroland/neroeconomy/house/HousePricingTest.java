package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.commodity.CommodityDefinition;

/** The arithmetic of a House's quotes: spread, pressure, elasticity and recovery. */
class HousePricingTest {
    @Test void aWholeReferenceQuotesExactlyWhatTheDataAndTheGuardSay() {
        for (long price = 1L; price <= 2_000L; price++)
            for (int spread : new int[] {200, 1_000, 2_500, 7_500}) {
                assertEquals(CommodityDefinition.askAt(price, spread), HousePricing.ask(price * 1_000L, spread));
                assertEquals(CommodityDefinition.bidAt(price, spread), HousePricing.bid(price * 1_000L, spread));
            }
    }

    @Test void theAskIsAlwaysAboveTheBidWhateverTheReferenceAndPressure() {
        Random random = new Random(23L);
        for (int run = 0; run < 200_000; run++) {
            long reference = 1L + random.nextInt(random.nextBoolean() ? 50_000 : 2_000_000_000);
            int spread = CommodityDefinition.MIN_SPREAD_BPS
                    + random.nextInt(CommodityDefinition.MAX_SPREAD_BPS - CommodityDefinition.MIN_SPREAD_BPS + 1);
            long askPressure = random.nextInt(5) == 0 ? 0L : (long) random.nextInt((int) HousePricing.MAX_PRESSURE + 1);
            long bidPressure = random.nextInt(5) == 0 ? 0L : (long) random.nextInt((int) HousePricing.MAX_PRESSURE + 1);
            long ask = HousePricing.askWithPressure(HousePricing.ask(reference, spread), askPressure);
            long bid = HousePricing.bidWithPressure(HousePricing.bid(reference, spread), bidPressure);
            // Strictly above the reference, strictly below it.
            assertTrue(ask * 1_000L > reference, "ask " + ask + " ref " + reference);
            assertTrue(bid * 1_000L < reference, "bid " + bid + " ref " + reference);
            assertTrue(ask > bid);
            assertTrue(bid >= 0L);
        }
    }

    @Test void pressureMovesTheAskUpAndTheBidDownByElasticityTimesUnitsOverTheCap() {
        // 0.5 × 128 / 512 = 12.5 %.
        assertEquals(125_000L, HousePricing.pressureFor(0.5D, 128L, 512L));
        assertEquals(0L, HousePricing.pressureFor(0.5D, 128L, 0L));
        assertEquals(0L, HousePricing.pressureFor(0.0D, 128L, 512L));
        assertEquals(HousePricing.MAX_PRESSURE, HousePricing.pressureFor(4.0D, 10_000L, 1L));
        assertEquals(113L, HousePricing.askWithPressure(100L, 125_000L));
        assertEquals(87L, HousePricing.bidWithPressure(100L, 125_000L));
        assertEquals(0L, HousePricing.bidWithPressure(100L, HousePricing.MAX_PRESSURE));
        assertEquals(500L, HousePricing.askWithPressure(100L, HousePricing.MAX_PRESSURE));
        assertEquals(HousePricing.MAX_PRESSURE, HousePricing.add(HousePricing.MAX_PRESSURE - 1L, 10L));
        // More units, more pressure; never less.
        long last = 0L;
        for (long units = 1L; units <= 512L; units++) {
            long pressure = HousePricing.pressureFor(0.9D, units, 512L);
            assertTrue(pressure >= last);
            last = pressure;
        }
    }

    @Test void pressureRecoversByTheRecoveryEachStepAndReachesNothing() {
        assertEquals(96_000L, HousePricing.decay(100_000L, 0.04D));
        long pressure = HousePricing.MAX_PRESSURE;
        int steps = 0;
        while (pressure > 0L && steps < 100_000) {
            long next = HousePricing.decay(pressure, 0.01D);
            assertTrue(next < pressure);
            pressure = next;
            steps++;
        }
        assertEquals(0L, pressure);
        assertEquals(0L, HousePricing.decay(5L, 1.0D));
    }
}
