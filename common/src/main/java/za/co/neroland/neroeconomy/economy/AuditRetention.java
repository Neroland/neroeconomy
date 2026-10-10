package za.co.neroland.neroeconomy.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which transaction rows the server-wide row bound drops first (F2). Rows are kept in the order they
 * were written, so the front of the list is the oldest. When there are more than the bound, the
 * oldest ordinary rows go first; the rows an audit most needs — anything that created or destroyed
 * credits (admin grants, takes and sets, the daily mint) and large amounts — go only when they alone
 * pass the bound, oldest first. Retention by age applies to every row alike. Pure.
 */
public final class AuditRetention {
    /** A row moving at least this many credits outlives ordinary rows under the row bound. */
    public static final long LARGE_AMOUNT = 10_000L;

    private AuditRetention() { }

    /** Whether a row is one the bound keeps over ordinary rows. */
    public static boolean kept(TransactionType type, long amount) {
        return type != null && (type.mints() || type.burns() || Math.abs(amount) >= LARGE_AMOUNT);
    }

    /**
     * Trims {@code rows} (oldest first) to at most {@code max}: ordinary rows first, oldest first,
     * then kept rows, oldest first. @return how many rows were dropped
     */
    public static <T> int trim(List<T> rows, int max, Predicate<T> kept) {
        int bound = Math.max(0, max);
        int excess = rows.size() - bound;
        if (excess <= 0) return 0;
        int ordinaryToDrop = 0;
        for (T row : rows) if (!kept.test(row)) ordinaryToDrop++;
        ordinaryToDrop = Math.min(ordinaryToDrop, excess);
        int keptToDrop = excess - ordinaryToDrop;
        List<T> out = new ArrayList<>(bound);
        for (T row : rows) {
            boolean isKept = kept.test(row);
            if (!isKept && ordinaryToDrop > 0) {
                ordinaryToDrop--;
                continue;
            }
            if (isKept && keptToDrop > 0) {
                keptToDrop--;
                continue;
            }
            out.add(row);
        }
        rows.clear();
        rows.addAll(out);
        return excess;
    }
}
