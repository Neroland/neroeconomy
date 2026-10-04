package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.List;

/**
 * The price history a chart is drawn from: two fixed-size ring buffers, so it can never grow.
 *
 * <ul>
 *   <li><b>Recent</b>: the price after each of the last {@link #RECENT} steps — one economy-day at
 *       the default step length.</li>
 *   <li><b>Daily</b>: the closing price of each of the last {@link #DAILY} economy-days the
 *       simulation ran.</li>
 * </ul>
 *
 * <p>Only prices that have already happened are ever written here.
 */
public final class PriceHistory {
    /** Points kept in the recent ring: 144 steps. */
    public static final int RECENT = 144;
    /** Points kept in the daily ring: 30 days. */
    public static final int DAILY = 30;

    /** A ring of the last {@code capacity} values. */
    private static final class Ring {
        private final long[] values;
        private int size;
        private int next;

        Ring(int capacity) { values = new long[capacity]; }

        void push(long value) {
            values[next] = value;
            next = (next + 1) % values.length;
            if (size < values.length) size++;
        }

        /** Oldest first. */
        long[] toArray() {
            long[] out = new long[size];
            int start = (next - size + values.length) % values.length;
            for (int index = 0; index < size; index++) out[index] = values[(start + index) % values.length];
            return out;
        }

        /** Divides every stored value, never below the lowest price there is: what a share split does to a chart. */
        void divide(long divisor) {
            for (int index = 0; index < values.length; index++)
                if (values[index] > 0L) values[index] = Math.max(PriceModel.MIN_PRICE_CENTS, values[index] / divisor);
        }

        List<Long> toList() {
            List<Long> out = new ArrayList<>(size);
            for (long value : toArray()) out.add(value);
            return out;
        }

        /** Takes the last {@code capacity} of {@code saved}; anything that is not a price is left out. */
        void load(List<Long> saved) {
            if (saved == null) return;
            int from = Math.max(0, saved.size() - values.length);
            for (int index = from; index < saved.size(); index++) {
                Long value = saved.get(index);
                if (value != null && value > 0L) push(Math.min(value, PriceModel.MAX_PRICE_CENTS));
            }
        }
    }

    private final Ring recent = new Ring(RECENT);
    private final Ring daily = new Ring(DAILY);

    public PriceHistory() { }

    PriceHistory(List<Long> recentSaved, List<Long> dailySaved) {
        recent.load(recentSaved);
        daily.load(dailySaved);
    }

    /** A share split: every stored price is divided, so the chart shows no cliff. */
    void divide(long divisor) {
        if (divisor < 2L) return;
        recent.divide(divisor);
        daily.divide(divisor);
    }

    void pushRecent(long price) { recent.push(price); }
    void pushDaily(long close) { daily.push(close); }

    /** The price after each of the last steps, oldest first, in hundredths of a credit. A copy. */
    public long[] recent() { return recent.toArray(); }

    /** The closing price of each of the last days, oldest first, in hundredths of a credit. A copy. */
    public long[] daily() { return daily.toArray(); }

    public int recentSize() { return recent.size; }
    public int dailySize() { return daily.size; }

    List<Long> recentList() { return recent.toList(); }
    List<Long> dailyList() { return daily.toList(); }
}
