package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.simulation.SimRandom;

/** The price model's formulas and limits, one company at a time, with the random number chosen by the test. */
class PriceModelTest {
    private static final long STEP = ExchangeTesting.STEP;
    private static final long FIRST = ExchangeTesting.DAY_START;
    private static final ExchangeSettings SETTINGS = ExchangeSettings.DEFAULTS;

    private static void advance(StockDefinition definition, StockState state, long step, double z) {
        PriceModel.advance(definition, state, SETTINGS, step, STEP, z, 0L, 1.0D);
    }

    // --- the plain move --------------------------------------------------------------------------

    @Test void withNoNoiseThePriceFollowsItsAnchorUpByTheDrift() {
        StockDefinition definition = ExchangeTesting.steady("calm");
        StockState state = StockState.listed(definition);
        assertEquals(10_000L, state.price());
        assertEquals(10_000L * PriceModel.MICROS_PER_CENT, state.anchorMicros());
        // The daily limit is 10 %, and 15 % a week is about 2 % a day, so nothing is clipped.
        for (int index = 0; index < ExchangeTesting.STEPS_PER_WEEK; index++) advance(definition, state, FIRST + index, 0.0D);
        // One week at 15 % a week: 115 credits, to the hundredth.
        assertEquals(11_500L, state.price(), 1L);
        assertEquals(115_000_000L, state.anchorMicros(), 2_000L);
        assertEquals(FIRST + ExchangeTesting.STEPS_PER_WEEK - 1L, state.lastStep());
    }

    @Test void theSameInputsGiveTheSamePriceAndADifferentNumberADifferentOne() {
        StockDefinition definition = ExchangeTesting.risky("twin");
        StockState one = StockState.listed(definition);
        StockState two = StockState.listed(definition);
        StockState other = StockState.listed(definition);
        for (int index = 0; index < 50; index++) {
            double z = Math.sin(index) * 1.5D;
            advance(definition, one, FIRST + index, z);
            advance(definition, two, FIRST + index, z);
            advance(definition, other, FIRST + index, -z);
        }
        assertEquals(one.price(), two.price());
        assertEquals(one.anchorMicros(), two.anchorMicros());
        assertNotEquals(one.price(), other.price());
    }

    @Test void theGaussianIsAFunctionOfTheStreamAndLooksNormal() {
        byte[] secret = ExchangeTesting.secret(5L);
        double first = PriceModel.gaussian(SimRandom.of(secret, ExchangeStep.NAME, 7L, "price:a"));
        assertEquals(first, PriceModel.gaussian(SimRandom.of(secret, ExchangeStep.NAME, 7L, "price:a")));
        assertNotEquals(first, PriceModel.gaussian(SimRandom.of(secret, ExchangeStep.NAME, 8L, "price:a")));
        assertNotEquals(first, PriceModel.gaussian(SimRandom.of(secret, ExchangeStep.NAME, 7L, "price:b")));
        assertNotEquals(first, PriceModel.gaussian(SimRandom.of(ExchangeTesting.secret(6L), ExchangeStep.NAME, 7L, "price:a")));
        double sum = 0.0D;
        double squares = 0.0D;
        int count = 20_000;
        for (int step = 0; step < count; step++) {
            double z = PriceModel.gaussian(SimRandom.of(secret, ExchangeStep.NAME, step, "price:a"));
            assertTrue(Double.isFinite(z));
            sum += z;
            squares += z * z;
        }
        assertEquals(0.0D, sum / count, 0.03D);
        assertEquals(1.0D, squares / count, 0.05D);
    }

    // --- limits ----------------------------------------------------------------------------------

    @Test void oneStepNeverMovesAPriceMoreThanItsStepLimit() {
        for (StockDefinition definition : new StockDefinition[] {ExchangeTesting.steady("s"), ExchangeTesting.risky("r")}) {
            int limitBps = SETTINGS.stepLimitBps(definition.type());
            StockState state = StockState.listed(definition);
            for (int index = 0; index < 600; index++) {
                long before = state.price();
                // Far more than any real draw, alternating in sign in runs of seven.
                advance(definition, state, FIRST + index, (index / 7) % 2 == 0 ? 9.0D : -9.0D);
                long band = Math.max(1L, before * limitBps / 10_000L);
                assertTrue(Math.abs(state.price() - before) <= band, "step " + index + ": " + before + " -> " + state.price());
            }
        }
        // The limits themselves: 2 % and 6 %.
        assertEquals(200, SETTINGS.stepLimitBps(ExchangeTesting.steady("s").type()));
        assertEquals(600, SETTINGS.stepLimitBps(ExchangeTesting.risky("r").type()));
    }

    @Test void aPriceNeverLeavesItsDailyBandAndTheBandIsMeasuredFromTheDayOpen() {
        for (StockDefinition definition : new StockDefinition[] {ExchangeTesting.steady("s"), ExchangeTesting.risky("r")}) {
            int limitBps = SETTINGS.dayLimitBps(definition.type());
            for (double z : new double[] {9.0D, -9.0D}) {
                StockState state = StockState.listed(definition);
                long open = state.price();
                for (int index = 0; index < ExchangeTesting.STEPS_PER_DAY; index++) {
                    advance(definition, state, FIRST + index, z);
                    assertEquals(open, state.dayOpen());
                    assertTrue(Math.abs(state.price() - open) <= open * limitBps / 10_000L);
                }
                // It got all the way to the limit, and the next day opens from there.
                assertEquals(open + (z > 0.0D ? 1L : -1L) * (open * limitBps / 10_000L), state.price());
                long close = state.price();
                advance(definition, state, FIRST + ExchangeTesting.STEPS_PER_DAY, 0.0D);
                assertEquals(close, state.dayOpen());
                assertEquals(1, state.history().dailySize());
                assertEquals(close, state.history().daily()[0]);
            }
        }
        assertEquals(1_000, SETTINGS.dayLimitBps(ExchangeTesting.steady("s").type()));
        assertEquals(4_000, SETTINGS.dayLimitBps(ExchangeTesting.risky("r").type()));
    }

    @Test void aPriceNeverFallsBelowTheFloorHoweverLongItFalls() {
        StockDefinition definition = ExchangeTesting.risky("doomed");
        StockState state = StockState.listed(definition);
        long floor = PriceModel.floorCents(definition.startCents(), SETTINGS.floorBps());
        // 5 % of 200 credits.
        assertEquals(1_000L, floor);
        long lowest = Long.MAX_VALUE;
        for (int index = 0; index < 40 * ExchangeTesting.STEPS_PER_DAY; index++) {
            advance(definition, state, FIRST + index, -9.0D);
            lowest = Math.min(lowest, state.price());
            assertTrue(state.price() >= floor);
        }
        assertEquals(floor, lowest);
        // On the floor it is not halted for it, and it can come back up.
        long step = FIRST + 41L * ExchangeTesting.STEPS_PER_DAY;
        advance(definition, state, step, 0.0D);
        assertTrue(state.price() >= floor);
        advance(definition, state, step + 1L, 9.0D);
        advance(definition, state, step + 2L, 9.0D);
        assertTrue(state.price() > floor);
        // The floor is never below 0.01 credit, whatever the start price and the percentage.
        assertEquals(1L, PriceModel.floorCents(100L, 0));
        assertEquals(5L, PriceModel.floorCents(100L, 500));
        assertEquals(1L, PriceModel.floorCents(100L, 50));
    }

    // --- the circuit breaker ---------------------------------------------------------------------

    @Test void aShareThatReachesItsDailyLimitIsHaltedForSixStepsAndThenMovesAgain() {
        StockDefinition definition = ExchangeTesting.steady("breaker");
        StockState state = StockState.listed(definition);
        long step = FIRST;
        // 2 % a step toward a 10 % limit: it gets there on the fifth step.
        while (state.haltedUntil() == StockState.NEVER) {
            advance(definition, state, step, 9.0D);
            assertTrue(step - FIRST < 10L);
            step++;
        }
        long hit = step - 1L;
        assertEquals(11_000L, state.price());
        assertEquals(hit + 6L, state.haltedUntil());
        assertTrue(state.halted(hit));
        long anchor = state.anchorMicros();
        // Six steps: the price stands still whatever the random number is; the anchor keeps growing.
        for (int index = 1; index <= 6; index++) {
            advance(definition, state, hit + index, -9.0D);
            assertEquals(11_000L, state.price());
            assertEquals(hit + index, state.lastStep());
            assertEquals(index < 6, state.halted(hit + index));
        }
        assertTrue(state.anchorMicros() > anchor);
        // The seventh step moves it again: far down, so by the whole step limit of 2 %.
        advance(definition, state, hit + 7L, -30.0D);
        assertEquals(11_000L - 220L, state.price());
        assertFalse(state.halted(hit + 7L));
        // Every step is in the history, the frozen ones too.
        assertEquals((int) (hit + 7L - FIRST + 1L), state.history().recentSize());
    }

    @Test void aShareStillAtItsLimitAfterAHaltIsHaltedAgainAndZeroHaltStepsNeverHalts() {
        StockDefinition definition = ExchangeTesting.steady("stuck");
        StockState state = StockState.listed(definition);
        long step = FIRST;
        while (state.haltedUntil() == StockState.NEVER) advance(definition, state, step++, 9.0D);
        long first = state.haltedUntil();
        for (long at = step; at <= first + 1L; at++) advance(definition, state, at, 9.0D);
        assertEquals(first + 1L + 6L, state.haltedUntil());
        assertEquals(11_000L, state.price());

        ExchangeSettings never = SETTINGS.withLimits(200, 600, 1_000, 3_000, 0);
        StockState free = StockState.listed(definition);
        for (int index = 0; index < 30; index++)
            PriceModel.advance(definition, free, never, FIRST + index, STEP, 9.0D, 0L, 1.0D);
        assertEquals(StockState.NEVER, free.haltedUntil());
        assertEquals(11_000L, free.price());
    }

    // --- the volume nudge ------------------------------------------------------------------------

    @Test void tradedVolumeMovesAPriceByAtMostHalfAPercentAStepEitherWay() {
        StockDefinition definition = ExchangeTesting.steady("busy");
        StockState quiet = StockState.listed(definition);
        StockState busy = StockState.listed(definition);
        StockState flooded = StockState.listed(definition);
        PriceModel.advance(definition, quiet, SETTINGS, FIRST, STEP, 0.0D, 0L, 1.0D);
        // 2000 credits above the usual level (none) is the whole nudge; a million credits is no more.
        PriceModel.advance(definition, busy, SETTINGS, FIRST, STEP, 0.0D, 2_000L, 1.0D);
        PriceModel.advance(definition, flooded, SETTINGS, FIRST, STEP, 0.0D, 1_000_000L, 1.0D);
        // With no trade the price only follows its drift: a little over 100 credits.
        assertEquals(10_001L, quiet.price());
        assertEquals(Math.round(quiet.price() * 1.005D), busy.price(), 1L);
        assertEquals(busy.price(), flooded.price());
        assertTrue((double) flooded.price() / quiet.price() <= 1.005D + 1.0e-4D);
        // Half the scale gives half the nudge.
        StockState half = StockState.listed(definition);
        PriceModel.advance(definition, half, SETTINGS, FIRST, STEP, 0.0D, 1_000L, 1.0D);
        assertEquals(Math.round(quiet.price() * 1.0025D), half.price(), 1L);
        // The usual level has moved an eighth of the way up.
        assertEquals(2_000L * 100L / 8L, busy.volumeBaseline());

        // Trade that stops after a busy spell pulls the other way, by no more than the same cap.
        StockState cooling = StockState.listed(definition);
        for (int index = 0; index < 200; index++)
            PriceModel.advance(definition, cooling, SETTINGS, FIRST + index, STEP, 0.0D, 50_000L, 1.0D);
        // Steady trade does nothing once it is the usual level.
        assertEquals(50_000L * 100L, cooling.volumeBaseline());
        long before = cooling.price();
        StockState same = StockState.listed(definition);
        same.price = cooling.price;
        same.anchorMicros = cooling.anchorMicros;
        same.carryMicros = cooling.carryMicros;
        same.dayOpen = cooling.dayOpen;
        same.day = cooling.day;
        PriceModel.advance(definition, same, SETTINGS, FIRST + 200, STEP, 0.0D, 0L, 1.0D);
        PriceModel.advance(definition, cooling, SETTINGS, FIRST + 200, STEP, 0.0D, 0L, 1.0D);
        double ratio = (double) cooling.price() / same.price();
        assertTrue(ratio < 1.0D, "no trade after a busy spell lowers the price");
        assertTrue(ratio >= 0.995D - 1.0e-4D, "by at most half a percent: " + ratio);
        assertTrue(before > 0L);
    }

    @Test void theUsualVolumeAlwaysArrivesAndTheNudgeCanBeSwitchedOff() {
        StockDefinition definition = ExchangeTesting.steady("level");
        StockState state = StockState.listed(definition);
        for (int index = 0; index < 400; index++)
            PriceModel.advance(definition, state, SETTINGS, FIRST + index, STEP, 0.0D, 7L, 1.0D);
        assertEquals(700L, state.volumeBaseline());
        for (int index = 400; index < 1_200; index++)
            PriceModel.advance(definition, state, SETTINGS, FIRST + index, STEP, 0.0D, 0L, 1.0D);
        assertEquals(0L, state.volumeBaseline());

        ExchangeSettings off = new ExchangeSettings(true, 200, 600, 1_000, 3_000, 6, 0.0005D, 0.0D, 2_000L, 250L, 1_000L,
                0.01D, 0.25D, 4.0D, 500, 80, 300, 800);
        StockState unmoved = StockState.listed(definition);
        StockState quiet = StockState.listed(definition);
        PriceModel.advance(definition, unmoved, off, FIRST, STEP, 0.0D, 1_000_000L, 1.0D);
        PriceModel.advance(definition, quiet, off, FIRST, STEP, 0.0D, 0L, 1.0D);
        assertEquals(quiet.price(), unmoved.price());
    }

    // --- shocks ----------------------------------------------------------------------------------

    @Test void anActiveShockPushesThePriceAndIsClampedToTheShockLimits() {
        StockDefinition definition = ExchangeTesting.risky("shocked");
        StockState calm = StockState.listed(definition);
        StockState up = StockState.listed(definition);
        StockState huge = StockState.listed(definition);
        StockState atMax = StockState.listed(definition);
        StockState down = StockState.listed(definition);
        StockState tiny = StockState.listed(definition);
        StockState atMin = StockState.listed(definition);
        PriceModel.advance(definition, calm, SETTINGS, FIRST, STEP, 0.0D, 0L, 1.0D);
        PriceModel.advance(definition, up, SETTINGS, FIRST, STEP, 0.0D, 0L, 2.0D);
        PriceModel.advance(definition, huge, SETTINGS, FIRST, STEP, 0.0D, 0L, 1.0e9D);
        PriceModel.advance(definition, atMax, SETTINGS, FIRST, STEP, 0.0D, 0L, 4.0D);
        PriceModel.advance(definition, down, SETTINGS, FIRST, STEP, 0.0D, 0L, 0.5D);
        PriceModel.advance(definition, tiny, SETTINGS, FIRST, STEP, 0.0D, 0L, 1.0e-9D);
        PriceModel.advance(definition, atMin, SETTINGS, FIRST, STEP, 0.0D, 0L, 0.25D);
        // 1 % of ln 2 a step: about +0.7 % on 200 credits.
        assertEquals(20_000L, calm.price(), 5L);
        assertEquals(Math.round(calm.price() * Math.exp(0.01D * Math.log(2.0D))), up.price(), 1L);
        assertEquals(Math.round(calm.price() * Math.exp(0.01D * Math.log(0.5D))), down.price(), 1L);
        assertTrue(up.price() > calm.price() && down.price() < calm.price());
        // A shock beyond the clamp counts as the clamp: 4 and a quarter.
        assertEquals(atMax.price(), huge.price());
        assertEquals(atMin.price(), tiny.price());
        assertTrue(huge.price() > up.price() && tiny.price() < down.price());
        // A number that is not one is no shock at all.
        StockState broken = StockState.listed(definition);
        PriceModel.advance(definition, broken, SETTINGS, FIRST, STEP, 0.0D, 0L, Double.NaN);
        assertEquals(calm.price(), broken.price());

        // A narrower clamp is obeyed, and a gain of 0 keeps shocks out altogether.
        StockState narrow = StockState.listed(definition);
        StockState atNarrow = StockState.listed(definition);
        ExchangeSettings narrowed = SETTINGS.withShock(0.01D, 0.5D, 1.5D);
        PriceModel.advance(definition, narrow, narrowed, FIRST, STEP, 0.0D, 0L, 4.0D);
        PriceModel.advance(definition, atNarrow, narrowed, FIRST, STEP, 0.0D, 0L, 1.5D);
        assertEquals(atNarrow.price(), narrow.price());
        StockState deaf = StockState.listed(definition);
        PriceModel.advance(definition, deaf, SETTINGS.withShock(0.0D, 0.25D, 4.0D), FIRST, STEP, 0.0D, 0L, 4.0D);
        assertEquals(calm.price(), deaf.price());
    }

    @Test void aShockIsStillBoundByTheStepAndDayLimits() {
        StockDefinition definition = ExchangeTesting.steady("held");
        // The whole shock at once: far more than 2 % a step.
        ExchangeSettings violent = SETTINGS.withShock(1.0D, 0.25D, 4.0D);
        StockState state = StockState.listed(definition);
        PriceModel.advance(definition, state, violent, FIRST, STEP, 0.0D, 0L, 4.0D);
        assertEquals(10_200L, state.price());
        for (int index = 1; index < 20; index++)
            PriceModel.advance(definition, state, violent, FIRST + index, STEP, 0.0D, 0L, 4.0D);
        assertEquals(11_000L, state.price());
        assertTrue(state.haltedUntil() > FIRST);
    }

    // --- history ---------------------------------------------------------------------------------

    @Test void theHistoryRingsNeverGrowPastTheirSize() {
        StockDefinition definition = ExchangeTesting.steady("old");
        StockState state = StockState.listed(definition);
        int days = 45;
        for (int index = 0; index < days * ExchangeTesting.STEPS_PER_DAY; index++)
            advance(definition, state, FIRST + index, Math.sin(index * 0.37D));
        PriceHistory history = state.history();
        assertEquals(PriceHistory.RECENT, history.recentSize());
        assertEquals(PriceHistory.RECENT, history.recent().length);
        assertEquals(PriceHistory.DAILY, history.dailySize());
        assertEquals(PriceHistory.DAILY, history.daily().length);
        // Oldest first, and the newest recent point is the price now.
        assertEquals(state.price(), history.recent()[PriceHistory.RECENT - 1]);
        // What is handed out is a copy.
        history.recent()[0] = -1L;
        assertTrue(history.recent()[0] > 0L);
        assertEquals(144, PriceHistory.RECENT);
        assertEquals(30, PriceHistory.DAILY);
    }

    // --- numbers ---------------------------------------------------------------------------------

    @Test void pricesAreWrittenOutExactly() {
        assertEquals("123.45", PriceText.credits(12_345L));
        assertEquals("0.05", PriceText.credits(5L));
        assertEquals("7.00", PriceText.credits(700L));
        assertEquals("+1.50%", PriceText.percent(150));
        assertEquals("-0.25%", PriceText.percent(-25));
        assertEquals("0.00%", PriceText.percent(0));
        assertEquals("+30.00%", PriceText.percent(3_000));
        assertEquals(150, PriceModel.changeBps(10_000L, 10_150L));
        assertEquals(-1_000, PriceModel.changeBps(10_000L, 9_000L));
        assertEquals(0, PriceModel.changeBps(0L, 9_000L));
    }

    @Test void bondInterestIsExactRoundedDownAndNeverOverflows() {
        // 0.8 %, 3 % and 8 % for the term.
        assertEquals(80, SETTINGS.bondRateBps(BondTerm.ONE_DAY));
        assertEquals(300, SETTINGS.bondRateBps(BondTerm.THREE_DAYS));
        assertEquals(800, SETTINGS.bondRateBps(BondTerm.SEVEN_DAYS));
        assertEquals(8L, BondTerm.interest(1_000L, 80));
        assertEquals(30L, BondTerm.interest(1_000L, 300));
        assertEquals(80L, BondTerm.interest(1_000L, 800));
        // Rounded down: 0.8 % of 124 is 0.992.
        assertEquals(0L, BondTerm.interest(124L, 80));
        assertEquals(1L, BondTerm.interest(125L, 80));
        assertEquals(0L, BondTerm.interest(0L, 800));
        assertEquals(0L, BondTerm.interest(-5L, 800));
        assertEquals(0L, BondTerm.interest(1_000L, 0));
        assertEquals(Long.MAX_VALUE / 10_000L * 800L + Long.MAX_VALUE % 10_000L * 800L / 10_000L,
                BondTerm.interest(Long.MAX_VALUE, 800));
        assertEquals(Long.MAX_VALUE, BondTerm.interest(Long.MAX_VALUE, 20_000));
        assertEquals(1, BondTerm.ONE_DAY.days());
        assertEquals(3L * 86_400_000L, BondTerm.THREE_DAYS.millis());
        assertEquals(BondTerm.SEVEN_DAYS, BondTerm.parse("7d"));
        assertEquals("bond.neroeconomy.1d", BondTerm.ONE_DAY.translationKey());
    }
}
