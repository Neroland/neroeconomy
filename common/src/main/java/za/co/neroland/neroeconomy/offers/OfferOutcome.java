package za.co.neroland.neroeconomy.offers;

/**
 * What an offer operation did.
 *
 * @param result           why it did or did not happen
 * @param offer            the offer acted on; null unless the result is {@link OfferResult#OK}
 * @param recipientGot     on an accept: the credits the recipient received, after the trade tax
 * @param recipientPaid    on an accept: the credits the recipient paid
 * @param senderGets       on an accept: the credits booked for the sender to collect, after the trade tax
 * @param tax              on an accept: the trade tax paid to the Reserve, both sides together
 * @param refundedToClaim  on a refund: true when the credits could not go straight to the sender's
 *                         balance and are waiting to be collected instead
 */
public record OfferOutcome(OfferResult result, Offer offer, long recipientGot, long recipientPaid, long senderGets,
        long tax, boolean refundedToClaim) {
    static OfferOutcome refused(OfferResult result) { return new OfferOutcome(result, null, 0L, 0L, 0L, 0L, false); }

    static OfferOutcome of(Offer offer) { return new OfferOutcome(OfferResult.OK, offer, 0L, 0L, 0L, 0L, false); }

    public boolean ok() { return result.ok(); }
}
