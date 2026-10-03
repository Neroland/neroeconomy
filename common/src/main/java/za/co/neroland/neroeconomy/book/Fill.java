package za.co.neroland.neroeconomy.book;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;

/**
 * One fill: goods changed hands between two accounts at one price, settled as one ledger operation.
 *
 * <p>A fill names both parties because the book needs both. Nothing shown to a player may: a
 * result message is built from the amounts only, and the counterparty is never named.
 *
 * @param sellOrder       the sell order filled, or the id of the one being placed
 * @param buyOrder        the buy order filled, or the id of the one being placed; null for a direct purchase
 * @param quantity        how many changed hands
 * @param unitPrice       the price per unit the sale settled at: the resting order's
 * @param base            {@code unitPrice × quantity}: the seller's side of the sale
 * @param buyerPaid       what the buyer paid in the end, after any price modifier
 * @param fee             the market fee taken from the seller's proceeds
 * @param sellerReceives  {@code base − fee}
 * @param refund          what came back to the buyer from escrow because their limit was above the price
 * @param discountSkipped a discount applied but the Reserve could not pay it, so the buyer paid the base
 */
public record Fill(UUID sellOrder, UUID buyOrder, AccountId seller, AccountId buyer, String item, int payload,
        int quantity, long unitPrice, long base, long buyerPaid, long fee, long sellerReceives, long refund,
        boolean discountSkipped) {
    /** What the price modifiers changed for the buyer: positive for a surcharge, negative for a discount. */
    public long adjustment() { return buyerPaid - base; }
}
