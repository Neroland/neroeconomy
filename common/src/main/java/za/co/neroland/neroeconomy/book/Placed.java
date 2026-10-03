package za.co.neroland.neroeconomy.book;

import java.util.List;
import java.util.UUID;

/**
 * What placing an order did: what filled at once, and what is left resting on the book.
 *
 * @param result     why the order was or was not placed; everything else is empty unless it is
 *                   {@link BookResult#OK}
 * @param orderId    the id of the order — also when all of it filled at once and nothing rests
 * @param rested     how many are left resting on the book; 0 when everything filled at once
 * @param fills      what filled at once, best price first
 * @param listingFee the listing fee charged for the order
 * @param escrowed   credits moved into escrow for a buy order when it was placed
 * @param toVault    whether goods bought at once went to the vault rather than the inventory
 */
public record Placed(BookResult result, UUID orderId, int rested, List<Fill> fills, long listingFee, long escrowed,
        boolean toVault) {
    static Placed refused(BookResult result) { return new Placed(result, null, 0, List.of(), 0L, 0L, false); }

    public boolean ok() { return result.ok(); }

    /** How many filled at once. */
    public int filled() {
        int total = 0;
        for (Fill fill : fills) total += fill.quantity();
        return total;
    }

    /** What the at-once fills were worth at the sellers' prices. */
    public long base() {
        long total = 0L;
        for (Fill fill : fills) total += fill.base();
        return total;
    }

    /** What the buyer paid for the at-once fills, after any price modifier. */
    public long buyerPaid() {
        long total = 0L;
        for (Fill fill : fills) total += fill.buyerPaid();
        return total;
    }

    /** The market fees taken from the at-once fills. */
    public long fees() {
        long total = 0L;
        for (Fill fill : fills) total += fill.fee();
        return total;
    }

    /** What the seller is owed for the at-once fills. */
    public long sellerReceives() {
        long total = 0L;
        for (Fill fill : fills) total += fill.sellerReceives();
        return total;
    }

    /** What came back from escrow because fills were cheaper than the buyer's limit. */
    public long refunded() {
        long total = 0L;
        for (Fill fill : fills) total += fill.refund();
        return total;
    }
}
