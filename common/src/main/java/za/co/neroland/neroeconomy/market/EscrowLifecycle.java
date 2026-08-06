package za.co.neroland.neroeconomy.market;

/** Overflow-safe arithmetic shared by credits-backed market escrow and its tests. */
public final class EscrowLifecycle {
    private EscrowLifecycle() { }
    public static long create(int quantity, long unitPrice) {
        if (quantity < 1 || unitPrice < 1L) throw new IllegalArgumentException("positive escrow values required");
        return Math.multiplyExact(quantity, unitPrice);
    }
    public static long fill(long escrow, int quantity, long unitPrice) {
        long paid = create(quantity, unitPrice);
        if (paid > escrow) throw new IllegalArgumentException("fill exceeds escrow");
        return escrow - paid;
    }
    public static long expire(long escrow) { return Math.max(0L, escrow); }
}
