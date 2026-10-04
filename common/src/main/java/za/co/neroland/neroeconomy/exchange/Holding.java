package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What one account holds of one stock (or of the index fund): a short list of <b>lots</b>, oldest
 * first. A lot is what one purchase bought — units, what they cost in whole credits, and the
 * economy time they were bought at. Pure Java: no store, no ledger, no clock.
 *
 * <ul>
 *   <li><b>Selling</b> takes from the oldest lot first. Taking part of a lot takes the same part
 *       of its cost, rounded down, and the lot keeps the rest, so the cost of a holding is always
 *       exactly what was paid for the units still in it.</li>
 *   <li><b>Holding period.</b> A lot cannot be sold until {@code holdMillis} of economy time have
 *       passed since it was bought. Until then it is <em>young</em>.</li>
 *   <li><b>Bound.</b> At most {@link #MAX_YOUNG_LOTS} young lots; a purchase that would make one
 *       more is refused. Older lots are merged ({@link #settle}): a lot bought before the last
 *       dividend day joins the first lot of the list for good, and the others are kept one per
 *       economy-day, which is all the dividend rule ever needs to know. A holding is therefore
 *       never more than a dozen lots or so, however often its owner trades.</li>
 * </ul>
 *
 * <p>A merged lot carries the <em>latest</em> purchase time of what went into it. That loses
 * nothing: the lots that are merged are only ever ones the holding period and the dividend rule
 * already treat alike.
 */
public final class Holding {
    /** Most lots still inside the holding period that one holding may have. */
    public static final int MAX_YOUNG_LOTS = 8;
    /** Most units one purchase, and one holding before a split, may be of. */
    public static final long MAX_UNITS = 1_000_000_000_000_000L;

    /**
     * One purchase, or several merged.
     *
     * @param units      whole units, above 0
     * @param cost       what they cost, in whole credits: the principal held for them in the
     *                   exchange account
     * @param acquiredAt economy time of the purchase; of the latest one for a merged lot
     */
    public record Lot(long units, long cost, long acquiredAt) {
        static final Codec<Lot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("units").forGetter(Lot::units),
                Codec.LONG.fieldOf("cost").forGetter(Lot::cost),
                Codec.LONG.optionalFieldOf("at", 0L).forGetter(Lot::acquiredAt)).apply(instance, Lot::new));

        boolean valid() { return units > 0L && cost >= 0L; }

        /** Whether the lot may be sold at {@code now}. */
        public boolean sellable(long now, long holdMillis) { return holdMillis <= 0L || now - acquiredAt >= holdMillis; }

        /**
         * The economy-day the lot counts as bought in for the dividend rule. A lot bought on the
         * very first millisecond of a day belongs to the day before: it has held the whole of the
         * day that then begins.
         */
        long dividendDay() { return Math.floorDiv(acquiredAt - 1L, PriceModel.DAY_MILLIS); }
    }

    static final Codec<Holding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("stock").forGetter(Holding::stockId),
            Lot.CODEC.listOf().optionalFieldOf("lots", List.of()).forGetter(Holding::lots))
            .apply(instance, Holding::fromData));

    private final String stockId;
    private final List<Lot> lots = new ArrayList<>();

    public Holding(String stockId) {
        if (stockId == null || stockId.isEmpty()) throw new IllegalArgumentException("holding");
        this.stockId = stockId;
    }

    private static Holding fromData(String stockId, List<Lot> rows) {
        // A holding of nothing that has a name is no holding: it loads empty, and whoever reads the
        // list leaves an empty one out. Loading never throws.
        if (stockId == null || stockId.isEmpty()) return new Holding("unknown:unknown");
        Holding holding = new Holding(stockId);
        // A row that cannot be a lot is left out; every other one still loads.
        for (Lot row : rows) if (row != null && row.valid()) holding.lots.add(row);
        return holding;
    }

    // --- reads -------------------------------------------------------------------------------

    public String stockId() { return stockId; }

    /** The lots, oldest first. A copy. */
    public List<Lot> lots() { return List.copyOf(lots); }

    public boolean isEmpty() { return lots.isEmpty(); }

    public int lotCount() { return lots.size(); }

    /** Every unit held. */
    public long units() {
        long total = 0L;
        for (Lot lot : lots) total = ExchangeMath.add(total, lot.units());
        return total;
    }

    /** What the units still held cost, in whole credits. */
    public long cost() {
        long total = 0L;
        for (Lot lot : lots) total = ExchangeMath.add(total, lot.cost());
        return total;
    }

    /** The units that may be sold at {@code now}: those of every lot past the holding period. */
    public long sellableUnits(long now, long holdMillis) {
        long total = 0L;
        for (Lot lot : lots) if (lot.sellable(now, holdMillis)) total = ExchangeMath.add(total, lot.units());
        return total;
    }

    /** How many lots are still inside the holding period at {@code now}. */
    public int youngLots(long now, long holdMillis) {
        int young = 0;
        for (Lot lot : lots) if (!lot.sellable(now, holdMillis)) young++;
        return young;
    }

    /**
     * The economy time at which the next lot leaves the holding period, or 0 when every unit may
     * be sold already.
     */
    public long earliestSellAt(long now, long holdMillis) {
        long earliest = 0L;
        for (Lot lot : lots) {
            if (lot.sellable(now, holdMillis)) continue;
            long at = lot.acquiredAt() + holdMillis;
            if (earliest == 0L || at < earliest) earliest = at;
        }
        return earliest;
    }

    /** The units of every lot bought at or before {@code cutoff}: what a dividend is paid on. */
    public long unitsHeldSince(long cutoff) {
        long total = 0L;
        for (Lot lot : lots) if (lot.acquiredAt() <= cutoff) total = ExchangeMath.add(total, lot.units());
        return total;
    }

    /**
     * What the oldest {@code units} sellable units cost: the principal a sale of them returns.
     * Changes nothing. -1 when fewer than that many may be sold at {@code now}.
     */
    public long basisOf(long units, long now, long holdMillis) {
        if (units <= 0L) return -1L;
        long left = units;
        long basis = 0L;
        for (Lot lot : lots) {
            if (!lot.sellable(now, holdMillis)) continue;
            long taken = Math.min(left, lot.units());
            basis += partOf(lot, taken);
            left -= taken;
            if (left == 0L) return basis;
        }
        return -1L;
    }

    /** The cost of {@code taken} units of a lot: all of it for the whole lot, the same share rounded down otherwise. */
    private static long partOf(Lot lot, long taken) {
        return taken >= lot.units() ? lot.cost() : ExchangeMath.mulDiv(lot.cost(), taken, lot.units(), false);
    }

    // --- changes -----------------------------------------------------------------------------

    /** A purchase: a new lot at the end of the list. */
    public void add(long units, long cost, long now) {
        if (units <= 0L || cost < 0L) throw new IllegalArgumentException("lot");
        lots.add(new Lot(units, cost, now));
    }

    /**
     * A sale: takes {@code units} from the oldest sellable lots.
     *
     * @return what they cost, exactly as {@link #basisOf} said
     * @throws IllegalStateException when fewer than that many may be sold; ask {@link #basisOf} first
     */
    public long take(long units, long now, long holdMillis) {
        if (basisOf(units, now, holdMillis) < 0L) throw new IllegalStateException("not held");
        long left = units;
        long basis = 0L;
        for (int index = 0; index < lots.size() && left > 0L; index++) {
            Lot lot = lots.get(index);
            if (!lot.sellable(now, holdMillis)) continue;
            long taken = Math.min(left, lot.units());
            long part = partOf(lot, taken);
            basis += part;
            left -= taken;
            if (taken == lot.units()) lots.remove(index--);
            else lots.set(index, new Lot(lot.units() - taken, lot.cost() - part, lot.acquiredAt()));
        }
        return basis;
    }

    /** Takes everything, whatever its age: a delisting pays every unit out. @return what it all cost */
    public long takeAll() {
        long basis = cost();
        lots.clear();
        return basis;
    }

    /**
     * Merges the lots that no rule tells apart any more, so the list stays short. A lot past the
     * holding period that was bought at or before {@code seasonedBefore} joins the first lot for
     * good; the other lots past the holding period are merged per dividend day.
     *
     * @param seasonedBefore the economy time up to which a purchase counts for every dividend
     *                       still to come: the start of the last dividend day that was paid
     * @return whether anything was merged
     */
    public boolean settle(long now, long holdMillis, long seasonedBefore) {
        boolean merged = false;
        for (int index = 1; index < lots.size(); index++) {
            Lot lot = lots.get(index);
            Lot before = lots.get(index - 1);
            if (!lot.sellable(now, holdMillis) || !before.sellable(now, holdMillis)) continue;
            boolean seasoned = lot.acquiredAt() <= seasonedBefore && before.acquiredAt() <= seasonedBefore;
            if (!seasoned && lot.dividendDay() != before.dividendDay()) continue;
            lots.set(index - 1, new Lot(ExchangeMath.add(before.units(), lot.units()),
                    ExchangeMath.add(before.cost(), lot.cost()), Math.max(before.acquiredAt(), lot.acquiredAt())));
            lots.remove(index--);
            merged = true;
        }
        return merged;
    }

    /** A share split: every lot holds {@code factor} times the units, at the cost it had. */
    public void split(int factor) {
        if (factor < 2) return;
        for (int index = 0; index < lots.size(); index++) {
            Lot lot = lots.get(index);
            long units = Long.MAX_VALUE / factor < lot.units() ? Long.MAX_VALUE : lot.units() * factor;
            lots.set(index, new Lot(units, lot.cost(), lot.acquiredAt()));
        }
    }
}
