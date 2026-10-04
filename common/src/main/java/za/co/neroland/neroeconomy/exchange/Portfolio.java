package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Everything one account holds on the exchange: its {@link Holding}s by stock id and its
 * {@link Bond}s, and what the last dividend day paid it. Pure data — it moves no credit and knows
 * no ledger; the real desk keeps one per account for real positions and the practice book keeps
 * its own, separate ones.
 */
public final class Portfolio {
    static final Codec<Portfolio> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Holding.CODEC.listOf().optionalFieldOf("holdings", List.of()).forGetter(Portfolio::holdingRows),
            Bond.CODEC.listOf().optionalFieldOf("bonds", List.of()).forGetter(Portfolio::bondRows),
            Codec.LONG.optionalFieldOf("dividend_day", StockState.NO_DAY).forGetter(portfolio -> portfolio.dividendDay),
            Codec.LONG.optionalFieldOf("dividend", 0L).forGetter(portfolio -> portfolio.dividend))
            .apply(instance, Portfolio::fromData));

    private final Map<String, Holding> holdings = new LinkedHashMap<>();
    private final List<Bond> bonds = new ArrayList<>();
    /** The economy-day of the last dividend this account was paid, and how much it was. */
    private long dividendDay = StockState.NO_DAY;
    private long dividend;

    public Portfolio() { }

    private static Portfolio fromData(List<Holding> holdings, List<Optional<Bond>> bonds, long dividendDay,
            long dividend) {
        Portfolio portfolio = new Portfolio();
        for (Holding holding : holdings)
            if (holding != null && !holding.isEmpty()) portfolio.holdings.putIfAbsent(holding.stockId(), holding);
        for (Optional<Bond> bond : bonds) bond.ifPresent(portfolio.bonds::add);
        portfolio.dividendDay = dividendDay;
        portfolio.dividend = Math.max(0L, dividend);
        return portfolio;
    }

    private List<Holding> holdingRows() { return new ArrayList<>(holdings.values()); }

    private List<Optional<Bond>> bondRows() { return bonds.stream().map(Optional::of).toList(); }

    // --- reads -------------------------------------------------------------------------------

    /** The holding of one stock, or null. */
    public Holding holding(String stockId) { return stockId == null ? null : holdings.get(stockId); }

    /** Every holding, in the order they were first bought. The live objects: do not keep them. */
    public List<Holding> holdings() { return new ArrayList<>(holdings.values()); }

    /** Every bond, oldest first. A copy. */
    public List<Bond> bonds() { return List.copyOf(bonds); }

    public int bondCount() { return bonds.size(); }

    public Bond bond(UUID id) {
        for (Bond bond : bonds) if (bond.id().equals(id)) return bond;
        return null;
    }

    /** What the shares and index units still held cost, in whole credits. */
    public long shareCost() {
        long total = 0L;
        for (Holding holding : holdings.values()) total = ExchangeMath.add(total, holding.cost());
        return total;
    }

    /** What was put into the bonds still open. */
    public long bondPrincipal() {
        long total = 0L;
        for (Bond bond : bonds) total = ExchangeMath.add(total, bond.principal());
        return total;
    }

    /** The interest reserved for the bonds still open. */
    public long bondInterest() {
        long total = 0L;
        for (Bond bond : bonds) total = ExchangeMath.add(total, bond.interest());
        return total;
    }

    /** What counts toward the portfolio limit: shares, index and bonds, at what was paid for them. */
    public long invested() { return ExchangeMath.add(shareCost(), bondPrincipal()); }

    /** Everything the exchange account holds for a real portfolio: share cost, bond principal and reserved interest. */
    public long held() { return ExchangeMath.add(invested(), bondInterest()); }

    public boolean isEmpty() { return holdings.isEmpty() && bonds.isEmpty(); }

    public long dividendDay() { return dividendDay; }
    public long dividend() { return dividend; }

    // --- changes -----------------------------------------------------------------------------

    /** The holding of one stock, made when there is none yet. */
    Holding holdingFor(String stockId) { return holdings.computeIfAbsent(stockId, Holding::new); }

    /** Drops the holding of one stock once it holds nothing. */
    void dropIfEmpty(String stockId) {
        Holding holding = holdings.get(stockId);
        if (holding != null && holding.isEmpty()) holdings.remove(stockId);
    }

    /** Removes a holding outright and hands it back; null when there was none. */
    Holding remove(String stockId) { return holdings.remove(stockId); }

    void add(Bond bond) { bonds.add(bond); }

    boolean remove(Bond bond) { return bonds.remove(bond); }

    void paidDividend(long day, long amount) {
        dividendDay = day;
        dividend = Math.max(0L, amount);
    }

    /** Merges old lots in every holding. @return whether anything changed */
    boolean settle(long now, long holdMillis, long seasonedBefore) {
        boolean changed = false;
        for (Holding holding : holdings.values()) changed |= holding.settle(now, holdMillis, seasonedBefore);
        return changed;
    }
}
