package za.co.neroland.neroeconomy.market;

/**
 * What a purchase did, in the buyer's and the seller's terms, so the result can restate it.
 *
 * @param result          why the purchase did or did not happen; every amount is 0 unless it is
 *                        {@link PurchaseResult#SUCCESS}
 * @param paid            what the buyer paid in total
 * @param baseTotal       the seller's own price for the quantity, before any price modifier
 * @param fee             the market fee taken from the seller's proceeds
 * @param sellerReceives  what the seller gets: the base total less the fee
 * @param discountSkipped true when a discount applied to the price but the Reserve could not pay
 *                        it, so the purchase went through at the seller's own price
 * @param toVault         true when the goods were delivered to the buyer's vault rather than put
 *                        into their inventory: the buyer was away, or had no room for them
 */
public record PurchaseOutcome(PurchaseResult result, long paid, long baseTotal, long fee, long sellerReceives,
        boolean discountSkipped, boolean toVault) {
    static PurchaseOutcome refused(PurchaseResult result) {
        return new PurchaseOutcome(result, 0L, 0L, 0L, 0L, false, false);
    }

    public boolean success() { return result == PurchaseResult.SUCCESS; }

    /** What the price modifiers changed for the buyer: positive for a surcharge, negative for a discount. */
    public long adjustment() { return paid - baseTotal; }
}
