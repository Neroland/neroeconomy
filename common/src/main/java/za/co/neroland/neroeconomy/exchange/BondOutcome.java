package za.co.neroland.neroeconomy.exchange;

/**
 * What buying or cashing in a Savings Bond did.
 *
 * @param result   why it did or did not happen
 * @param bond     the bond bought or cashed in; null unless the result is OK
 * @param paid     credits that reached the balance: 0 for a purchase, the principal — and the
 *                 interest, for a bond that had matured — for a bond cashed in
 * @param interest the interest that was part of {@code paid}; 0 for a bond cashed in early
 */
public record BondOutcome(TradeResult result, Bond bond, long paid, long interest) {
    public static BondOutcome refused(TradeResult result) { return new BondOutcome(result, null, 0L, 0L); }

    public boolean ok() { return result.ok(); }

    /** The lang key of why it did not happen; empty when it did. */
    public String reasonKey() { return result.ok() ? "" : result.translationKey(); }
}
