package za.co.neroland.neroeconomy.exchange;

/**
 * Works out what a purchase or a sale comes to. Pure: it is handed the terms, the stock's
 * {@link Listing}, the holding and a few numbers, and returns a {@link TradeQuote}; it reaches no
 * store, no ledger and no clock. The real desk and the practice book both quote through here, so a
 * practice trade is priced, limited and held exactly as a real one is.
 */
public final class Quotes {
    /** Asks {@link #sell} for every unit that may be sold now. */
    public static final long ALL = Long.MAX_VALUE;

    private Quotes() { }

    /**
     * A purchase of {@code units}.
     *
     * @param holding  what the account already holds of the stock, or null
     * @param invested what the account's whole portfolio cost: shares, index and bonds
     * @param balance  what the account can spend
     */
    public static TradeQuote buy(TradingTerms terms, Listing listing, Holding holding, long invested, long balance,
            String stockId, long units, long now) {
        if (units < 1L || units > Holding.MAX_UNITS) return TradeQuote.refused(TradeResult.INVALID, TradeSide.BUY, stockId, units);
        if (!listing.tradeable()) return TradeQuote.refused(listing.status(), TradeSide.BUY, stockId, units);
        long ask = listing.ask();
        long cost = ExchangeMath.cost(units, ask);
        long fee = ExchangeMath.fee(cost, terms.feeBps());
        long spend = ExchangeMath.add(cost, fee);
        TradeResult result = TradeResult.OK;
        long position = holding == null ? 0L : holding.cost();
        if (cost == Long.MAX_VALUE || spend == Long.MAX_VALUE) result = TradeResult.INVALID;
        else if (ExchangeMath.add(position, cost) > terms.maxPositionPerStock()) result = TradeResult.POSITION_LIMIT;
        else if (ExchangeMath.add(invested, cost) > terms.maxPortfolio()) result = TradeResult.PORTFOLIO_LIMIT;
        else if (holding != null && holding.youngLots(now, terms.holdMillis()) >= Holding.MAX_YOUNG_LOTS)
            result = TradeResult.SETTLING;
        else if (spend > balance) result = TradeResult.INSUFFICIENT_FUNDS;
        return new TradeQuote(result, TradeSide.BUY, stockId, units, ask, cost, fee, 0L, 0L, 0L, 0L, 0L, spend, 0L,
                holding == null ? 0L : holding.earliestSellAt(now, terms.holdMillis()));
    }

    /**
     * A purchase of as many whole units as {@code credits} cover, fee included.
     * {@link TradeResult#TOO_SMALL} when they do not cover one.
     */
    public static TradeQuote buyForCredits(TradingTerms terms, Listing listing, Holding holding, long invested,
            long balance, String stockId, long credits, long now) {
        if (credits < 1L) return TradeQuote.refused(TradeResult.INVALID, TradeSide.BUY, stockId, 0L);
        if (!listing.tradeable()) return TradeQuote.refused(listing.status(), TradeSide.BUY, stockId, 0L);
        long units = ExchangeMath.unitsFor(credits, listing.ask(), terms.feeBps());
        if (units < 1L) return new TradeQuote(TradeResult.TOO_SMALL, TradeSide.BUY, stockId, 0L, listing.ask(), 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        return buy(terms, listing, holding, invested, balance, stockId, units, now);
    }

    /**
     * A sale of {@code units}, or of everything that may be sold now for {@link #ALL}.
     *
     * @param gainCap the most the gain of this sale can be paid: what the Reserve and today's
     *                exchange budget still cover, or {@link Long#MAX_VALUE} where nothing limits it
     */
    public static TradeQuote sell(TradingTerms terms, Listing listing, Holding holding, String stockId, long units,
            long now, long gainCap) {
        if (units < 1L) return TradeQuote.refused(TradeResult.INVALID, TradeSide.SELL, stockId, units);
        if (!listing.tradeable()) return TradeQuote.refused(listing.status(), TradeSide.SELL, stockId, 0L);
        if (holding == null || holding.isEmpty()) return TradeQuote.refused(TradeResult.NOTHING_HELD, TradeSide.SELL, stockId, 0L);
        long sellable = holding.sellableUnits(now, terms.holdMillis());
        long earliest = holding.earliestSellAt(now, terms.holdMillis());
        long wanted = units == ALL ? sellable : units;
        long bid = listing.bid();
        TradeResult refusal = units != ALL && units > holding.units() ? TradeResult.NOT_ENOUGH_UNITS
                : wanted > sellable || wanted < 1L ? TradeResult.HOLDING_PERIOD : null;
        if (refusal != null) return new TradeQuote(refusal, TradeSide.SELL, stockId, units == ALL ? 0L : units, bid, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, sellable, earliest);
        return sale(TradeResult.OK, stockId, wanted, bid, holding.basisOf(wanted, now, terms.holdMillis()),
                terms.feeBps(), gainCap, sellable, earliest);
    }

    /**
     * The sale of a whole holding at {@code bidCents}, whatever its age: what a delisting pays out.
     * The result is {@link TradeResult#OK} even when the gain is cut short, because the holder is
     * not asked.
     */
    public static TradeQuote payOut(TradingTerms terms, Holding holding, long bidCents, long gainCap) {
        TradeQuote quote = sale(TradeResult.OK, holding.stockId(), holding.units(), bidCents, holding.cost(),
                terms.feeBps(), gainCap, holding.units(), 0L);
        return quote.result() == TradeResult.GAIN_REDUCED ? quote.with(TradeResult.OK) : quote;
    }

    private static TradeQuote sale(TradeResult result, String stockId, long units, long bid, long basis, int feeBps,
            long gainCap, long sellable, long earliest) {
        long proceeds = ExchangeMath.proceeds(units, bid);
        long gain = Math.max(0L, proceeds - basis);
        long loss = Math.max(0L, basis - proceeds);
        long payable = Math.min(gain, Math.max(0L, gainCap));
        // What reaches the account before the fee: the principal and what is paid of the gain, or
        // only what the units fetched when that is less than they cost.
        long received = loss > 0L ? proceeds : ExchangeMath.add(basis, payable);
        long fee = ExchangeMath.fee(received, feeBps);
        return new TradeQuote(payable < gain ? TradeResult.GAIN_REDUCED : result, TradeSide.SELL, stockId, units, bid,
                proceeds, fee, basis, gain, payable, gain - payable, loss, received - fee, sellable, earliest);
    }
}
