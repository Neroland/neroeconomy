package za.co.neroland.neroeconomy.book;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;

/**
 * What was traded on the order book since it was last asked: per item, how many changed hands and
 * for how many credits. The exchange's price model reads it once per simulation step
 * ({@link BookSection#drainTradedVolume}) to see how busy the trade in a company's commodities is.
 *
 * <p><b>What counts.</b> Every fill is recorded with the two accounts it was between. When the
 * tally is read, two caps are applied, so that volume cannot be manufactured cheaply:
 * <ul>
 *   <li>fills between the same two <em>player</em> accounts count up to {@code pairCap} credits —
 *       one player trading back and forth with a second account moves nothing more than that;</li>
 *   <li>the fills of one player account, with anyone, count up to {@code accountCap} credits.</li>
 * </ul>
 * An account never matches itself on the book, so a self-trade cannot be recorded at all. Fills
 * between two system accounts are not capped.
 *
 * <p><b>Bounds and privacy.</b> At most {@link #MAX_ROWS} (pair, item) rows are kept; a fill that
 * would add another is not counted. The tally is never saved: it lives in memory from one step to
 * the next, is emptied when it is read, is dropped for an erased player at once
 * ({@link #forget}), and what is handed out carries items and totals only — never an account.
 */
final class TradedVolume {
    /** The most (pair, item) rows kept between two reads. */
    static final int MAX_ROWS = 4096;

    /** The two accounts of a fill, in a fixed order, and the item. */
    private record Row(AccountId first, AccountId second, String item) {
        boolean involves(AccountId account) { return first.equals(account) || second.equals(account); }
    }

    /** The two accounts of a row, without the item. */
    private record Pair(AccountId first, AccountId second) { }

    /** Running totals of one row. */
    private static final class Sum {
        private long quantity;
        private long value;
    }

    private final Map<Row, Sum> rows = new LinkedHashMap<>();

    /** Records one fill of {@code quantity} of {@code item} for {@code value} credits in all. */
    void record(AccountId seller, AccountId buyer, String item, int quantity, long value) {
        if (seller == null || buyer == null || item == null || quantity < 1 || value < 1L || seller.equals(buyer))
            return;
        // The same two accounts are one pair whichever of them sold.
        boolean ordered = seller.stableKey().compareTo(buyer.stableKey()) <= 0;
        Row row = new Row(ordered ? seller : buyer, ordered ? buyer : seller, item);
        Sum sum = rows.get(row);
        if (sum == null) {
            if (rows.size() >= MAX_ROWS) return;
            sum = new Sum();
            rows.put(row, sum);
        }
        sum.quantity = add(sum.quantity, quantity);
        sum.value = add(sum.value, value);
    }

    /**
     * The counted totals per item since the last call, and an empty tally afterwards.
     *
     * @param pairCap    credits counted per pair of player accounts; 0 counts none of their fills
     * @param accountCap credits counted per player account; 0 counts no player's fills
     */
    Map<String, ItemVolume> drain(long pairCap, long accountCap) {
        Map<String, ItemVolume> totals = new LinkedHashMap<>();
        Map<Pair, Long> pairUsed = new HashMap<>();
        Map<AccountId, Long> accountUsed = new HashMap<>();
        for (Map.Entry<Row, Sum> entry : rows.entrySet()) {
            Row row = entry.getKey();
            Sum sum = entry.getValue();
            boolean firstPlayer = row.first() instanceof PlayerAccountId;
            boolean secondPlayer = row.second() instanceof PlayerAccountId;
            Pair pair = new Pair(row.first(), row.second());
            long counted = sum.value;
            if (firstPlayer && secondPlayer) counted = Math.min(counted, room(pairCap, pairUsed.get(pair)));
            if (firstPlayer) counted = Math.min(counted, room(accountCap, accountUsed.get(row.first())));
            if (secondPlayer) counted = Math.min(counted, room(accountCap, accountUsed.get(row.second())));
            if (counted <= 0L) continue;
            if (firstPlayer && secondPlayer) pairUsed.merge(pair, counted, TradedVolume::add);
            if (firstPlayer) accountUsed.merge(row.first(), counted, TradedVolume::add);
            if (secondPlayer) accountUsed.merge(row.second(), counted, TradedVolume::add);
            // The quantity is counted in the same proportion as the value.
            long quantity = counted == sum.value ? sum.quantity
                    : Math.max(1L, (long) ((double) sum.quantity * counted / sum.value));
            ItemVolume before = totals.get(row.item());
            totals.put(row.item(), before == null ? new ItemVolume(quantity, counted)
                    : new ItemVolume(add(before.quantity(), quantity), add(before.value(), counted)));
        }
        rows.clear();
        return totals;
    }

    /** Drops everything recorded about one account. */
    void forget(AccountId account) {
        if (account == null) return;
        for (Iterator<Row> iterator = rows.keySet().iterator(); iterator.hasNext();)
            if (iterator.next().involves(account)) iterator.remove();
    }

    int size() { return rows.size(); }

    private static long room(long cap, Long used) { return Math.max(0L, cap - (used == null ? 0L : used)); }

    private static long add(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }
}
