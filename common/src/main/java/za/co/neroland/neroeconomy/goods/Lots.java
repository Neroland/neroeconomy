package za.co.neroland.neroeconomy.goods;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arithmetic of lots: merging goods into a list of stacks, splitting them at the stack limit,
 * taking them out again and counting them. Every method returns new values and changes nothing it
 * was given, and none of them can create or lose a single item: what goes in is exactly what the
 * result holds, which the tests check by counting.
 */
public final class Lots {
    private Lots() { }

    /**
     * What taking goods out of a list of stacks produced.
     *
     * @param stacks what is left, in the original order
     * @param taken  how many were taken; less than asked for only when the list held fewer
     */
    public record Taken(List<Lot> stacks, long taken) { }

    /** One lot cut into stacks no larger than {@code limit}; the counts add up to the lot's. */
    public static List<Lot> split(Lot lot, int limit) {
        int perStack = Math.max(1, limit);
        List<Lot> out = new ArrayList<>((lot.count() + perStack - 1) / perStack);
        for (int left = lot.count(); left > 0; left -= perStack) out.add(lot.withCount(Math.min(perStack, left)));
        return out;
    }

    /**
     * Adds {@code adding} to {@code stacks}: each lot first tops up stacks of the same goods that
     * are below the limit, oldest first, and whatever is left becomes new stacks at the end, cut at
     * the limit. A stack already over its limit is left exactly as it is.
     */
    public static List<Lot> merge(List<Lot> stacks, Collection<Lot> adding, StackLimit limit) {
        List<Lot> out = new ArrayList<>(stacks);
        for (Lot lot : adding) {
            if (lot == null) continue;
            int perStack = limit.limitOf(lot);
            int left = lot.count();
            for (int index = 0; index < out.size() && left > 0; index++) {
                Lot held = out.get(index);
                if (!held.sameGoods(lot) || held.count() >= perStack) continue;
                int moved = Math.min(left, perStack - held.count());
                out.set(index, held.withCount(held.count() + moved));
                left -= moved;
            }
            if (left > 0) out.addAll(split(lot.withCount(left), perStack));
        }
        return out;
    }

    /**
     * Takes up to {@code count} of one kind of goods out of {@code stacks}, from the newest stack
     * backwards so the oldest stacks stay whole. Stacks that are emptied are removed.
     */
    public static Taken take(List<Lot> stacks, String item, int payload, long count) {
        List<Lot> out = new ArrayList<>(stacks);
        long left = Math.max(0L, count);
        for (int index = out.size() - 1; index >= 0 && left > 0L; index--) {
            Lot held = out.get(index);
            if (!held.is(item, payload)) continue;
            int moved = (int) Math.min(left, held.count());
            if (moved == held.count()) out.remove(index);
            else out.set(index, held.withCount(held.count() - moved));
            left -= moved;
        }
        return new Taken(out, Math.max(0L, count) - left);
    }

    /** How many of one kind of goods {@code stacks} hold. */
    public static long total(Collection<Lot> stacks, String item, int payload) {
        long total = 0L;
        for (Lot lot : stacks) if (lot != null && lot.is(item, payload)) total += lot.count();
        return total;
    }

    /** How many of each kind of goods {@code stacks} hold, in first-seen order. */
    public static Map<Lot.Key, Long> totals(Collection<Lot> stacks) {
        Map<Lot.Key, Long> totals = new LinkedHashMap<>();
        for (Lot lot : stacks) if (lot != null) totals.merge(lot.key(), (long) lot.count(), Long::sum);
        return totals;
    }

    /** Every item in {@code stacks}, whatever it is. */
    public static long count(Collection<Lot> stacks) {
        long total = 0L;
        for (Lot lot : stacks) if (lot != null) total += lot.count();
        return total;
    }
}
