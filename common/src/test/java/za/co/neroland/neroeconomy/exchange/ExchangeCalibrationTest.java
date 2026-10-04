package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The published expectation, checked the only way it can be: by running the shipped companies
 * through one economy-week (1008 steps) under many different secrets, with the default settings,
 * no traded volume and no shocks. Returns are before the spread and the fees.
 *
 * <ul>
 *   <li><b>Steady</b> shares: +6 % to +10 % a week with their dividends, and a losing week 10 to
 *       20 times in a hundred.</li>
 *   <li><b>Risky</b> shares: +25 % to +35 % a week, and a losing week 30 to 40 times in a hundred —
 *       a market in which nothing can fall is a faucet.</li>
 *   <li>An equal mix of all twelve, and the index fund: +15 % to +30 %.</li>
 *   <li>The 7-day Savings Bond (8 %, no fee, no risk) is a real alternative to steady shares.</li>
 * </ul>
 *
 * It also checks the "what could happen in a week" helper against the same runs: about one week
 * in ten ends below its bad outcome, about one in ten above its good one, and about half below its
 * typical one.
 *
 * <p>The secrets are fixed, so the test is deterministic: it gives the same numbers on every machine.
 */
class ExchangeCalibrationTest {
    private static final int SECRETS = 240;
    private static final int WEEK = ExchangeTesting.STEPS_PER_WEEK;
    private static final int DAY = ExchangeTesting.STEPS_PER_DAY;

    /** The mean and the standard deviation of some returns. */
    private record Spread(double mean, double deviation) {
        static Spread of(double[] values, int count) {
            double sum = 0.0D;
            for (int index = 0; index < count; index++) sum += values[index];
            double mean = sum / count;
            double squares = 0.0D;
            for (int index = 0; index < count; index++) squares += (values[index] - mean) * (values[index] - mean);
            return new Spread(mean, Math.sqrt(squares / count));
        }
    }

    /** A price in the shares the company listed with: a share that split 10-for-1 counts ten times. */
    private static double unsplit(StockState state) { return state.price() * Math.pow(10.0D, state.splits()); }

    @Test void aWeekOfTheShippedCompaniesMatchesWhatIsPublished() {
        StockRegistry registry = ExchangeTesting.shipped();
        List<StockDefinition> stocks = registry.stocks();
        assertEquals(12, stocks.size());
        ExchangeSettings settings = ExchangeSettings.DEFAULTS;
        TradingTerms terms = TradingTerms.DEFAULTS;

        double[] basket = new double[SECRETS];
        double[] index = new double[SECRETS];
        double[] steady = new double[SECRETS * stocks.size()];
        double[] steadyPrice = new double[SECRETS * stocks.size()];
        double[] risky = new double[SECRETS * stocks.size()];
        int steadyWeeks = 0;
        int riskyWeeks = 0;
        int steadyLosses = 0;
        int riskyLosses = 0;
        int riskyBigWins = 0;
        int halts = 0;
        // How the weeks fell against the "what could happen in a week" helper, per kind of share.
        int[][] against = new int[2][3];

        for (int seed = 0; seed < SECRETS; seed++) {
            ExchangeSection section = new ExchangeSection();
            byte[] secret = ExchangeTesting.secret(1_000L + seed);
            long first = ExchangeTesting.DAY_START;
            // What one share bought at the first step earned in dividends: one a day, at the day's first price.
            double[] dividends = new double[stocks.size()];
            // One step past the week: the step that begins the eighth day pays the seventh dividend.
            for (long step = first; step <= first + WEEK; step++) {
                section.step(registry, settings, step, ExchangeTesting.STEP, ExchangeTesting.inputs(secret, step));
                boolean dayRoll = step > first && (step - first) % DAY == 0L;
                for (int stock = 0; stock < stocks.size(); stock++) {
                    StockDefinition definition = stocks.get(stock);
                    StockState state = section.state(definition.id());
                    if (state.haltedUntil() == step + settings.haltSteps()) halts++;
                    if (dayRoll) dividends[stock] += unsplit(state) * definition.dividendYield() / 100.0D / 7.0D;
                }
            }
            double mix = 0.0D;
            for (int stock = 0; stock < stocks.size(); stock++) {
                StockDefinition definition = stocks.get(stock);
                StockState state = section.state(definition.id());
                // The floor held, whatever happened.
                assertTrue(state.price() >= PriceModel.floorCents(state.splitAdjusted(definition.startCents()),
                        settings.floorBps()));
                double gain = unsplit(state) / definition.startCents();
                double total = gain + dividends[stock] / definition.startCents();
                mix += gain / stocks.size();
                WeekOutlook.Outcome outlook = WeekOutlook.stock(definition, settings, terms, ExchangeTesting.STEP, 1_000L);
                int kind = definition.steady() ? 0 : 1;
                if (gain < 1.0D + outlook.grossBadBasisPoints() / 10_000.0D) against[kind][0]++;
                if (gain < 1.0D + outlook.grossTypicalBasisPoints() / 10_000.0D) against[kind][1]++;
                if (gain > 1.0D + outlook.grossGoodBasisPoints() / 10_000.0D) against[kind][2]++;
                if (definition.steady()) {
                    steadyPrice[steadyWeeks] = gain;
                    steady[steadyWeeks++] = total;
                    if (total < 1.0D) steadyLosses++;
                } else {
                    risky[riskyWeeks++] = total;
                    if (total < 1.0D) riskyLosses++;
                    if (gain > 1.5D) riskyBigWins++;
                }
            }
            basket[seed] = mix;
            index[seed] = section.indexQuote().priceCents() * Math.pow(10.0D, section.index().splits())
                    / IndexState.START_CENTS;
        }

        Spread mixed = Spread.of(basket, SECRETS);
        Spread fund = Spread.of(index, SECRETS);
        Spread calm = Spread.of(steady, steadyWeeks);
        Spread calmPrice = Spread.of(steadyPrice, steadyWeeks);
        Spread wild = Spread.of(risky, riskyWeeks);
        double riskyLossShare = (double) riskyLosses / riskyWeeks;
        double steadyLossShare = (double) steadyLosses / steadyWeeks;
        String report = String.format("basket %+.1f%% (sd %.1f%%), index %+.1f%%, steady %+.1f%% with dividends "
                + "(%+.1f%% without, sd %.1f%%, losing weeks %.1f%%), risky %+.1f%% (sd %.1f%%, losing weeks %.1f%%, "
                + "weeks above +50%%: %d), halts per week %.2f; weeks below bad / below typical / above good: steady "
                + "%.1f%% / %.1f%% / %.1f%%, risky %.1f%% / %.1f%% / %.1f%%", (mixed.mean() - 1.0D) * 100.0D,
                mixed.deviation() * 100.0D, (fund.mean() - 1.0D) * 100.0D, (calm.mean() - 1.0D) * 100.0D,
                (calmPrice.mean() - 1.0D) * 100.0D, calm.deviation() * 100.0D, steadyLossShare * 100.0D,
                (wild.mean() - 1.0D) * 100.0D, wild.deviation() * 100.0D, riskyLossShare * 100.0D, riskyBigWins,
                (double) halts / SECRETS, 100.0D * against[0][0] / steadyWeeks, 100.0D * against[0][1] / steadyWeeks,
                100.0D * against[0][2] / steadyWeeks, 100.0D * against[1][0] / riskyWeeks,
                100.0D * against[1][1] / riskyWeeks, 100.0D * against[1][2] / riskyWeeks);
        System.out.println("[exchange calibration] " + report);
        assertEquals(SECRETS * 6, steadyWeeks, report);
        assertEquals(SECRETS * 6, riskyWeeks, report);

        // Steady shares: +6 % to +10 % a week with their dividends, losing 10 to 20 weeks in a hundred.
        assertTrue(calm.mean() >= 1.06D && calm.mean() <= 1.10D, report);
        assertTrue(steadyLossShare >= 0.10D && steadyLossShare <= 0.20D, report);
        assertTrue(calm.deviation() < 0.12D, report);
        // Risky shares: +25 % to +35 %, losing 30 to 40 weeks in a hundred, with large swings either way.
        assertTrue(wild.mean() >= 1.25D && wild.mean() <= 1.35D, report);
        assertTrue(riskyLossShare >= 0.30D && riskyLossShare <= 0.40D, report);
        assertTrue(wild.deviation() > 0.30D, report);
        assertTrue(riskyBigWins > 0, report);
        assertTrue(calm.mean() < wild.mean() && calm.deviation() < wild.deviation(), report);
        // The equal mix of all twelve, and the index fund: +15 % to +30 % a week.
        assertTrue(mixed.mean() >= 1.15D && mixed.mean() <= 1.30D, report);
        assertTrue(fund.mean() >= 1.15D && fund.mean() <= 1.30D, report);
        // The breaker is a real thing, not a dead rule.
        assertTrue(halts > 0, report);

        // The 7-day Savings Bond is a real alternative to steady shares: its 8 % sits inside what they
        // are expected to return, and once the spread and the two fees are counted it is the better
        // typical week — for no risk and a week's wait.
        double bond = settings.bondRateBps(BondTerm.SEVEN_DAYS) / 10_000.0D;
        assertTrue(bond >= 0.06D && bond <= 0.10D, report);
        assertTrue(Math.abs(calm.mean() - 1.0D - bond) < 0.02D, report);
        long bondBack = WeekOutlook.bond(BondTerm.SEVEN_DAYS, terms, 1_000L).typical();
        for (StockDefinition definition : stocks) {
            if (!definition.steady()) continue;
            WeekOutlook.Outcome outlook = WeekOutlook.stock(definition, settings, terms, ExchangeTesting.STEP, 1_000L);
            assertTrue(outlook.typical() < bondBack, definition.id());
            assertTrue(outlook.good() > bondBack, definition.id());
        }

        // The helper agrees with what the model does: about a tenth of the weeks below its bad
        // outcome, about a tenth above its good one, about half below its typical one.
        for (int kind = 0; kind < 2; kind++) {
            double weeks = kind == 0 ? steadyWeeks : riskyWeeks;
            assertTrue(against[kind][0] / weeks > 0.05D && against[kind][0] / weeks < 0.16D, report);
            assertTrue(against[kind][1] / weeks > 0.40D && against[kind][1] / weeks < 0.60D, report);
            assertTrue(against[kind][2] / weeks > 0.05D && against[kind][2] / weeks < 0.16D, report);
        }
    }
}
