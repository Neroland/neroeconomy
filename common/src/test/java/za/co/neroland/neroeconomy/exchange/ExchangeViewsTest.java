package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.config.EconomyConfig;

/**
 * What a screen or the companion app is given: the market list with ask and bid, a portfolio with
 * what was put in and what it is worth, the practice equivalents — and the published defaults of
 * the trading terms.
 */
class ExchangeViewsTest {
    private static final String CALM = ExchangeWorld.CALM.id();
    private static final String WILD = ExchangeWorld.WILD.id();

    private final ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() { exchange.close(); }

    private ExchangeViews.Product product(String id) {
        return ExchangeViews.products(exchange.exchange(), exchange.env()).stream()
                .filter(product -> product.id().equals(id)).findFirst().orElseThrow();
    }

    @Test void theMarketListShowsWhatEachProductCostsToBuyAndFetchesWhenSold() {
        List<ExchangeViews.Product> products = ExchangeViews.products(exchange.exchange(), exchange.env());
        assertEquals(List.of(CALM, ExchangeWorld.PLAIN.id(), WILD, ExchangeWorld.INDEX),
                products.stream().map(ExchangeViews.Product::id).toList());
        ExchangeViews.Product calm = product(CALM);
        assertEquals("stock.test.calm", calm.nameKey());
        assertEquals(StockType.STEADY, calm.type());
        assertEquals(10_000L, calm.priceCents());
        assertEquals(10_050L, calm.askCents());
        assertEquals(9_950L, calm.bidCents());
        assertEquals(140, calm.dividendBasisPoints());
        assertEquals(ExchangeViews.RISK_STEADY, calm.riskKey());
        assertTrue(calm.tradeable());
        assertFalse(calm.index());
        ExchangeViews.Product wild = product(WILD);
        assertEquals(20_200L, wild.askCents());
        assertEquals(19_800L, wild.bidCents());
        assertEquals(ExchangeViews.RISK_RISKY, wild.riskKey());
        ExchangeViews.Product index = product(ExchangeWorld.INDEX);
        assertTrue(index.index());
        assertEquals(IndexState.NAME_KEY, index.nameKey());
        assertEquals(10_050L, index.askCents());
        assertEquals(ExchangeViews.RISK_INDEX, index.riskKey());
        assertEquals(0, index.dividendBasisPoints());
        // A halted share and a delisted one say so, in a lang key.
        exchange.halt(CALM);
        assertEquals("message.neroeconomy.invest.halted", product(CALM).statusKey());
        exchange.delist(ExchangeWorld.WILD);
        assertEquals("message.neroeconomy.invest.delisted", product(WILD).statusKey());
        assertFalse(product(WILD).tradeable());

        List<ExchangeViews.BondOffer> bonds = ExchangeViews.bonds(exchange.terms);
        assertEquals(List.of("1d", "3d", "7d"), bonds.stream().map(ExchangeViews.BondOffer::id).toList());
        assertEquals(800, bonds.get(2).rateBasisPoints());
        assertEquals(7, bonds.get(2).days());
        assertEquals(ExchangeViews.RISK_BOND, bonds.get(2).riskKey());
    }

    @Test void aPortfolioShowsWhatWasPutInWhatItIsWorthAndTheDifference() {
        UUID alice = exchange.world.player(1, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        ExchangeViews.PortfolioView empty = ExchangeViews.portfolio(exchange.exchange(), exchange.env(), account,
                exchange.now());
        assertTrue(empty.isEmpty());
        assertFalse(empty.practice());
        assertEquals(20_000L, empty.portfolioLimit());
        assertEquals(5_000L, empty.positionLimit());
        assertEquals(8, empty.bondLimit());

        long bought = exchange.now();
        exchange.buy(alice, CALM, 10L);                                          // 1005
        exchange.advance(ExchangeWorld.HOUR);
        exchange.buy(alice, CALM, 4L);                                           // 402
        exchange.buy(alice, WILD, 2L);                                           // 404
        Bond bond = exchange.desk().buyBond(exchange.env(), account, BondTerm.SEVEN_DAYS, 1_000L, null).bond();
        exchange.price(CALM, 12_000L);
        exchange.price(WILD, 15_000L);

        ExchangeViews.PortfolioView view = ExchangeViews.portfolio(exchange.exchange(), exchange.env(), account,
                exchange.now());
        assertEquals(2, view.holdings().size());
        ExchangeViews.HoldingView calm = view.holdings().get(0);
        assertEquals(CALM, calm.stockId());
        assertEquals("stock.test.calm", calm.nameKey());
        assertEquals(14L, calm.units());
        assertEquals(1_407L, calm.cost());
        // 14 units at the bid of 119.40.
        assertEquals(11_940L, calm.bidCents());
        assertEquals(1_671L, calm.value());
        assertEquals(264L, calm.difference());
        // The first ten are an hour old; the four bought just now wait their hour.
        assertEquals(10L, calm.sellableUnits());
        assertEquals(bought + 2L * ExchangeWorld.HOUR, calm.earliestSellAt());
        assertEquals("", calm.statusKey());
        ExchangeViews.HoldingView wild = view.holdings().get(1);
        // 2 units at the bid of 148.50: worth 297 of the 404 they cost.
        assertEquals(297L, wild.value());
        assertEquals(-107L, wild.difference());
        assertEquals(0L, wild.sellableUnits());

        assertEquals(1_811L, view.cost());
        assertEquals(1_968L, view.value());
        assertEquals(157L, view.difference());
        assertEquals(1_000L, view.bondPrincipal());
        assertEquals(80L, view.bondInterest());
        assertEquals(2_811L, view.invested());
        assertEquals(1, view.bonds().size());
        ExchangeViews.BondView held = view.bonds().get(0);
        assertEquals(bond.id(), held.id());
        assertEquals("7d", held.term());
        assertEquals(7, held.days());
        assertEquals(bond.maturesAt(), held.maturesAt());
        assertFalse(held.matured());
        assertFalse(view.dividends().happened());
        assertEquals(0L, view.playBalance());
        assertFalse(view.canReset());
        // A view is a copy: nothing done to it reaches the exchange.
        assertEquals(14L, exchange.holding(alice, CALM).units());
    }

    @Test void thePracticeViewHasTheSameShapeInPlayCredits() {
        UUID alice = exchange.world.player(1, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        ExchangeViews.PortfolioView fresh = ExchangeViews.practice(exchange.exchange(), exchange.env(), account,
                exchange.now());
        assertTrue(fresh.practice());
        assertTrue(fresh.isEmpty());
        assertEquals(10_000L, fresh.playBalance());
        assertTrue(fresh.canReset());

        exchange.practice().buy(exchange.env(), account, CALM, 10L, ExchangeDesk.ANY_PRICE);
        exchange.practice().buyBond(exchange.env(), account, BondTerm.ONE_DAY, 500L, null);
        ExchangeViews.PortfolioView view = ExchangeViews.practice(exchange.exchange(), exchange.env(), account,
                exchange.now());
        assertEquals(10_000L - 1_016L - 500L, view.playBalance());
        assertEquals(1_005L, view.cost());
        assertEquals(995L, view.value());
        assertEquals(-10L, view.difference());
        assertEquals(500L, view.bondPrincipal());
        assertEquals(1_505L, view.invested());
        // The real portfolio of the same account is still empty.
        assertTrue(ExchangeViews.portfolio(exchange.exchange(), exchange.env(), account, exchange.now()).isEmpty());
        exchange.practice().reset(exchange.env(), account);
        assertFalse(ExchangeViews.practice(exchange.exchange(), exchange.env(), account, exchange.now()).canReset());
    }

    @Test void theDefaultsOfTheTradingTermsAreThePublishedOnes() {
        TradingTerms terms = TradingTerms.DEFAULTS;
        assertTrue(terms.enabled());
        assertEquals(100, terms.feeBps());
        assertEquals(100, terms.spreadSteadyBps());
        assertEquals(200, terms.spreadRiskyBps());
        assertEquals(100, terms.spreadIndexBps());
        assertEquals(3_600_000L, terms.holdMillis());
        assertEquals(5_000L, terms.maxPositionPerStock());
        assertEquals(20_000L, terms.maxPortfolio());
        assertEquals(8, terms.maxBonds());
        assertEquals(10_000L, terms.practiceStart());
        assertEquals(20, terms.actionsPerMinute());
        // The configuration, which is at its defaults in a unit test, gives exactly these terms.
        assertEquals(terms, TradingTerms.fromConfig());
        assertEquals(60, (int) EconomyConfig.SHARE_HOLD_MINUTES.get());
        assertEquals(5_000L, (long) EconomyConfig.MAX_POSITION_PER_STOCK.get());
        assertEquals(20_000L, (long) EconomyConfig.MAX_PORTFOLIO.get());
        assertEquals(8, (int) EconomyConfig.MAX_BONDS_PER_PLAYER.get());
        assertEquals(10_000L, (long) EconomyConfig.PRACTICE_STARTING_CREDITS.get());
        assertEquals(20, (int) EconomyConfig.EXCHANGE_ACTIONS_PER_MINUTE.get());
        assertEquals(1.0D, EconomyConfig.EXCHANGE_SPREAD_STEADY_PERCENT.get(), 0.0D);
        assertEquals(2.0D, EconomyConfig.EXCHANGE_SPREAD_RISKY_PERCENT.get(), 0.0D);
        assertEquals(1.0D, EconomyConfig.EXCHANGE_SPREAD_INDEX_PERCENT.get(), 0.0D);
        assertEquals(40.0D, EconomyConfig.EXCHANGE_DAY_LIMIT_RISKY_PERCENT.get(), 0.0D);
    }

    @Test void theRateLimiterCountsPerAccountPerMinuteAndForgets() {
        ActionLimiter limiter = new ActionLimiter();
        AccountId alice = ExchangeWorld.account(new UUID(1L, 1L));
        AccountId bob = ExchangeWorld.account(new UUID(1L, 2L));
        assertTrue(limiter.allow(alice, 1_000L, 2));
        assertTrue(limiter.allow(alice, 30_000L, 2));
        assertFalse(limiter.allow(alice, 60_999L, 2));
        assertTrue(limiter.allow(bob, 60_999L, 2));
        // The minute is counted from the account's first action.
        assertTrue(limiter.allow(alice, 61_000L, 2));
        assertFalse(limiter.allow(null, 61_000L, 2));
        assertEquals(2, limiter.tracked());
        limiter.forget(alice);
        assertEquals(1, limiter.tracked());
        assertTrue(limiter.allow(alice, 61_001L, 1));
        assertFalse(limiter.allow(alice, 61_002L, 1));
        limiter.clear();
        assertEquals(0, limiter.tracked());
        // Full of live windows, it lets nobody new in — and makes room as soon as old ones run out.
        for (int account = 0; account < ActionLimiter.MAX_TRACKED; account++)
            assertTrue(limiter.allow(ExchangeWorld.account(new UUID(2L, account)), 5_000L, 5));
        assertFalse(limiter.allow(alice, 5_001L, 5));
        assertTrue(limiter.allow(alice, 65_000L, 5));
        assertEquals(1, limiter.tracked());
    }
}
