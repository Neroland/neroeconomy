package za.co.neroland.neroeconomy.exchange;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The index fund's price. The fund is a basket of every listed company, equal-weighted, and its
 * price is <em>derived</em>: each step it moves by the average of its members' moves in that step,
 *
 * <pre>   level' = level × mean(price'ᵢ / priceᵢ)   over every listed company i</pre>
 *
 * so it has no randomness of its own, and a company joining or leaving the exchange never makes it
 * jump. It starts at {@link #START_CENTS} (100 credits) and is not halted: a halted member simply
 * contributes no move.
 */
public final class IndexState {
    /** The id the index fund is traded and queried under. */
    public static final String ID = "neroeconomy:index";
    public static final String NAME_KEY = "stock.neroeconomy.index";
    public static final String DESCRIPTION_KEY = "stock.neroeconomy.index.description";
    /** The level the index starts at: 100 credits. */
    public static final long START_CENTS = 100L * PriceModel.CENTS_PER_CREDIT;

    static final Codec<IndexState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("price", START_CENTS).forGetter(state -> state.price),
            Codec.LONG.optionalFieldOf("day_open", 0L).forGetter(state -> state.dayOpen),
            Codec.LONG.optionalFieldOf("day", StockState.NO_DAY).forGetter(state -> state.day),
            Codec.LONG.listOf().optionalFieldOf("recent", List.of()).forGetter(state -> state.history.recentList()),
            Codec.LONG.listOf().optionalFieldOf("daily", List.of()).forGetter(state -> state.history.dailyList()),
            // Added with splits, so optional: an index saved before them has never split.
            StockState.SplitRow.CODEC.optionalFieldOf("split", StockState.SplitRow.NONE)
                    .forGetter(state -> new StockState.SplitRow(state.splits, state.lastSplitDay)))
            .apply(instance, IndexState::fromData));
    /** A level at or above this many hundredths — 20000 credits — splits 10-for-1 at the next day roll. */
    public static final long SPLIT_AT_CENTS = 20_000L * PriceModel.CENTS_PER_CREDIT;

    long price = START_CENTS;
    long dayOpen = START_CENTS;
    long day = StockState.NO_DAY;
    int splits;
    long lastSplitDay = StockState.NO_DAY;
    final PriceHistory history;

    IndexState() { history = new PriceHistory(); }

    private IndexState(PriceHistory history) { this.history = history; }

    private static IndexState fromData(long price, long dayOpen, long day, List<Long> recent, List<Long> daily,
            StockState.SplitRow split) {
        IndexState state = new IndexState(new PriceHistory(recent, daily));
        state.splits = split == null ? 0 : Math.max(0, Math.min(StockState.MAX_SPLITS, split.count()));
        state.lastSplitDay = split == null || state.splits == 0 ? StockState.NO_DAY : split.day();
        state.price = Math.max(PriceModel.MIN_PRICE_CENTS, Math.min(PriceModel.MAX_PRICE_CENTS, price));
        state.dayOpen = dayOpen > 0L ? Math.min(PriceModel.MAX_PRICE_CENTS, dayOpen) : state.price;
        state.day = day;
        return state;
    }

    /**
     * One step: the level moves by {@code meanRatio}, the average of the members' price ratios in
     * this step, or stays where it is when there is no member.
     */
    void advance(long day, int members, double meanRatio) {
        if (day != this.day) {
            if (this.day != StockState.NO_DAY) history.pushDaily(price);
            dayOpen = price;
            this.day = day;
        }
        if (members > 0 && Double.isFinite(meanRatio) && meanRatio > 0.0D) {
            double moved = price * meanRatio;
            if (Double.isFinite(moved))
                price = Math.max(PriceModel.MIN_PRICE_CENTS, Math.min(PriceModel.MAX_PRICE_CENTS, Math.round(moved)));
        }
        history.pushRecent(price);
    }

    /** Whether a step into economy-day {@code nextDay} is a day roll at which the index must split. */
    boolean splitDue(long nextDay) {
        return day != StockState.NO_DAY && day != nextDay && price >= SPLIT_AT_CENTS && splits < StockState.MAX_SPLITS;
    }

    /** A 10-for-1 split of the index fund; whoever keeps the holdings multiplies their units by ten. */
    void split(long splitDay) {
        price = Math.max(PriceModel.MIN_PRICE_CENTS, Math.floorDiv(price + StockState.SPLIT_FACTOR / 2L,
                (long) StockState.SPLIT_FACTOR));
        dayOpen = Math.max(PriceModel.MIN_PRICE_CENTS, dayOpen / StockState.SPLIT_FACTOR);
        history.divide(StockState.SPLIT_FACTOR);
        splits++;
        lastSplitDay = splitDay;
    }

    /** How many times the index fund has split 10-for-1, and the economy-day of the last time. */
    public int splits() { return splits; }
    public long lastSplitDay() { return lastSplitDay; }

    /** Whether the index has never been through a step. */
    boolean untouched() { return day == StockState.NO_DAY && history.recentSize() == 0; }

    /** The level now, in hundredths of a credit. */
    public long price() { return price; }
    /** The level at the start of the index's economy-day. */
    public long dayOpen() { return dayOpen; }
    public long day() { return day; }
    public PriceHistory history() { return history; }
    public int dayChangeBps() { return PriceModel.changeBps(dayOpen, price); }
}
