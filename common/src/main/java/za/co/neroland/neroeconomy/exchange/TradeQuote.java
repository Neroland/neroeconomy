package za.co.neroland.neroeconomy.exchange;

/**
 * What a purchase or a sale on the exchange comes to, worked out against the price as it is now.
 * A quote is what a confirm step shows before anything moves, and — returned by the trade itself
 * with {@link TradeResult#OK} — the record of what did move. Amounts are whole credits; a unit
 * price is whole hundredths of a credit.
 *
 * <p><b>A purchase:</b> {@code total} is what the units cost (the principal), {@code fee} is
 * charged on it and {@code net} — the two together — is what leaves the balance.
 *
 * <p><b>A sale:</b> {@code total} is what the units fetch at the bid. {@code costBasis} is what
 * they were bought for, and it always comes back in full. When they fetch more, the difference is
 * the {@code gain}; {@code gainPayable} is what the Reserve can pay of it now and
 * {@code gainForfeited} the rest, which is given up if the sale goes ahead. When they fetch less,
 * the difference is the {@code loss}. {@code fee} is charged on what is received and {@code net} is
 * what reaches the balance after it.
 *
 * @param result         {@link TradeResult#OK}, or why the trade cannot happen as asked.
 *                       {@link TradeResult#GAIN_REDUCED} is the one refusal a sale can go ahead
 *                       past, by accepting {@code gainPayable}
 * @param unitPriceCents the ask for a purchase, the bid for a sale; 0 when there is no price
 * @param sellableUnits  units of the holding that may be sold now (a sale); 0 for a purchase
 * @param earliestSellAt economy time at which the next units of the holding leave the holding
 *                       period; 0 when all of it may be sold already
 */
public record TradeQuote(TradeResult result, TradeSide side, String stockId, long units, long unitPriceCents,
        long total, long fee, long costBasis, long gain, long gainPayable, long gainForfeited, long loss, long net,
        long sellableUnits, long earliestSellAt) {

    /** A refusal before any number could be worked out. */
    public static TradeQuote refused(TradeResult result, TradeSide side, String stockId, long units) {
        return new TradeQuote(result, side, stockId == null ? "" : stockId, Math.max(0L, units), 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L);
    }

    public boolean ok() { return result.ok(); }

    /** The lang key of why the trade cannot happen as asked; empty when it can. */
    public String reasonKey() { return result.ok() ? "" : result.translationKey(); }

    /** The same numbers under another result. */
    public TradeQuote with(TradeResult next) {
        return new TradeQuote(next, side, stockId, units, unitPriceCents, total, fee, costBasis, gain, gainPayable,
                gainForfeited, loss, net, sellableUnits, earliestSellAt);
    }
}
