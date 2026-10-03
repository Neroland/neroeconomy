package za.co.neroland.neroeconomy.economy;

/**
 * Decides whether a ledger call took effect from the balances on either side of it. Used when the
 * call itself could not report: Core fires balance listeners synchronously inside the call and does
 * not catch what they throw, so an exception says nothing about whether the money already moved.
 */
public final class LedgerOutcome {
    private LedgerOutcome() { }

    /** A deposit of {@code amount} took effect when the balance rose by exactly that much. */
    public static boolean deposited(long before, long after, long amount) {
        return amount >= 0L && after >= before && after - before == amount;
    }

    /** A withdrawal of {@code amount} took effect when the balance fell by exactly that much. */
    public static boolean withdrawn(long before, long after, long amount) {
        return amount >= 0L && before >= after && before - after == amount;
    }

    /** A transfer took effect only when both sides moved; one side alone is never reported as success. */
    public static boolean transferred(long fromBefore, long fromAfter, long toBefore, long toAfter, long amount) {
        return withdrawn(fromBefore, fromAfter, amount) && deposited(toBefore, toAfter, amount);
    }
}
