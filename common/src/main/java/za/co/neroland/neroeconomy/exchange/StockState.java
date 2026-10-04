package za.co.neroland.neroeconomy.exchange;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What the exchange remembers about one company between steps. Everything the next price depends
 * on is here, in whole numbers, so a step is a pure function of (this, the step index, the
 * world's secret) and gives the same result on every machine.
 *
 * <ul>
 *   <li>{@code anchor} — where the price is pulled toward; grows by the company's drift. Kept in
 *       millionths of a credit, so a small drift per step is not lost to rounding.</li>
 *   <li>{@code price} — the price now, in hundredths of a credit: the number that is quoted,
 *       traded at and limited.</li>
 *   <li>{@code carry} — what rounding the price to a hundredth left over, in millionths of a
 *       credit (less than half a hundredth either way); the next step adds it back.</li>
 *   <li>{@code dayOpen}, {@code day} — the price at the start of the economy-day the stock is in,
 *       and which day that is; the daily limit is measured from it.</li>
 *   <li>{@code haltedUntil} — the last step of a halt: the price does not move on any step up to
 *       and including it.</li>
 *   <li>{@code volumeBaseline} — the usual traded volume of its linked commodities, in hundredths
 *       of a credit per step; the volume nudge compares each step's volume with it.</li>
 *   <li>{@code delisted} — its data file is gone. The state stays, frozen at its last price, until
 *       whoever keeps the holdings has paid the holders out and calls
 *       {@link ExchangeSection#removeDelisted}.</li>
 *   <li>{@code nameKey}, {@code startCents} — copied from the definition when it lists, so a
 *       delisted company can still be named and its holders told what it listed at.</li>
 *   <li>{@code splits}, {@code lastSplitDay} — how many times the share has split 10-for-1 and the
 *       economy-day of the last time; see {@link #split}.</li>
 * </ul>
 *
 * <p>Nothing here is about a player.
 */
public final class StockState {
    /** The {@code day} of a stock that has not been through a step yet. */
    public static final long NO_DAY = Long.MIN_VALUE;
    /** The {@code haltedUntil} and {@code lastStep} of a stock that never was halted or stepped. */
    public static final long NEVER = -1L;

    static final Codec<StockState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(state -> state.id),
            Codec.LONG.fieldOf("price").forGetter(state -> state.price),
            // Everything else is optional with a safe default, so a state written by an older or
            // a trimmed build still loads.
            Codec.LONG.optionalFieldOf("anchor", 0L).forGetter(state -> state.anchorMicros),
            Codec.LONG.optionalFieldOf("day_open", 0L).forGetter(state -> state.dayOpen),
            Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter(state -> state.day),
            Codec.LONG.optionalFieldOf("halted_until", NEVER).forGetter(state -> state.haltedUntil),
            Codec.LONG.optionalFieldOf("last_step", NEVER).forGetter(state -> state.lastStep),
            Codec.LONG.optionalFieldOf("volume_baseline", 0L).forGetter(state -> state.volumeBaseline),
            Codec.BOOL.optionalFieldOf("delisted", false).forGetter(state -> state.delisted),
            Codec.STRING.optionalFieldOf("name", "").forGetter(state -> state.nameKey),
            Codec.LONG.optionalFieldOf("start", 0L).forGetter(state -> state.startCents),
            Codec.LONG.optionalFieldOf("carry", 0L).forGetter(state -> state.carryMicros),
            Codec.LONG.listOf().optionalFieldOf("recent", List.of()).forGetter(state -> state.history.recentList()),
            Codec.LONG.listOf().optionalFieldOf("daily", List.of()).forGetter(state -> state.history.dailyList()),
            // Added with share splits, so optional: a state saved before them has never split.
            SplitRow.CODEC.optionalFieldOf("split", SplitRow.NONE).forGetter(state -> new SplitRow(state.splits,
                    state.lastSplitDay)))
            .apply(instance, StockState::fromData));

    /** A price at or above this many hundredths — 2000 credits — splits at the next day roll. */
    public static final long SPLIT_AT_CENTS = 2_000L * PriceModel.CENTS_PER_CREDIT;
    /** One share becomes this many. */
    public static final int SPLIT_FACTOR = 10;
    /** The most splits that are remembered: the divisor of the floor must stay a whole number. */
    static final int MAX_SPLITS = 15;

    /** How often a share has split, and when it last did. */
    record SplitRow(int count, long day) {
        static final SplitRow NONE = new SplitRow(0, NO_DAY);
        static final Codec<SplitRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("count", 0).forGetter(SplitRow::count),
                Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter(SplitRow::day)).apply(instance, SplitRow::new));
    }

    final String id;
    long anchorMicros;
    long price;
    long dayOpen;
    long day = NO_DAY;
    long haltedUntil = NEVER;
    long lastStep = NEVER;
    long volumeBaseline;
    boolean delisted;
    String nameKey = "";
    long startCents;
    long carryMicros;
    int splits;
    long lastSplitDay = NO_DAY;
    final PriceHistory history;

    private StockState(String id, PriceHistory history) {
        this.id = id;
        this.history = history;
    }

    /** A company on its first day: price, day-open and anchor all at the start price. */
    static StockState listed(StockDefinition definition) {
        StockState state = new StockState(definition.id(), new PriceHistory());
        state.price = definition.startCents();
        state.dayOpen = state.price;
        state.anchorMicros = state.price * PriceModel.MICROS_PER_CENT;
        state.nameKey = definition.nameKey();
        state.startCents = state.price;
        return state;
    }

    private static StockState fromData(String id, long price, long anchor, long dayOpen, long day, long haltedUntil,
            long lastStep, long volumeBaseline, boolean delisted, String nameKey, long startCents, long carry,
            List<Long> recent, List<Long> daily, SplitRow split) {
        StockState state = new StockState(id, new PriceHistory(recent, daily));
        // A stored number that cannot be a price is brought back into range rather than trusted.
        state.price = clampPrice(price);
        state.dayOpen = dayOpen > 0L ? clampPrice(dayOpen) : state.price;
        state.anchorMicros = anchor > 0L ? Math.min(anchor, PriceModel.MAX_ANCHOR_MICROS)
                : state.price * PriceModel.MICROS_PER_CENT;
        state.day = day;
        state.haltedUntil = Math.max(NEVER, haltedUntil);
        state.lastStep = Math.max(NEVER, lastStep);
        state.volumeBaseline = Math.max(0L, Math.min(volumeBaseline, PriceModel.MAX_VOLUME_CENTS));
        state.delisted = delisted;
        state.nameKey = nameKey == null ? "" : nameKey;
        state.startCents = startCents > 0L ? clampPrice(startCents) : state.price;
        // The rounding carry is always less than half a hundredth; anything else is not one.
        state.carryMicros = Math.abs(carry) <= PriceModel.MICROS_PER_CENT / 2L ? carry : 0L;
        state.splits = split == null ? 0 : Math.max(0, Math.min(MAX_SPLITS, split.count()));
        state.lastSplitDay = split == null || state.splits == 0 ? NO_DAY : split.day();
        return state;
    }

    private static long clampPrice(long cents) {
        return Math.max(PriceModel.MIN_PRICE_CENTS, Math.min(PriceModel.MAX_PRICE_CENTS, cents));
    }

    // --- reads -------------------------------------------------------------------------------

    public String id() { return id; }
    /** The price now, in hundredths of a credit. */
    public long price() { return price; }
    /** The anchor, in millionths of a credit. */
    public long anchorMicros() { return anchorMicros; }
    /** The price at the start of the stock's economy-day, in hundredths of a credit. */
    public long dayOpen() { return dayOpen; }
    /** The economy-day the stock is in, or {@link #NO_DAY} before its first step. */
    public long day() { return day; }
    /** The last step of the stock's halt, or {@link #NEVER}. */
    public long haltedUntil() { return haltedUntil; }
    /** The last step that updated this stock, or {@link #NEVER}. */
    public long lastStep() { return lastStep; }
    public long volumeBaseline() { return volumeBaseline; }
    public boolean delisted() { return delisted; }
    public String nameKey() { return nameKey; }
    /** The price it listed at, in hundredths of a credit. */
    public long startCents() { return startCents; }
    public PriceHistory history() { return history; }

    /**
     * Whether trading in the stock is halted once step {@code lastRun} has run: a halt is still in
     * force. A buy or a sell must be refused while this is true.
     */
    public boolean halted(long lastRun) { return lastRun < haltedUntil; }

    /** How far the price is from the day-open price, in hundredths of a percent; negative when it is below. */
    public int dayChangeBps() { return PriceModel.changeBps(dayOpen, price); }

    /** How many times the share has split 10-for-1. */
    public int splits() { return splits; }

    /** The economy-day of the last split, or {@link #NO_DAY}. */
    public long lastSplitDay() { return lastSplitDay; }

    /** Whether the share is due to split at the next day roll: its price is at or above {@link #SPLIT_AT_CENTS}. */
    boolean splitDue() { return !delisted && price >= SPLIT_AT_CENTS && splits < MAX_SPLITS; }

    /**
     * What a definition's start price is worth in today's shares: divided by ten for every split,
     * so the floor a price cannot fall below shrinks with the share and never ends up above it.
     */
    long splitAdjusted(long startCentsOfDefinition) {
        long adjusted = startCentsOfDefinition;
        for (int count = 0; count < splits; count++) adjusted /= SPLIT_FACTOR;
        return Math.max(PriceModel.MIN_PRICE_CENTS, adjusted);
    }

    /**
     * A 10-for-1 share split, at the roll into economy-day {@code day}: the price, the anchor, the
     * day-open price, the listing price and the stored history are divided by ten. Whoever keeps
     * the holdings multiplies every holding's units by ten in the same operation, so what a
     * holding is worth does not change (to within half a hundredth of a credit per new share,
     * which is what rounding the new price to a hundredth can come to).
     */
    void split(long day) {
        long micros = (price * PriceModel.MICROS_PER_CENT + carryMicros) / SPLIT_FACTOR;
        price = Math.max(PriceModel.MIN_PRICE_CENTS,
                Math.floorDiv(micros + PriceModel.MICROS_PER_CENT / 2L, PriceModel.MICROS_PER_CENT));
        long carry = micros - price * PriceModel.MICROS_PER_CENT;
        carryMicros = Math.abs(carry) <= PriceModel.MICROS_PER_CENT / 2L ? carry : 0L;
        anchorMicros = Math.max(1L, anchorMicros / SPLIT_FACTOR);
        dayOpen = Math.max(PriceModel.MIN_PRICE_CENTS, dayOpen / SPLIT_FACTOR);
        startCents = Math.max(PriceModel.MIN_PRICE_CENTS, startCents / SPLIT_FACTOR);
        history.divide(SPLIT_FACTOR);
        splits++;
        lastSplitDay = day;
    }
}
