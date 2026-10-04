package za.co.neroland.neroeconomy.economy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Where every credit is, as aggregates only: no account is named and no balance is listed.
 *
 * @param circulation      the sum of every player balance
 * @param reserve          the Reserve's balance
 * @param escrow           credits promised but not yet delivered
 * @param exchange         principal of open exchange positions
 * @param houses           the working capital of every Merchant House together
 * @param obligations      the sum of every recorded escrow obligation
 * @param budgetUsedToday  what each Reserve budget has paid out in the current economy day
 * @param supplyBalanced   Invariant A: every balance together equals minted less burned
 * @param escrowBalanced   Invariant B: the escrow balance equals the recorded obligations
 * @param exchangeHeld     what the exchange section records as held for open positions: the cost
 *                         of every share and index unit, the principal of every bond and the
 *                         interest reserved for them
 * @param exchangeBalanced Invariant C: the exchange balance equals {@code exchangeHeld}
 */
public record SupplyReport(long circulation, long reserve, long escrow, long exchange, long houses,
        long mintedToday, long burnedToday, long mintedAllTime, long burnedAllTime,
        Map<TransactionType, Long> mintedByReason, Map<TransactionType, Long> burnedByReason,
        Map<BudgetKind, Long> budgetUsedToday, long obligations, boolean supplyBalanced, boolean escrowBalanced,
        long exchangeHeld, boolean exchangeBalanced) {

    public SupplyReport {
        mintedByReason = Collections.unmodifiableMap(new EnumMap<>(mintedByReason));
        burnedByReason = Collections.unmodifiableMap(new EnumMap<>(burnedByReason));
        EnumMap<BudgetKind, Long> budgets = new EnumMap<>(BudgetKind.class);
        budgets.putAll(budgetUsedToday);
        budgetUsedToday = Collections.unmodifiableMap(budgets);
    }

    /** Every credit in existence: all balances together. */
    public long total() { return circulation + reserve + escrow + exchange + houses; }

    public boolean healthy() { return supplyBalanced && escrowBalanced && exchangeBalanced; }
}
