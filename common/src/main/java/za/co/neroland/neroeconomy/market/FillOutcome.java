package za.co.neroland.neroeconomy.market;

/**
 * What filling a buy offer did, so the seller can be told what the sale was worth and what is
 * theirs to collect.
 *
 * @param result why the fill did or did not happen; every amount is 0 unless it is {@link FillResult#SUCCESS}
 * @param gross  the offer's price for the quantity sold
 * @param fee    the market fee taken from it
 * @param net    what was booked to the seller's credit claim
 */
public record FillOutcome(FillResult result, long gross, long fee, long net) {
    static FillOutcome refused(FillResult result) { return new FillOutcome(result, 0L, 0L, 0L); }

    public boolean success() { return result == FillResult.SUCCESS; }
}
