package za.co.neroland.neroeconomy.exchange;

import za.co.neroland.neroeconomy.simulation.SimClock;
import za.co.neroland.neroeconomy.simulation.SimRandom;

/**
 * The exchange's price model: how one company's price moves in one simulation step. Pure — no
 * game, no store, no clock, no configuration — so it runs in plain JUnit and is the one place the
 * formulas live. The default numbers are the {@code DEFAULT_…} constants of
 * {@link ExchangeSettings}; the per-company numbers come from the data files
 * ({@link StockDefinition}).
 *
 * <h2>The formulas, as published</h2>
 *
 * A step is {@code stepMillis} of economy time (10 minutes by default: 144 steps a day, 1008 a
 * week). For one company, with {@code price} and {@code anchor} from the step before:
 *
 * <pre>
 *   μ      = ln(1 + drift / 100) × stepMillis / one week          anchor growth per step
 *   σ      = volatility / 100 × sqrt(stepMillis / one day)        random move per step (std dev)
 *
 *   anchor' = anchor × e^μ
 *   x       = ln(price / anchor)                                  how far the price is from its anchor
 *   x'      = x × (1 − reversion)                                 the gap closes a little   (0.05 % a step)
 *           + σ × z                                               z: standard normal, from the keyed hash
 *           + ln(1 + nudgeCap × v)                                v in [−1, 1]: traded volume (cap 0.5 %)
 *           + shockGain × ln(shock)                               an active market shock    (1 % of it a step)
 *   price'  = anchor' × e^(x')                                    then the limits below
 * </pre>
 *
 * <ul>
 *   <li><b>z</b> is drawn from {@code SimContext.random("price:" + stock id)}: HMAC-SHA256 of the
 *       world's secret, the handler name, the step index and that label. Nothing else is random,
 *       and nothing about a later step is computed or stored.</li>
 *   <li><b>Volume</b>: {@code v = clamp((volume − baseline) / volumeScale, −1, 1)}, where
 *       {@code volume} is the credits of counted trade in the company's linked commodities since
 *       the step before and {@code baseline} is its usual level, which moves an eighth of the way
 *       toward each step's volume. More trade than usual lifts the price, less lowers it, and
 *       steady trade does nothing. Fills between the same two player accounts count up to
 *       {@code volumePairCap} (250) credits per step and one account's fills up to
 *       {@code volumeAccountCap} (1000).</li>
 *   <li><b>Shock</b>: the product of the active {@code MarketShocks} multipliers for the stock id
 *       and its linked ids, clamped to {@code minPriceMultiplier}…{@code maxPriceMultiplier}
 *       (0.25…4); 1 when there is none.</li>
 * </ul>
 *
 * <h2>The limits, applied in this order</h2>
 * <ol>
 *   <li><b>Per step</b>: at most 2 % (steady) or 6 % (risky) from the price before, and never
 *       less than 0.01 credit of room.</li>
 *   <li><b>Per day</b>: at most 10 % (steady) or 40 % (risky) from the price at the start of the
 *       economy-day.</li>
 *   <li><b>Floor</b>: never below 5 % of the start price, and never below 0.01 credit. The floor
 *       wins over the other limits. (After a share split the start price counts in today's
 *       shares: a tenth of what it was.)</li>
 *   <li><b>Circuit breaker</b>: a price that reaches its daily limit is halted for 6 steps: it
 *       does not move, and it is flagged halted so trades are refused. The anchor keeps growing
 *       through a halt.</li>
 * </ol>
 *
 * <h2>Numbers</h2>
 * Prices are whole hundredths of a credit and anchors whole millionths; every limit is applied to
 * the hundredths, exactly. What rounding a price to a hundredth leaves over (less than half a
 * hundredth either way) is kept as a whole number of millionths and added back in the next step,
 * so a move too small to show is not lost and cheap shares follow their anchor as well as dear
 * ones. The floating-point steps use only {@link StrictMath} and IEEE arithmetic, which give
 * bit-identical results on every Java runtime, and each result is rounded to a whole number once
 * ({@link Math#round}, half up). A player's exchange orders are not an input: nothing a player
 * buys or sells there moves a price.
 *
 * <h2>What to expect</h2>
 * With the shipped companies, per economy-week and before the spread and the fees:
 * <ul>
 *   <li><b>steady</b> shares are expected to return 6 to 10 %, dividends included, and to lose in
 *       one week out of six or seven;</li>
 *   <li><b>risky</b> shares are expected to return 25 to 35 %, and to lose in one week out of three;</li>
 *   <li>an equal mix of all twelve, and the index fund, 15 to 30 %.</li>
 * </ul>
 * The 7-day Savings Bond pays 8 % with no fee and no risk, so it is a real alternative to steady
 * shares. {@code ExchangeCalibrationTest} checks all of this over many secrets.
 */
public final class PriceModel {
    public static final long CENTS_PER_CREDIT = 100L;
    /** Millionths of a credit in a hundredth. */
    public static final long MICROS_PER_CENT = 10_000L;
    public static final long DAY_MILLIS = 86_400_000L;
    public static final long WEEK_MILLIS = 7L * DAY_MILLIS;
    /** The lowest price there is: 0.01 credit. */
    public static final long MIN_PRICE_CENTS = 1L;
    /** The highest price there is: a thousand million credits. A price that gets here stops growing. */
    public static final long MAX_PRICE_CENTS = 100_000_000_000L;
    public static final long MAX_ANCHOR_MICROS = MAX_PRICE_CENTS * MICROS_PER_CENT;
    /** The most volume one step counts, in hundredths of a credit; far above anything a cap lets through. */
    public static final long MAX_VOLUME_CENTS = 100_000_000_000_000L;
    /** The baseline moves this fraction of the way toward each step's volume: one eighth. */
    private static final long BASELINE_DIVISOR = 8L;
    private static final long BPS = 10_000L;

    private PriceModel() { }

    /**
     * A standard normal number from {@code random} (Box–Muller). {@link SimRandom#nextGaussian()}
     * is not used: it goes through {@code Math.log} and {@code Math.cos}, which a runtime may
     * compute differently from another; {@link StrictMath} may not.
     */
    public static double gaussian(SimRandom random) {
        double u = 1.0D - random.nextDouble();
        double v = random.nextDouble();
        return StrictMath.sqrt(-2.0D * StrictMath.log(u)) * StrictMath.cos(2.0D * StrictMath.PI * v);
    }

    /** The economy-day a step belongs to. */
    public static long dayOf(long step, long stepMillis) {
        return Math.floorDiv(SimClock.stepTime(step, stepMillis), DAY_MILLIS);
    }

    /** Anchor growth per step, as a logarithm: {@code μ}. */
    public static double driftPerStep(double driftPercentPerWeek, long stepMillis) {
        return StrictMath.log1p(driftPercentPerWeek / 100.0D) * ((double) stepMillis / WEEK_MILLIS);
    }

    /** Standard deviation of the random move of one step: {@code σ}. */
    public static double volatilityPerStep(double volatilityPercentPerDay, long stepMillis) {
        return volatilityPercentPerDay / 100.0D * StrictMath.sqrt((double) stepMillis / DAY_MILLIS);
    }

    /** The floor of a company's price, in hundredths of a credit. */
    public static long floorCents(long startCents, int floorBps) {
        long share = (Math.max(0L, startCents) * floorBps + BPS - 1L) / BPS;
        return Math.max(MIN_PRICE_CENTS, Math.min(MAX_PRICE_CENTS, share));
    }

    /** How far {@code now} is from {@code from}, in hundredths of a percent, rounded toward zero. */
    public static int changeBps(long from, long now) {
        if (from <= 0L) return 0;
        long bps = (now - from) * BPS / from;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, bps));
    }

    /**
     * Moves one company through one step.
     *
     * @param z      a standard normal number for this company and step; see {@link #gaussian}
     * @param volume credits of counted trade in its linked commodities since the step before
     * @param shock  the active market-shock multiplier; 1 for none
     */
    public static void advance(StockDefinition definition, StockState state, ExchangeSettings settings, long step,
            long stepMillis, double z, long volume, double shock) {
        rollDay(state, dayOf(step, stepMillis));
        long anchorBefore = state.anchorMicros;
        state.anchorMicros = grow(anchorBefore, driftPerStep(definition.drift(), stepMillis));
        double v = volumeSignal(state, volume, settings);
        if (step <= state.haltedUntil) {
            // Halted: the price stands still. The anchor and the volume baseline moved on above.
            state.history.pushRecent(state.price);
            state.lastStep = step;
            return;
        }
        // The price in millionths: the stored hundredths plus what the last rounding left over.
        long priceMicros = state.price * MICROS_PER_CENT + state.carryMicros;
        double x = StrictMath.log((double) priceMicros / anchorBefore);
        double clampedShock = Double.isFinite(shock) ? Math.max(settings.minShock(), Math.min(settings.maxShock(), shock))
                : 1.0D;
        double next = x * (1.0D - settings.reversion())
                + volatilityPerStep(definition.volatility(), stepMillis) * (Double.isFinite(z) ? z : 0.0D)
                + StrictMath.log1p(settings.volumeNudgeCap() * v)
                + settings.shockGain() * StrictMath.log(clampedShock);
        double raw = state.anchorMicros * StrictMath.exp(next);
        // Rounded to a whole millionth once, then to the hundredth that is stored; a number that
        // is not one leaves the price where it is.
        long targetMicros = Double.isFinite(raw)
                ? Math.max(MIN_PRICE_CENTS * MICROS_PER_CENT, Math.min(MAX_PRICE_CENTS * MICROS_PER_CENT, Math.round(raw)))
                : priceMicros;
        long target = Math.floorDiv(targetMicros + MICROS_PER_CENT / 2L, MICROS_PER_CENT);

        long stepBand = Math.max(1L, state.price * settings.stepLimitBps(definition.type()) / BPS);
        long dayBand = Math.max(1L, state.dayOpen * settings.dayLimitBps(definition.type()) / BPS);
        long stepLow = state.price - stepBand;
        long stepHigh = state.price + stepBand;
        long dayLow = state.dayOpen - dayBand;
        long dayHigh = state.dayOpen + dayBand;
        long low = Math.max(stepLow, dayLow);
        long high = Math.min(stepHigh, dayHigh);
        long limited;
        boolean atDayLimit;
        if (low > high) {
            // The price is outside today's band, which only a changed limit can cause: it takes
            // one step back toward the band and is not halted for it.
            limited = state.price > dayHigh ? stepLow : stepHigh;
            atDayLimit = false;
        } else {
            limited = Math.max(low, Math.min(high, target));
            atDayLimit = limited >= dayHigh || limited <= dayLow;
        }
        // The start price in today's shares: a share that has split has a floor a tenth as high.
        long floor = floorCents(state.splitAdjusted(definition.startCents()), settings.floorBps());
        state.price = Math.max(floor, Math.min(MAX_PRICE_CENTS, limited));
        // What rounding to a hundredth left over is carried into the next step, so that a move too
        // small to show is not lost; a price held by a limit or the floor carries nothing.
        state.carryMicros = state.price == target ? targetMicros - target * MICROS_PER_CENT : 0L;
        // Held up by the floor, the price has not reached the limit below it.
        if (atDayLimit && state.price == limited && settings.haltSteps() > 0)
            state.haltedUntil = step + settings.haltSteps();
        state.history.pushRecent(state.price);
        state.lastStep = step;
    }

    /** A new economy-day: yesterday's last price is its close and today's open. */
    static void rollDay(StockState state, long day) {
        if (day == state.day) return;
        if (state.day != StockState.NO_DAY) state.history.pushDaily(state.price);
        state.dayOpen = state.price;
        state.day = day;
    }

    private static long grow(long anchorMicros, double mu) {
        double grown = anchorMicros * StrictMath.exp(mu);
        if (!Double.isFinite(grown)) return anchorMicros;
        return Math.max(1L, Math.min(MAX_ANCHOR_MICROS, Math.round(grown)));
    }

    /**
     * The volume signal {@code v} in [−1, 1] for this step, and the baseline moved an eighth of
     * the way toward this step's volume (by at least one hundredth when they differ, so it always
     * arrives).
     */
    private static double volumeSignal(StockState state, long volume, ExchangeSettings settings) {
        long counted = Math.max(0L, Math.min(volume, MAX_VOLUME_CENTS / CENTS_PER_CREDIT)) * CENTS_PER_CREDIT;
        long difference = counted - state.volumeBaseline;
        long move = difference / BASELINE_DIVISOR;
        if (move == 0L && difference != 0L) move = difference > 0L ? 1L : -1L;
        state.volumeBaseline += move;
        if (settings.volumeScale() <= 0L || difference == 0L) return 0.0D;
        double scale = (double) settings.volumeScale() * CENTS_PER_CREDIT;
        return Math.max(-1.0D, Math.min(1.0D, difference / scale));
    }
}
