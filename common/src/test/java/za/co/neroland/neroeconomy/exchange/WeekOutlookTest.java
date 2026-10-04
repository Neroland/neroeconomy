package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * "What could happen in a week": three outcomes per product, worked out from the model's published
 * numbers alone. (That they agree with what the model actually does is checked against a
 * simulation in {@code ExchangeCalibrationTest}.)
 */
class WeekOutlookTest {
    private static final ExchangeSettings SETTINGS = ExchangeSettings.DEFAULTS;
    private static final TradingTerms TERMS = TradingTerms.DEFAULTS;
    private static final long STEP = ExchangeTesting.STEP;

    @Test void aBondHasOneCertainOutcomeAtTheEndOfItsOwnTerm() {
        WeekOutlook.Outcome seven = WeekOutlook.bond(BondTerm.SEVEN_DAYS, TERMS, 1_000L);
        assertEquals(1_000L, seven.stake());
        assertEquals(1_080L, seven.bad());
        assertEquals(1_080L, seven.typical());
        assertEquals(1_080L, seven.good());
        assertEquals(800, seven.typicalBasisPoints());
        assertEquals(7, seven.days());
        WeekOutlook.Outcome one = WeekOutlook.bond(BondTerm.ONE_DAY, TERMS, 5_000L);
        assertEquals(5_040L, one.bad());
        assertEquals(1, one.days());
        // Whatever the stake, a bond never returns less than it.
        for (long stake : new long[] {0L, 1L, 7L, 124L, 99_999L})
            for (BondTerm term : BondTerm.values()) assertTrue(WeekOutlook.bond(term, TERMS, stake).bad() >= stake);
    }

    @Test void aShareHasABadATypicalAndAGoodWeekAroundItsDrift() {
        WeekOutlook.Outcome calm = WeekOutlook.stock(ExchangeWorld.CALM, SETTINGS, TERMS, STEP, 1_000L);
        assertTrue(calm.bad() < calm.typical() && calm.typical() < calm.good());
        assertEquals(7, calm.days());
        // Before costs the typical week is the drift: 6 %.
        assertTrue(Math.abs(calm.grossTypicalBasisPoints() - 600) <= 15, "typical " + calm.grossTypicalBasisPoints());
        // 2 % a day is about 4.2 % a week: the bad and the good week are about 5.4 % either side.
        assertTrue(calm.grossBadBasisPoints() > -100 && calm.grossBadBasisPoints() < 150, "bad " + calm.grossBadBasisPoints());
        assertTrue(calm.grossGoodBasisPoints() > 1_050 && calm.grossGoodBasisPoints() < 1_300, "good " + calm.grossGoodBasisPoints());
        // After costs — the spread and two fees, about 3 % — and with six days of dividend (1.2 %).
        assertTrue(calm.typicalBasisPoints() < calm.grossTypicalBasisPoints());
        assertTrue(calm.typicalBasisPoints() > calm.grossTypicalBasisPoints() - 320 + 100);
        assertTrue(Math.abs(1_000L + Math.floorDiv(calm.typicalBasisPoints() * 1_000L, 10_000L) - calm.typical()) <= 1L);
    }

    @Test void aRiskyShareHasAMuchWiderWeekThanASteadyOne() {
        WeekOutlook.Outcome calm = WeekOutlook.stock(ExchangeWorld.CALM, SETTINGS, TERMS, STEP, 1_000L);
        WeekOutlook.Outcome wild = WeekOutlook.stock(ExchangeWorld.WILD, SETTINGS, TERMS, STEP, 1_000L);
        assertTrue(wild.good() - wild.bad() > 4L * (calm.good() - calm.bad()));
        assertTrue(wild.bad() < 1_000L, "a bad week in a risky share loses");
        assertTrue(wild.good() > 1_300L);
        assertTrue(wild.typical() > calm.typical());
        // A week cannot move further than seven daily limits allow.
        assertTrue(wild.grossGoodBasisPoints() < (int) ((Math.pow(1.3D, 7.0D) - 1.0D) * 10_000.0D));
        assertTrue(wild.grossBadBasisPoints() > (int) ((Math.pow(0.7D, 7.0D) - 1.0D) * 10_000.0D));
    }

    @Test void theIndexFundSitsBetweenItsMembersAndIsNarrowerThanTheRiskyOnes() {
        List<StockDefinition> members = List.of(ExchangeWorld.CALM, ExchangeWorld.PLAIN, ExchangeWorld.WILD);
        WeekOutlook.Outcome index = WeekOutlook.index(members, SETTINGS, TERMS, STEP, 1_000L);
        WeekOutlook.Outcome calm = WeekOutlook.stock(ExchangeWorld.CALM, SETTINGS, TERMS, STEP, 1_000L);
        WeekOutlook.Outcome wild = WeekOutlook.stock(ExchangeWorld.WILD, SETTINGS, TERMS, STEP, 1_000L);
        assertTrue(index.bad() < index.typical() && index.typical() < index.good());
        assertTrue(index.good() - index.bad() < wild.good() - wild.bad());
        assertTrue(index.good() - index.bad() > calm.good() - calm.bad());
        assertTrue(index.grossTypicalBasisPoints() > calm.grossTypicalBasisPoints());
        assertTrue(index.grossTypicalBasisPoints() < wild.grossGoodBasisPoints());
        // A fund of one company is that company, without its dividend.
        WeekOutlook.Outcome alone = WeekOutlook.index(List.of(ExchangeWorld.PLAIN), SETTINGS, TERMS, STEP, 1_000L);
        WeekOutlook.Outcome plain = WeekOutlook.stock(ExchangeWorld.PLAIN, SETTINGS, TERMS, STEP, 1_000L);
        assertTrue(Math.abs(alone.grossTypicalBasisPoints() - plain.grossTypicalBasisPoints()) <= 25);
        assertTrue(Math.abs(alone.grossBadBasisPoints() - plain.grossBadBasisPoints()) <= 25);
        // A fund of nothing goes nowhere.
        WeekOutlook.Outcome empty = WeekOutlook.index(List.of(), SETTINGS, TERMS, STEP, 1_000L);
        assertEquals(0, empty.grossTypicalBasisPoints());
    }

    @Test void theOutlookIsTheSameEveryTimeAndScalesWithTheStake() {
        for (StockDefinition definition : ExchangeTesting.shipped().stocks()) {
            WeekOutlook.Outcome once = WeekOutlook.stock(definition, SETTINGS, TERMS, STEP, 2_000L);
            assertEquals(once, WeekOutlook.stock(definition, SETTINGS, TERMS, STEP, 2_000L));
            WeekOutlook.Outcome tenfold = WeekOutlook.stock(definition, SETTINGS, TERMS, STEP, 20_000L);
            assertEquals(once.typicalBasisPoints(), tenfold.typicalBasisPoints());
            assertTrue(Math.abs(tenfold.typical() - 10L * once.typical()) <= 10L);
            assertTrue(once.bad() <= once.typical() && once.typical() <= once.good(), definition.id());
            assertTrue(once.bad() >= 0L);
        }
        assertEquals(0L, WeekOutlook.stock(ExchangeWorld.CALM, SETTINGS, TERMS, STEP, 0L).good());
        assertEquals(0L, WeekOutlook.stock(ExchangeWorld.CALM, SETTINGS, TERMS, STEP, -5L).stake());
    }

    @Test void theNormalDistributionFunctionIsRight() {
        assertEquals(0.5D, WeekOutlook.cumulative(0.0D), 1.0e-7D);
        assertEquals(0.9D, WeekOutlook.cumulative(WeekOutlook.Z), 1.0e-6D);
        assertEquals(0.1D, WeekOutlook.cumulative(-WeekOutlook.Z), 1.0e-6D);
        assertEquals(0.97725D, WeekOutlook.cumulative(2.0D), 1.0e-5D);
        assertEquals(0.0D, WeekOutlook.cumulative(-9.0D), 1.0e-9D);
        assertEquals(1.0D, WeekOutlook.cumulative(9.0D), 1.0e-9D);
    }
}
