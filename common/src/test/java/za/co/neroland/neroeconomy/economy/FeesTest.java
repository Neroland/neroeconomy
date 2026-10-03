package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The one fee rule: rounded up, at least one credit, never more than the total, exact at any size. */
class FeesTest {
    @Test void aFeeIsRoundedUp() {
        assertEquals(2L, Fees.fee(100L, 2.0D));
        assertEquals(3L, Fees.fee(101L, 2.0D));
        assertEquals(3L, Fees.fee(150L, 2.0D));
        assertEquals(4L, Fees.fee(151L, 2.0D));
        assertEquals(5L, Fees.fee(100L, 5.0D));
        assertEquals(6L, Fees.fee(101L, 5.0D));
        assertEquals(1L, Fees.fee(3L, 33.33D));
        assertEquals(2L, Fees.fee(4L, 33.33D));
    }

    @Test void anyFeeThatAppliesIsAtLeastOneCredit() {
        assertEquals(1L, Fees.fee(1L, 2.0D));
        assertEquals(1L, Fees.fee(49L, 2.0D));
        assertEquals(1L, Fees.fee(1L, 0.01D));
        // A percentage too small to be one basis point still charges one.
        assertEquals(1, Fees.basisPoints(0.0001D));
        assertEquals(1L, Fees.fee(1_000L, 0.0001D));
    }

    @Test void zeroPercentAndNothingToChargeAreFree() {
        assertEquals(0L, Fees.fee(1_000L, 0.0D));
        assertEquals(0L, Fees.fee(0L, 5.0D));
        assertEquals(0L, Fees.fee(-10L, 5.0D));
        assertEquals(0L, Fees.fee(1_000L, -1.0D));
        assertEquals(0L, Fees.fee(1_000L, Double.NaN));
        assertEquals(0, Fees.basisPoints(Double.NaN));
        assertEquals(0L, Fees.feeForBasisPoints(1_000L, 0));
        assertEquals(1_000L, Fees.afterFee(1_000L, 0.0D));
        assertEquals(0L, Fees.afterFee(0L, 5.0D));
    }

    @Test void aFeeIsNeverMoreThanTheTotalOrHalfOfALargeOne() {
        assertEquals(1L, Fees.fee(1L, 50.0D));
        assertEquals(1L, Fees.fee(2L, 50.0D));
        assertEquals(2L, Fees.fee(3L, 50.0D));
        // The configured ceiling is 50 %; anything above it is clamped there.
        assertEquals(Fees.MAX_BASIS_POINTS, Fees.basisPoints(80.0D));
        assertEquals(Fees.MAX_BASIS_POINTS, Fees.basisPoints(Double.POSITIVE_INFINITY));
        assertEquals(50L, Fees.fee(100L, 99.0D));
        assertEquals(50L, Fees.feeForBasisPoints(100L, 9_999));
        for (long total = 1L; total <= 500L; total++)
            for (int points : new int[] {1, 50, 200, 500, 3_333, 5_000})
                assertTrue(Fees.feeForBasisPoints(total, points) <= total, total + " at " + points);
    }

    @Test void theArithmeticIsExactAtTheLargestTotals() {
        // 2 % of Long.MAX_VALUE, rounded up, checked against the same sum done by hand.
        long whole = Long.MAX_VALUE / 10_000L * 200L;
        long partial = (Long.MAX_VALUE % 10_000L * 200L + 9_999L) / 10_000L;
        assertEquals(whole + partial, Fees.fee(Long.MAX_VALUE, 2.0D));
        assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(200L))
                .add(java.math.BigInteger.valueOf(9_999L)).divide(java.math.BigInteger.valueOf(10_000L)).longValueExact(),
                Fees.fee(Long.MAX_VALUE, 2.0D));
        assertTrue(Fees.fee(Long.MAX_VALUE, 50.0D) > 0L);
        assertEquals(Long.MAX_VALUE - Fees.fee(Long.MAX_VALUE, 50.0D), Fees.afterFee(Long.MAX_VALUE, 50.0D));
        // Against exact big-number arithmetic across the whole range of sizes.
        Random random = new Random(7L);
        for (int index = 0; index < 5_000; index++) {
            long total = random.nextLong() & Long.MAX_VALUE;
            int points = 1 + random.nextInt(Fees.MAX_BASIS_POINTS);
            long expected = java.math.BigInteger.valueOf(total).multiply(java.math.BigInteger.valueOf(points))
                    .add(java.math.BigInteger.valueOf(9_999L)).divide(java.math.BigInteger.valueOf(10_000L))
                    .max(java.math.BigInteger.ONE).min(java.math.BigInteger.valueOf(total)).longValueExact();
            if (total == 0L) expected = 0L;
            assertEquals(expected, Fees.feeForBasisPoints(total, points), total + " at " + points);
        }
    }

    @Test void splittingAnOrderNeverPaysLessFeeThanTheWholeOrder() {
        for (long seed : new long[] {1L, 2L, 3L, 20_261_003L}) {
            Random random = new Random(seed);
            for (int round = 0; round < 4_000; round++) {
                int points = 1 + random.nextInt(Fees.MAX_BASIS_POINTS);
                long total = 1L + (random.nextInt(4) == 0 ? random.nextInt(1_000_000) : random.nextInt(500));
                long whole = Fees.feeForBasisPoints(total, points);
                // Into single credits: the cheapest-looking split of all.
                long singles = 0L;
                for (long unit = 0L; unit < Math.min(total, 2_000L); unit++) singles += Fees.feeForBasisPoints(1L, points);
                if (total <= 2_000L) assertTrue(singles >= whole, "singles of " + total + " at " + points);
                // Into random parts.
                long remaining = total;
                long parts = 0L;
                while (remaining > 0L) {
                    long part = 1L + (long) (random.nextDouble() * remaining);
                    part = Math.min(part, remaining);
                    parts += Fees.feeForBasisPoints(part, points);
                    remaining -= part;
                }
                assertTrue(parts >= whole, total + " at " + points + ": " + parts + " < " + whole);
            }
        }
    }

    @Test void thePercentageShownIsTheRateCharged() {
        assertEquals("2", Fees.percentText(2.0D));
        assertEquals("2.5", Fees.percentText(2.5D));
        assertEquals("2.05", Fees.percentText(2.05D));
        assertEquals("0.01", Fees.percentText(0.01D));
        assertEquals("0", Fees.percentText(0.0D));
        assertEquals("50", Fees.percentText(75.0D));
        assertEquals(3L, Fees.flat(3L));
        assertEquals(0L, Fees.flat(-3L));
    }
}
