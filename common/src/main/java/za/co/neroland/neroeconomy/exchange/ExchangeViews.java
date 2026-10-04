package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.api.StockType;

/**
 * What a screen or the companion app shows of the exchange, as plain copies: the market list with
 * what each product costs to buy and fetches when sold, and one account's portfolio — real or
 * practice — with what was put in, what it is worth now and the difference. Nothing here can
 * change the exchange, and a portfolio view is only ever built for the account that asks.
 *
 * <p>Amounts are whole credits; prices are whole hundredths of a credit; times are economy time
 * (convert with {@code ServerCurrencyProvider.toWallMillis} before they leave the server).
 */
public final class ExchangeViews {
    /** Lang keys of the plain risk labels. */
    public static final String RISK_STEADY = "invest.neroeconomy.risk.steady";
    public static final String RISK_RISKY = "invest.neroeconomy.risk.risky";
    public static final String RISK_INDEX = "invest.neroeconomy.risk.index";
    public static final String RISK_BOND = "invest.neroeconomy.risk.bond";

    private ExchangeViews() { }

    /**
     * One thing that can be bought on the exchange: a company, or the index fund.
     *
     * @param askCents             what one unit costs to buy now
     * @param bidCents             what one unit fetches when sold now
     * @param statusKey            lang key of why it cannot be traded now (halted, delisted); empty when it can
     * @param riskKey              lang key of the plain risk label
     * @param dividendBasisPoints  dividend per economy-week, in hundredths of a percent of the price; 0 for none
     * @param splits               how many times it has split 10-for-1
     * @param lastSplitDay         the economy-day of the last split, or {@link StockState#NO_DAY}
     */
    public record Product(String id, String nameKey, String descriptionKey, boolean index, StockType type,
            String sector, long priceCents, long askCents, long bidCents, int dayChangeBasisPoints, String statusKey,
            String riskKey, int dividendBasisPoints, int splits, long lastSplitDay) {
        public boolean tradeable() { return statusKey.isEmpty(); }
    }

    /**
     * One Savings Bond term on offer.
     *
     * @param rateBasisPoints the interest for the whole term, in hundredths of a percent
     */
    public record BondOffer(String id, String nameKey, String descriptionKey, int days, int rateBasisPoints,
            String riskKey) { }

    /**
     * One holding of an account.
     *
     * @param cost           what the units still held were bought for
     * @param value          what they would fetch at the bid now, before the fee
     * @param difference     {@code value − cost}: negative when they are worth less than was paid
     * @param sellableUnits  units past the holding period
     * @param earliestSellAt economy time at which the next units leave the holding period; 0 when all may be sold
     * @param statusKey      lang key of why it cannot be sold now (halted, delisted); empty when it can
     */
    public record HoldingView(String stockId, String nameKey, long units, long cost, long bidCents, long value,
            long difference, long sellableUnits, long earliestSellAt, String statusKey) { }

    /**
     * One Savings Bond of an account.
     *
     * @param interest what it pays on top at maturity
     * @param matured  it has reached its term and is paid out at the next simulation step
     */
    public record BondView(UUID id, String term, String nameKey, int days, long principal, long interest,
            long boughtAt, long maturesAt, boolean matured) { }

    /**
     * One account's portfolio.
     *
     * @param practice         whether this is the practice sandbox, in play credits
     * @param cost             what the shares and index units still held were bought for
     * @param value            what they would fetch at the bid now, before fees
     * @param difference       {@code value − cost}
     * @param bondPrincipal    what is in open bonds
     * @param bondInterest     the interest those bonds pay at maturity
     * @param invested         what counts toward the portfolio limit: {@code cost + bondPrincipal}
     * @param portfolioLimit   {@code maxPortfolio}
     * @param positionLimit    {@code maxPositionPerStock}
     * @param bondLimit        {@code maxBondsPerPlayer}
     * @param lastDividendDay  the economy-day of the last dividend this account was paid, or {@link StockState#NO_DAY}
     * @param lastDividend     how much that was
     * @param dividends        what the last dividend day came to for everyone: the factor a screen
     *                         shows as "paid at 62 % because the Reserve was short". Always in
     *                         full for a practice portfolio
     * @param playBalance      the play credits of a practice portfolio; 0 for a real one
     * @param canReset         whether a practice portfolio may be reset today; false for a real one
     */
    public record PortfolioView(boolean practice, List<HoldingView> holdings, List<BondView> bonds, long cost,
            long value, long difference, long bondPrincipal, long bondInterest, long invested, long portfolioLimit,
            long positionLimit, int bondLimit, long lastDividendDay, long lastDividend, DividendRun dividends,
            long playBalance, boolean canReset) {
        public PortfolioView {
            holdings = List.copyOf(holdings);
            bonds = List.copyOf(bonds);
        }

        public boolean isEmpty() { return holdings.isEmpty() && bonds.isEmpty(); }
    }

    // --- the market list ---------------------------------------------------------------------

    /** Every company — the listed ones, then any delisted ones still being wound up — and then the index fund. */
    public static List<Product> products(ExchangeSection section, ExchangeEnv env) {
        List<Product> out = new ArrayList<>();
        for (ExchangeApi.StockQuote quote : section.quotes(env.registry())) {
            Listing listing = section.listing(env, quote.id());
            StockState state = section.state(quote.id());
            out.add(new Product(quote.id(), quote.nameKey(), quote.descriptionKey(), false, quote.type(), quote.sector(),
                    quote.priceCents(), ExchangeMath.ask(quote.priceCents(), listing.spreadBps()),
                    ExchangeMath.bid(quote.priceCents(), listing.spreadBps()), quote.dayChangeBasisPoints(),
                    status(listing), quote.type() == StockType.RISKY ? RISK_RISKY : RISK_STEADY,
                    (int) Math.round(quote.dividendYieldPercent() * 100.0D), state == null ? 0 : state.splits(),
                    state == null ? StockState.NO_DAY : state.lastSplitDay()));
        }
        ExchangeApi.IndexQuote fund = section.indexQuote();
        Listing listing = section.listing(env, IndexState.ID);
        int spread = env.terms().spreadIndexBps();
        out.add(new Product(fund.id(), fund.nameKey(), fund.descriptionKey(), true, StockType.STEADY, "",
                fund.priceCents(), ExchangeMath.ask(fund.priceCents(), spread), ExchangeMath.bid(fund.priceCents(), spread),
                fund.dayChangeBasisPoints(), status(listing), RISK_INDEX, 0, section.index().splits(),
                section.index().lastSplitDay()));
        return List.copyOf(out);
    }

    /** The three Savings Bond terms and what each pays now. */
    public static List<BondOffer> bonds(TradingTerms terms) {
        List<BondOffer> out = new ArrayList<>();
        for (BondTerm term : BondTerm.values()) out.add(new BondOffer(term.key(), term.translationKey(),
                term.descriptionKey(), term.days(), terms.bondRateBps(term), RISK_BOND));
        return List.copyOf(out);
    }

    private static String status(Listing listing) { return listing.tradeable() ? "" : listing.status().translationKey(); }

    // --- a portfolio -------------------------------------------------------------------------

    /** The account's real portfolio. */
    public static PortfolioView portfolio(ExchangeSection section, ExchangeEnv env, AccountId account, long now) {
        return view(section, env, section.desk().portfolio(account), now, false, 0L, false, section.dividends());
    }

    /** The account's practice portfolio: the same shape, in play credits. */
    public static PortfolioView practice(ExchangeSection section, ExchangeEnv env, AccountId account, long now) {
        PracticeBook practice = section.practice();
        DividendRun real = section.dividends();
        // Practice dividends are never scaled: the day is the real one, the factor is always one.
        DividendRun full = new DividendRun(real.day(), 0L, 0L, 1L, 1L, 0);
        return view(section, env, practice.portfolio(account), now, true, practice.balance(env.terms(), account),
                practice.canReset(account), full);
    }

    private static PortfolioView view(ExchangeSection section, ExchangeEnv env, Portfolio portfolio, long now,
            boolean practice, long playBalance, boolean canReset, DividendRun dividends) {
        TradingTerms terms = env.terms();
        List<HoldingView> holdings = new ArrayList<>();
        List<BondView> bonds = new ArrayList<>();
        long cost = 0L;
        long value = 0L;
        if (portfolio != null) synchronized (section) {
            for (Holding holding : portfolio.holdings()) {
                Listing listing = section.listing(env, holding.stockId());
                long bid = listing.priceCents() > 0L ? listing.bid() : 0L;
                long worth = ExchangeMath.proceeds(holding.units(), bid);
                holdings.add(new HoldingView(holding.stockId(), nameKey(section, env, holding.stockId()), holding.units(),
                        holding.cost(), bid, worth, worth - holding.cost(),
                        holding.sellableUnits(now, terms.holdMillis()), holding.earliestSellAt(now, terms.holdMillis()),
                        status(listing)));
                cost = ExchangeMath.add(cost, holding.cost());
                value = ExchangeMath.add(value, worth);
            }
            for (Bond bond : portfolio.bonds()) bonds.add(new BondView(bond.id(), bond.term().key(),
                    bond.term().translationKey(), bond.term().days(), bond.principal(), bond.interest(), bond.boughtAt(),
                    bond.maturesAt(), bond.matured(now)));
        }
        long principal = portfolio == null ? 0L : portfolio.bondPrincipal();
        return new PortfolioView(practice, holdings, bonds, cost, value, value - cost, principal,
                portfolio == null ? 0L : portfolio.bondInterest(), ExchangeMath.add(cost, principal), terms.maxPortfolio(),
                terms.maxPositionPerStock(), terms.maxBonds(), portfolio == null ? StockState.NO_DAY : portfolio.dividendDay(),
                portfolio == null ? 0L : portfolio.dividend(), dividends, playBalance, canReset);
    }

    private static String nameKey(ExchangeSection section, ExchangeEnv env, String stockId) {
        if (IndexState.ID.equals(stockId)) return IndexState.NAME_KEY;
        StockDefinition definition = env.registry().get(stockId);
        if (definition != null) return definition.nameKey();
        StockState state = section.state(stockId);
        return state == null ? "" : state.nameKey();
    }
}
