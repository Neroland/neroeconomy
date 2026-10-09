package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.exchange.BondOutcome;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.ExchangeViews;
import za.co.neroland.neroeconomy.exchange.Investing;
import za.co.neroland.neroeconomy.exchange.PriceModel;
import za.co.neroland.neroeconomy.exchange.PriceText;
import za.co.neroland.neroeconomy.exchange.Quotes;
import za.co.neroland.neroeconomy.exchange.StockState;
import za.co.neroland.neroeconomy.exchange.TradeQuote;
import za.co.neroland.neroeconomy.exchange.TradeResult;
import za.co.neroland.neroeconomy.exchange.TradingTerms;
import za.co.neroland.neroeconomy.exchange.WeekOutlook;
import za.co.neroland.neroeconomy.text.TimeText;

/**
 * The Invest tab of one open hub on the server: real or practice credits, which list is shown,
 * what was picked, the last quote and its outlook, and the calls into {@link Investing}, which
 * checks the frozen economy, the request id and every rule again. Everything read is the viewer's
 * own portfolio and the public market; every line said back is a fixed sentence, and a practice
 * line always says it is practice.
 */
final class HubInvest {
    private final ServerPlayer player;
    private boolean practice;
    private HubAction.InvestView view = HubAction.InvestView.PRODUCTS;
    /** The stock id or bond term picked, and whether it is a bond term. */
    private String picked;
    private boolean pickedBond;
    private InvestPages.Quote quote;
    private InvestPages.Outlook outlook;

    HubInvest(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() { return player.level().getServer(); }

    private UUID self() { return player.getUUID(); }

    private long now() { return NeroEconomyCommon.CURRENCY_PROVIDER.now(); }

    /** A line to the player; a practice one always says so. */
    private void say(String key, Object... args) {
        MutableComponent line = Component.translatable(key, args);
        player.sendSystemMessage(practice ? Component.translatable("command.neroeconomy.invest.practice.line", line) : line);
    }

    void clearQuote() {
        quote = null;
        outlook = null;
    }

    // --- reading -----------------------------------------------------------------------------

    private ExchangeViews.PortfolioView portfolio() {
        return practice ? Investing.practicePortfolio(server(), self()) : Investing.portfolio(server(), self());
    }

    private ExchangeViews.Product product(List<ExchangeViews.Product> products, String id) {
        for (ExchangeViews.Product product : products) if (product.id().equals(id)) return product;
        return null;
    }

    HubPages.Built build(int page) {
        long now = now();
        List<ExchangeViews.Product> products = Investing.products(server());
        List<InvestPages.ProductLine> productLines = new ArrayList<>();
        for (ExchangeViews.Product product : products) {
            // A delisted company is being wound up; its holders are paid out, so it is not offered.
            if (product.statusKey().equals(TradeResult.DELISTED.translationKey())) continue;
            productLines.add(new InvestPages.ProductLine(product.id(), product.nameKey(), product.riskKey(),
                    product.statusKey(), product.index(), product.askCents(), product.bidCents(),
                    product.dayChangeBasisPoints(), product.dividendBasisPoints()));
        }
        List<InvestPages.BondTermLine> bondTerms = new ArrayList<>();
        for (ExchangeViews.BondOffer bond : Investing.bondOffers())
            bondTerms.add(new InvestPages.BondTermLine(bond.id(), bond.nameKey(), bond.riskKey(), bond.days(),
                    bond.rateBasisPoints()));

        ExchangeViews.PortfolioView mine = portfolio();
        List<InvestPages.HoldingLine> holdings = new ArrayList<>();
        for (ExchangeViews.HoldingView holding : mine.holdings())
            holdings.add(new InvestPages.HoldingLine(holding.stockId(), holding.nameKey(), holding.statusKey(),
                    holding.units(), holding.cost(), holding.value(), holding.difference(), holding.sellableUnits(),
                    holding.earliestSellAt() > 0L ? Math.max(0L, holding.earliestSellAt() - now) : 0L,
                    holding.bidCents()));
        List<InvestPages.BondLine> bonds = new ArrayList<>();
        for (ExchangeViews.BondView bond : mine.bonds())
            bonds.add(new InvestPages.BondLine(bond.id(), bond.nameKey(), bond.days(), bond.principal(), bond.interest(),
                    Math.max(0L, bond.maturesAt() - now), bond.matured()));
        InvestPages.Totals totals = new InvestPages.Totals(mine.cost(), mine.value(), mine.difference(),
                mine.bondPrincipal(), mine.invested(), mine.portfolioLimit(), mine.lastDividend(),
                mine.dividends().happened() ? mine.dividends().percent() : 100, mine.dividends().happened()
                        && mine.dividends().scaled());

        InvestPages.Picked pick = null;
        InvestPages.Chart chart = null;
        if (picked != null && pickedBond) {
            BondTerm term = BondTerm.parse(picked);
            if (term != null) pick = new InvestPages.Picked(term.key(), term.translationKey(),
                    ExchangeViews.RISK_BOND, "", true, false);
        } else if (picked != null) {
            ExchangeViews.Product product = product(products, picked);
            if (product != null) {
                pick = new InvestPages.Picked(product.id(), product.nameKey(), product.riskKey(), product.statusKey(),
                        false, product.index());
                chart = chart(product, now);
            }
        }
        InvestPages.InvestView investView = new InvestPages.InvestView(practice, view, ExchangeApi.enabled(),
                productLines, bondTerms, holdings, bonds, totals, mine.playBalance(), mine.canReset(), pick, chart,
                quote, outlook);
        return InvestPages.build(investView, page);
    }

    private InvestPages.Chart chart(ExchangeViews.Product product, long now) {
        var history = EconomyStore.get(server()).exchange().history(product.id()).orElse(null);
        if (history == null) return null;
        long[] recent = history.recentCents().stream().mapToLong(Long::longValue).toArray();
        long[] daily = history.dailyCents().stream().mapToLong(Long::longValue).toArray();
        int split = product.lastSplitDay() == StockState.NO_DAY ? -1
                : InvestPages.dayIndex(daily.length, Math.floorDiv(now, PriceModel.DAY_MILLIS), product.lastSplitDay());
        return new InvestPages.Chart(recent, daily, split,
                product.statusKey().equals(TradeResult.HALTED.translationKey()));
    }

    /** What the page depends on: prices, the portfolio, the choices made. Read every two seconds. */
    long signature() {
        return Objects.hash(practice, view, picked, quote, outlook, Investing.products(server()), portfolio());
    }

    // --- the calls ---------------------------------------------------------------------------

    void view(HubAction.InvestView next) {
        view = next;
        clearQuote();
    }

    void practice(boolean next) {
        practice = next;
        clearQuote();
    }

    void pick(HubPages.RowRef row) {
        picked = row.item();
        pickedBond = row.kind() == HubPages.RowRef.Kind.BOND_TERM;
        clearQuote();
    }

    /** What putting {@code credits} into the picked product would buy, and the week's outlook. */
    void quote(long credits) {
        clearQuote();
        if (picked == null) return;
        if (pickedBond) {
            BondTerm term = BondTerm.parse(picked);
            if (term == null) return;
            int rate = TradingTerms.fromConfig().bondRateBps(term);
            long interest = BondTerm.interest(credits, rate);
            quote = new InvestPages.Quote(HubAction.INVEST_QUOTE, true, "", credits, term.days(), 0L, credits, 0L,
                    credits, credits, interest, interest, 0L, 0L, 0L, 0L, false);
            WeekOutlook.Outcome week = Investing.bondOutlook(term, credits);
            if (week != null) outlook = new InvestPages.Outlook(week.bad(), week.typical(), week.good(), week.days());
            return;
        }
        TradeQuote priced = practice ? Investing.practiceQuoteBuyForCredits(server(), self(), picked, credits)
                : Investing.quoteBuyForCredits(server(), self(), picked, credits);
        quote = quoteOf(HubAction.INVEST_QUOTE, credits, priced, false);
        WeekOutlook.Outcome week = Investing.outlook(server(), picked, credits);
        if (week != null) outlook = new InvestPages.Outlook(week.bad(), week.typical(), week.good(), week.days());
    }

    private static InvestPages.Quote quoteOf(HubAction action, long asked, TradeQuote priced, boolean reduced) {
        return new InvestPages.Quote(action, priced.ok() || reduced, priced.reasonKey(), asked, priced.units(),
                priced.unitPriceCents(), priced.total(), priced.fee(), priced.net(), priced.costBasis(), priced.gain(),
                priced.gainPayable(), priced.gainForfeited(), priced.loss(), priced.sellableUnits(),
                priced.earliestSellAt() > 0L ? Math.max(0L, priced.earliestSellAt()
                        - NeroEconomyCommon.CURRENCY_PROVIDER.now()) : 0L, reduced);
    }

    private Component name(String stockId) {
        for (ExchangeViews.Product product : Investing.products(server()))
            if (product.id().equals(stockId) && !product.nameKey().isEmpty()) return Component.translatable(product.nameKey());
        for (ExchangeViews.HoldingView holding : portfolio().holdings())
            if (holding.stockId().equals(stockId) && !holding.nameKey().isEmpty())
                return Component.translatable(holding.nameKey());
        return Component.literal(stockId);
    }

    private static Component duration(long millis) {
        TimeText.Remaining left = TimeText.remaining(millis);
        return Component.translatable(left.key(), left.first(), left.second());
    }

    /** Puts {@code credits} into the picked product: at most that much leaves the balance. */
    void buy(UUID requestId, long credits) {
        clearQuote();
        if (picked == null) {
            say("gui.neroeconomy.hub.invest.pick_first");
            return;
        }
        if (pickedBond) {
            BondTerm term = BondTerm.parse(picked);
            if (term == null) return;
            BondOutcome done = practice ? Investing.practiceBuyBond(server(), self(), term, credits, requestId)
                    : Investing.buyBond(server(), self(), term, credits, requestId);
            if (!done.ok()) {
                say(done.result().translationKey(), 0L, 0L);
                return;
            }
            say("gui.neroeconomy.hub.invest.bond_bought", Component.translatable(term.translationKey()),
                    done.bond().principal(), done.bond().interest(), duration(term.millis()));
            return;
        }
        TradeQuote done = practice ? Investing.practiceBuyForCredits(server(), self(), picked, credits, requestId)
                : Investing.buyForCredits(server(), self(), picked, credits, requestId);
        if (!done.ok()) {
            say(done.result().translationKey(), 0L, 0L);
            return;
        }
        say("command.neroeconomy.invest.bought", done.units(), name(picked), PriceText.credits(done.unitPriceCents()),
                done.total(), done.fee(), done.net());
        long hold = TradingTerms.fromConfig().holdMillis();
        if (hold > 0L) say("command.neroeconomy.invest.hold", duration(hold));
    }

    void sellQuote(HubPages.RowRef row, long units) {
        clearQuote();
        long asked = units == 0L ? Quotes.ALL : units;
        TradeQuote priced = practice ? Investing.practiceQuoteSell(server(), self(), row.item(), asked)
                : Investing.quoteSell(server(), self(), row.item(), asked);
        quote = quoteOf(HubAction.INVEST_SELL_QUOTE, units, priced, priced.result() == TradeResult.GAIN_REDUCED);
    }

    /** @param acceptedGain the reduced gain the player accepted on its own confirm, or -1 for the full gain only */
    void sell(UUID requestId, HubPages.RowRef row, long units, long acceptedGain) {
        clearQuote();
        String stockId = row.item();
        Component name = name(stockId);
        long asked = units == 0L ? Quotes.ALL : units;
        TradeQuote done = practice ? Investing.practiceSell(server(), self(), stockId, asked, requestId)
                : Investing.sell(server(), self(), stockId, asked, acceptedGain, requestId);
        if (!done.ok()) {
            say(done.result().translationKey(), done.gainPayable(), done.gain());
            if (done.result() == TradeResult.HOLDING_PERIOD && done.earliestSellAt() > 0L)
                say("command.neroeconomy.invest.sellable", done.sellableUnits(), duration(done.earliestSellAt() - now()));
            return;
        }
        if (done.loss() > 0L) say("command.neroeconomy.invest.sold_loss", done.units(), name,
                PriceText.credits(done.unitPriceCents()), done.total(), done.loss(), done.fee(), done.net());
        else say("command.neroeconomy.invest.sold_gain", done.units(), name, PriceText.credits(done.unitPriceCents()),
                done.total(), done.costBasis(), done.gainPayable(), done.fee(), done.net());
        if (done.gainForfeited() > 0L)
            say("command.neroeconomy.invest.sold_reduced", done.gainPayable(), done.gain(), done.gainForfeited());
    }

    void redeem(UUID requestId, HubPages.RowRef row) {
        clearQuote();
        BondOutcome done = practice ? Investing.practiceRedeemBond(server(), self(), row.id(), requestId)
                : Investing.redeemBond(server(), self(), row.id(), requestId);
        if (!done.ok()) {
            say(done.result().translationKey(), 0L, 0L);
            return;
        }
        if (done.interest() > 0L || done.paid() > done.bond().principal())
            say("command.neroeconomy.invest.bond.paid_out", done.paid(), done.interest());
        else say("command.neroeconomy.invest.bond.redeemed_early", done.paid(), done.bond().interest());
    }

    void reset(UUID requestId) {
        clearQuote();
        if (!practice) return;
        TradeResult result = Investing.practiceReset(server(), self(), requestId);
        if (!result.ok()) {
            say(result.translationKey(), 0L, 0L);
            return;
        }
        say("command.neroeconomy.invest.practice.reset", Investing.practicePortfolio(server(), self()).playBalance());
    }
}
