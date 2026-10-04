package za.co.neroland.neroeconomy.exchange;

/**
 * What the trading rules need to know about one stock (or the index fund) at the moment of a
 * trade: whether it can be traded, its model price and its spread. The exchange section works one
 * out from its price state; the practice book is only ever handed one, so it has no way to reach
 * the section, the store or the ledger.
 *
 * @param status     {@link TradeResult#OK}, or why it cannot be traded: unknown, delisted, halted
 * @param priceCents the model price, in hundredths of a credit; 0 when there is none
 * @param spreadBps  ask less bid, in hundredths of a percent of the price
 */
public record Listing(TradeResult status, long priceCents, int spreadBps) {
    public static Listing refused(TradeResult status) { return new Listing(status, 0L, 0); }

    public boolean tradeable() { return status.ok(); }

    /** What a buyer pays per unit, in hundredths of a credit. */
    public long ask() { return ExchangeMath.ask(priceCents, spreadBps); }

    /** What a seller receives per unit, in hundredths of a credit. */
    public long bid() { return ExchangeMath.bid(priceCents, spreadBps); }
}
