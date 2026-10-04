package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.Fees;

/**
 * The arithmetic of a trade: which way every rounding goes, and the two properties that follow
 * from it for every price — an immediate round trip always loses, and an order split into singles
 * never does better than the whole order.
 */
class ExchangeMathTest {
    private static final TradingTerms TERMS = TradingTerms.DEFAULTS;

    @Test void theAskIsRoundedUpAndTheBidDownAroundThePrice() {
        // 100.00 at a 1 % spread: half a percent each side.
        assertEquals(10_050L, ExchangeMath.ask(10_000L, 100));
        assertEquals(9_950L, ExchangeMath.bid(10_000L, 100));
        // 0.33 at 1 %: 0.33165 rounds up to 0.34, 0.32835 rounds down to 0.32.
        assertEquals(34L, ExchangeMath.ask(33L, 100));
        assertEquals(32L, ExchangeMath.bid(33L, 100));
        // The cheapest price there is still has an ask above it and a bid below it.
        assertEquals(2L, ExchangeMath.ask(1L, 100));
        assertEquals(0L, ExchangeMath.bid(1L, 100));
        // A risky share's 2 % spread.
        assertEquals(20_200L, ExchangeMath.ask(20_000L, 200));
        assertEquals(19_800L, ExchangeMath.bid(20_000L, 200));
        // No spread at all leaves the price as it is.
        assertEquals(12_345L, ExchangeMath.ask(12_345L, 0));
        assertEquals(12_345L, ExchangeMath.bid(12_345L, 0));
    }

    @Test void costIsRoundedUpAndProceedsDownToWholeCredits() {
        // 3 units at 1.01: 3.03 credits costs 4 and fetches 3.
        assertEquals(4L, ExchangeMath.cost(3L, 101L));
        assertEquals(3L, ExchangeMath.proceeds(3L, 101L));
        // An exact number of credits is not rounded at all.
        assertEquals(1_005L, ExchangeMath.cost(10L, 10_050L));
        assertEquals(995L, ExchangeMath.proceeds(10L, 9_950L));
        // One unit of something worth less than a credit costs a whole credit and fetches nothing.
        assertEquals(1L, ExchangeMath.cost(1L, 2L));
        assertEquals(0L, ExchangeMath.proceeds(1L, 99L));
        assertEquals(0L, ExchangeMath.cost(0L, 500L));
        assertEquals(0L, ExchangeMath.proceeds(5L, 0L));
    }

    @Test void theFeeIsTheSharedFeeRuleRoundedUpAndAtLeastOne() {
        assertEquals(11L, ExchangeMath.fee(1_005L, 100));
        assertEquals(1L, ExchangeMath.fee(1L, 100));
        assertEquals(0L, ExchangeMath.fee(0L, 100));
        assertEquals(0L, ExchangeMath.fee(500L, 0));
        assertEquals(Fees.fee(987_654L, 1.0D), ExchangeMath.fee(987_654L, 100));
        assertEquals(1_016L, ExchangeMath.spend(10L, 10_050L, 100));
    }

    @Test void hugeNumbersNeitherOverflowNorWrap() {
        long units = Holding.MAX_UNITS;
        long ask = ExchangeMath.ask(PriceModel.MAX_PRICE_CENTS, 2_000);
        assertEquals(Long.MAX_VALUE, ExchangeMath.cost(units, ask));
        assertEquals(Long.MAX_VALUE, ExchangeMath.spend(units, ask, 100));
        assertEquals(Long.MAX_VALUE, ExchangeMath.add(Long.MAX_VALUE - 1L, 5L));
        // Just inside what fits is still exact.
        assertEquals(92_233_720_368_547_758L, ExchangeMath.mulDiv(Long.MAX_VALUE, 1L, 100L, false));
        assertEquals(3_000_000_000_000_000_000L, ExchangeMath.mulDiv(3_000_000_000L, 4_000_000_000L, 4L, false));
        assertEquals(Long.MAX_VALUE, ExchangeMath.mulDiv(3_000_000_000L, 4_000_000_000L, 1L, false));
        assertEquals(20L, ExchangeMath.mulMulDiv(100L, 10_000L, 140L, 7_000_000L));
    }

    @Test void unitsForCreditsIsTheMostThatFitsFeeIncluded() {
        Random random = new Random(11L);
        for (int round = 0; round < 20_000; round++) {
            long ask = 1L + (round % 3 == 0 ? random.nextInt(300) : random.nextInt(500_000));
            long credits = 1L + random.nextInt(round % 5 == 0 ? 50 : 60_000);
            int fee = round % 7 == 0 ? 0 : random.nextInt(600);
            long units = ExchangeMath.unitsFor(credits, ask, fee);
            if (units > 0L) assertTrue(ExchangeMath.spend(units, ask, fee) <= credits, "too many at " + ask);
            assertTrue(ExchangeMath.spend(units + 1L, ask, fee) > credits, "one more would fit at " + ask);
        }
        assertEquals(0L, ExchangeMath.unitsFor(0L, 100L, 100));
        // 1016 credits buy exactly the ten units that cost 1005 plus a fee of 11; 1015 buy nine.
        assertEquals(10L, ExchangeMath.unitsFor(1_016L, 10_050L, 100));
        assertEquals(9L, ExchangeMath.unitsFor(1_015L, 10_050L, 100));
    }

    /** Every spread that ships, with the prices of every company that ships and the index. */
    private static List<long[]> shippedPricesAndSpreads() {
        List<long[]> out = new ArrayList<>();
        for (StockDefinition definition : ExchangeTesting.shipped().stocks())
            out.add(new long[] {definition.startCents(), TERMS.spreadBps(definition.type())});
        out.add(new long[] {IndexState.START_CENTS, TERMS.spreadIndexBps()});
        return out;
    }

    @Test void anImmediateRoundTripAlwaysLosesForEveryStockAndTheIndexAtAnyPrice() {
        Random random = new Random(20_261_003L);
        for (long[] product : shippedPricesAndSpreads()) {
            int spread = (int) product[1];
            for (int round = 0; round < 4_000; round++) {
                // The listing price, and prices all the way from the floor to a hundred times it.
                long price = round == 0 ? product[0] : Math.max(1L, (long) (product[0] * Math.pow(10.0D,
                        random.nextDouble() * 3.3D - 1.3D)));
                long units = 1L + (round % 4 == 0 ? random.nextInt(3) : random.nextInt(5_000));
                long ask = ExchangeMath.ask(price, spread);
                long bid = ExchangeMath.bid(price, spread);
                assertTrue(ask > bid, "ask above bid at " + price);
                long cost = ExchangeMath.cost(units, ask);
                long spent = cost + ExchangeMath.fee(cost, TERMS.feeBps());
                long proceeds = ExchangeMath.proceeds(units, bid);
                long received = proceeds - ExchangeMath.fee(proceeds, TERMS.feeBps());
                assertTrue(proceeds < cost, units + " units at " + price + ": fetched " + proceeds + " of " + cost);
                assertTrue(received < spent, units + " units at " + price);
                // And with no fee at all the spread alone still makes it a loss.
                assertTrue(ExchangeMath.proceeds(units, bid) < ExchangeMath.cost(units, ask));
            }
        }
    }

    @Test void splittingAnOrderIntoSinglesNeverDoesBetterThanTheWholeOrder() {
        Random random = new Random(42L);
        for (long[] product : shippedPricesAndSpreads()) {
            int spread = (int) product[1];
            for (int round = 0; round < 1_500; round++) {
                long price = Math.max(1L, (long) (product[0] * Math.pow(10.0D, random.nextDouble() * 3.0D - 1.3D)));
                int units = 1 + random.nextInt(200);
                long ask = ExchangeMath.ask(price, spread);
                long bid = ExchangeMath.bid(price, spread);
                long wholeSpend = ExchangeMath.spend(units, ask, TERMS.feeBps());
                long wholeProceeds = ExchangeMath.proceeds(units, bid);
                long wholeNet = wholeProceeds - ExchangeMath.fee(wholeProceeds, TERMS.feeBps());
                // As singles, and as any two parts.
                long singleProceeds = ExchangeMath.proceeds(1L, bid);
                long singlesSpend = units * ExchangeMath.spend(1L, ask, TERMS.feeBps());
                long singlesNet = units * (singleProceeds - ExchangeMath.fee(singleProceeds, TERMS.feeBps()));
                assertTrue(singlesSpend >= wholeSpend, "buying singles was cheaper at " + price);
                assertTrue(singlesNet <= wholeNet, "selling singles fetched more at " + price);
                int first = random.nextInt(units + 1);
                long partsSpend = ExchangeMath.spend(first, ask, TERMS.feeBps())
                        + ExchangeMath.spend(units - first, ask, TERMS.feeBps());
                long firstProceeds = ExchangeMath.proceeds(first, bid);
                long restProceeds = ExchangeMath.proceeds(units - first, bid);
                long partsNet = firstProceeds - ExchangeMath.fee(firstProceeds, TERMS.feeBps())
                        + restProceeds - ExchangeMath.fee(restProceeds, TERMS.feeBps());
                assertTrue(partsSpend >= wholeSpend, "buying in two parts was cheaper at " + price);
                assertTrue(partsNet <= wholeNet, "selling in two parts fetched more at " + price);
            }
        }
    }
}
