package za.co.neroland.neroeconomy.economy;

/**
 * The arithmetic behind Invariant B: the escrow account holds exactly what the stores record as
 * owed. Pure Java, so the migration calculation is unit tested without a running game.
 */
public final class EscrowMath {
    private EscrowMath() { }

    /** The sum of every recorded obligation; negative parts are a caller bug and count as nothing. */
    public static long total(long... parts) {
        long total = 0L;
        for (long part : parts) {
            if (part <= 0L) continue;
            total = Long.MAX_VALUE - total < part ? Long.MAX_VALUE : total + part;
        }
        return total;
    }

    /**
     * What has to be credited to the escrow account so that it covers every recorded obligation.
     * An older version wrote escrowed credits off the ledger and kept only the numbers, so on
     * migration the escrow account starts at zero and this is the whole of the obligations; run
     * again on a migrated ledger it is zero.
     */
    public static long shortfall(long obligations, long escrowBalance) {
        if (obligations <= 0L) return 0L;
        return obligations > escrowBalance ? obligations - Math.max(0L, escrowBalance) : 0L;
    }
}
