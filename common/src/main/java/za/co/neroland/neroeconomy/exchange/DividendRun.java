package za.co.neroland.neroeconomy.exchange;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What the last dividend day came to, as aggregates: nothing in it is about an account.
 *
 * @param day         the economy-day whose start the dividends were paid at; {@link StockState#NO_DAY}
 *                    when none has been paid yet
 * @param requested   what every holder together was due
 * @param paid        what was paid
 * @param numerator   with {@code denominator}, the factor every amount was scaled by: 1/1 when the
 *                    Reserve and the day's budget covered it all
 * @param accounts    how many accounts were paid something
 */
public record DividendRun(long day, long requested, long paid, long numerator, long denominator, int accounts) {
    static final Codec<DividendRun> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("day", StockState.NO_DAY).forGetter(DividendRun::day),
            Codec.LONG.optionalFieldOf("requested", 0L).forGetter(DividendRun::requested),
            Codec.LONG.optionalFieldOf("paid", 0L).forGetter(DividendRun::paid),
            Codec.LONG.optionalFieldOf("numerator", 1L).forGetter(DividendRun::numerator),
            Codec.LONG.optionalFieldOf("denominator", 1L).forGetter(DividendRun::denominator),
            Codec.INT.optionalFieldOf("accounts", 0).forGetter(DividendRun::accounts))
            .apply(instance, DividendRun::new));

    /** No dividend day has been paid yet. */
    public static final DividendRun NONE = new DividendRun(StockState.NO_DAY, 0L, 0L, 1L, 1L, 0);

    public DividendRun {
        if (denominator < 1L) {
            numerator = 1L;
            denominator = 1L;
        }
        numerator = Math.max(0L, Math.min(numerator, denominator));
        requested = Math.max(0L, requested);
        paid = Math.max(0L, paid);
        accounts = Math.max(0, accounts);
    }

    public boolean happened() { return day != StockState.NO_DAY; }

    /** Whether the Reserve was short, so everyone was paid the same smaller share. */
    public boolean scaled() { return numerator < denominator; }

    /** The share that was paid, in whole percent, rounded down: 62 for "paid at 62 %". */
    public int percent() { return (int) ExchangeMath.mulDiv(numerator, 100L, denominator, false); }
}
