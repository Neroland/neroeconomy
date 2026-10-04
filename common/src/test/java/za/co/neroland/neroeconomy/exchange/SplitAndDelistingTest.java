package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.link.EconomyAlerts;

/**
 * Share splits — a price that has grown unwieldy is divided by ten at a day roll and every holding
 * is multiplied by ten, at the cost it had — and the payout of a delisted company's holders.
 */
class SplitAndDelistingTest {
    private static final String CALM = ExchangeWorld.CALM.id();
    private static final String WILD = ExchangeWorld.WILD.id();
    private static final long STEP = ExchangeWorld.STEP;
    /** The first step of the economy-day every test starts in. */
    private static final long FIRST_STEP = ExchangeWorld.FIRST_DAY * 144L;

    private final ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() { exchange.close(); }

    private UUID player(int number, long credits) { return exchange.world.player(number, credits); }
    private long balance(UUID player) { return exchange.world.balance(player); }

    /** One step of the price model with no random move at all. */
    private void step(long step) {
        assertTrue(exchange.exchange().step(exchange.registry, exchange.settings, step, STEP, ExchangeTesting.constant(0.0D)));
    }

    // --- splits ----------------------------------------------------------------------------------

    @Test void aSplitDividesThePriceByTenAndMultipliesEveryHoldingByTenAtTheSameCost() {
        exchange.terms = exchange.terms.withLimits(100_000L, 400_000L, 8).withHold(0L);
        UUID alice = player(1, 100_000L);
        UUID bob = player(2, 100_000L);
        AccountId carol = ExchangeWorld.account(player(3, 100L));
        step(FIRST_STEP);
        exchange.price(WILD, 180_000L);
        exchange.buy(alice, WILD, 3L);
        exchange.advance(ExchangeWorld.HOUR);
        exchange.buy(alice, WILD, 2L);
        exchange.buy(bob, CALM, 10L);
        assertTrue(exchange.practice().buy(exchange.env(), carol, WILD, 4L, ExchangeDesk.ANY_PRICE).ok());
        // The price runs up to 2500.03 credits during the day. Nothing splits in the middle of a day.
        exchange.price(WILD, 250_003L);
        step(FIRST_STEP + 1L);
        StockState state = exchange.exchange().state(WILD);
        assertEquals(0, state.splits());
        long priceBefore = state.price();
        long anchorBefore = state.anchorMicros();
        long startBefore = state.startCents();
        List<Holding.Lot> lots = exchange.holding(alice, WILD).lots();
        long cost = exchange.holding(alice, WILD).cost();
        long held = exchange.exchangeBalance();
        long worthBefore = exchange.holding(alice, WILD).units() * priceBefore;

        // The first step of the next day splits it, before the price moves.
        step(FIRST_STEP + 144L);
        assertEquals(1, state.splits());
        assertEquals(ExchangeWorld.FIRST_DAY + 1L, state.lastSplitDay());
        // The day opens at the split price: a tenth of yesterday's last price, to the nearest hundredth.
        assertTrue(Math.abs(state.dayOpen() * 10L - priceBefore) <= 5L, priceBefore + " became " + state.dayOpen());
        assertTrue(Math.abs(state.anchorMicros() - anchorBefore / 10L) <= anchorBefore / 10L / 500L);
        assertEquals(startBefore / 10L, state.startCents());
        // Every holding has ten times the units, lot for lot, at the cost and the dates it had.
        Holding holding = exchange.holding(alice, WILD);
        assertEquals(50L, holding.units());
        assertEquals(cost, holding.cost());
        for (int index = 0; index < lots.size(); index++) {
            assertEquals(lots.get(index).units() * 10L, holding.lots().get(index).units());
            assertEquals(lots.get(index).cost(), holding.lots().get(index).cost());
            assertEquals(lots.get(index).acquiredAt(), holding.lots().get(index).acquiredAt());
        }
        // Units times price is what it was, to within half a hundredth of a credit per new share.
        long worthAfter = holding.units() * state.dayOpen();
        assertTrue(Math.abs(worthAfter - worthBefore) <= holding.units() / 2L + 1L, worthBefore + " became " + worthAfter);
        // Practice holdings split too; other companies' holdings do not.
        assertEquals(40L, exchange.practice().portfolio(carol).holding(WILD).units());
        assertEquals(10L, exchange.holding(bob, CALM).units());
        assertEquals(0, exchange.exchange().state(CALM).splits());
        // Not a credit moved: Invariant C is what it was.
        assertEquals(held, exchange.exchangeBalance());
        exchange.check();

        // The split shares trade like any others: all fifty sell at the new price.
        exchange.advance(ExchangeWorld.DAY);
        TradeQuote sold = exchange.sell(alice, WILD, Quotes.ALL);
        assertTrue(sold.ok());
        assertEquals(50L, sold.units());
        assertEquals(cost, sold.costBasis());
        assertEquals(ExchangeMath.bid(state.price(), 200), sold.unitPriceCents());
        exchange.check();
        // It does not split again the day after: the price is far below the threshold now.
        step(FIRST_STEP + 288L);
        assertEquals(1, state.splits());
    }

    @Test void aSplitKeepsWhatRoundingToAHundredthLeftOver() {
        step(FIRST_STEP);
        StockState state = exchange.exchange().state(WILD);
        state.price = 200_004L;
        state.carryMicros = 0L;
        state.split(7L);
        // 2000.04 becomes 200.00, and the 0.004 that does not fit a hundredth is carried.
        assertEquals(20_000L, state.price());
        assertEquals(4_000L, state.carryMicros);
        state.price = 200_006L;
        state.carryMicros = 0L;
        state.split(8L);
        // 2000.06 becomes 200.01, carrying the 0.004 it was rounded up by.
        assertEquals(20_001L, state.price());
        assertEquals(-4_000L, state.carryMicros);
        assertEquals(2, state.splits());
        assertEquals(8L, state.lastSplitDay());
    }

    @Test void theChartIsDividedSoItShowsNoCliffAndTheFloorShrinksWithTheShare() {
        step(FIRST_STEP);
        exchange.price(WILD, 260_000L);
        for (long step = 1L; step < 144L; step++) step(FIRST_STEP + step);
        StockState state = exchange.exchange().state(WILD);
        long before = state.price();
        assertTrue(before >= StockState.SPLIT_AT_CENTS);
        step(FIRST_STEP + 144L);
        assertEquals(1, state.splits());
        // Every point of the chart is in today's shares: none is ten times the price.
        for (long point : state.history().recent()) assertTrue(point < before / 5L, "an old price is still in the chart");
        for (long point : state.history().daily()) assertTrue(point < before / 5L);
        // The company listed at 200.00; in today's shares that is 20.00, and the floor is 5 % of it.
        assertEquals(2_000L, state.startCents());
        assertEquals(2_000L, state.splitAdjusted(ExchangeWorld.WILD.startCents()));
        assertEquals(100L, PriceModel.floorCents(state.splitAdjusted(ExchangeWorld.WILD.startCents()),
                exchange.settings.floorBps()));
        // The public quote reports the listing price in today's shares too.
        assertEquals(2_000L, exchange.exchange().quote(WILD, exchange.registry).orElseThrow().startCents());
    }

    @Test void aSplitIsTheSameInEveryRunAndSurvivesASaveAndALoad() {
        ExchangeSection first = new ExchangeSection();
        ExchangeSection second = new ExchangeSection();
        for (ExchangeSection section : List.of(first, second)) {
            byte[] secret = ExchangeTesting.secret(3L);
            section.step(exchange.registry, exchange.settings, FIRST_STEP, STEP, ExchangeTesting.inputs(secret, FIRST_STEP));
            section.state(WILD).price = 400_000L;
            section.state(WILD).dayOpen = 400_000L;
            for (long step = FIRST_STEP + 1L; step <= FIRST_STEP + 150L; step++)
                section.step(exchange.registry, exchange.settings, step, STEP, ExchangeTesting.inputs(secret, step));
        }
        assertEquals(1, first.state(WILD).splits());
        JsonElement one = ExchangeSection.CODEC.encodeStart(JsonOps.INSTANCE, first).getOrThrow();
        assertEquals(one, ExchangeSection.CODEC.encodeStart(JsonOps.INSTANCE, second).getOrThrow());
        ExchangeSection loaded = ExchangeSection.CODEC.parse(JsonOps.INSTANCE, one).getOrThrow();
        assertEquals(1, loaded.state(WILD).splits());
        assertEquals(first.state(WILD).lastSplitDay(), loaded.state(WILD).lastSplitDay());
        assertEquals(one, ExchangeSection.CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow());
    }

    @Test void theIndexFundSplitsAtTwentyThousandCredits() {
        exchange.terms = exchange.terms.withLimits(400_000L, 400_000L, 8).withHold(0L);
        UUID alice = player(1, 400_000L);
        step(FIRST_STEP);
        exchange.indexPrice(1_990_000L);
        exchange.buy(alice, ExchangeWorld.INDEX, 2L);
        long cost = exchange.holding(alice, ExchangeWorld.INDEX).cost();
        // Below the threshold a day roll changes nothing.
        step(FIRST_STEP + 144L);
        assertEquals(0, exchange.exchange().index().splits());
        assertEquals(2L, exchange.holding(alice, ExchangeWorld.INDEX).units());
        exchange.indexPrice(2_400_005L);
        step(FIRST_STEP + 288L);
        assertEquals(1, exchange.exchange().index().splits());
        assertEquals(ExchangeWorld.FIRST_DAY + 2L, exchange.exchange().index().lastSplitDay());
        assertEquals(240_001L, exchange.exchange().index().dayOpen());
        assertEquals(20L, exchange.holding(alice, ExchangeWorld.INDEX).units());
        assertEquals(cost, exchange.holding(alice, ExchangeWorld.INDEX).cost());
        exchange.check();
    }

    // --- delisting -------------------------------------------------------------------------------

    @Test void theHoldersOfADelistedCompanyArePaidOutAtItsLastBidAndTheCompanyIsDropped() {
        UUID alice = player(1, 10_000L);
        UUID bob = player(2, 10_000L);
        AccountId carol = ExchangeWorld.account(player(3, 100L));
        exchange.buy(alice, WILD, 5L);                                           // 1010
        exchange.buy(bob, WILD, 2L);                                             // 404
        exchange.buy(bob, CALM, 3L);
        assertTrue(exchange.practice().buy(exchange.env(), carol, WILD, 4L, ExchangeDesk.ANY_PRICE).ok());
        long play = exchange.practice().balance(exchange.terms, carol);
        exchange.price(WILD, 30_000L);
        long aliceBefore = balance(alice);
        long reserve = exchange.world.reserve();
        exchange.delist(ExchangeWorld.WILD);
        assertEquals(List.of(WILD), exchange.exchange().delisted());
        exchange.advance(ExchangeWorld.MINUTE);

        // Still inside the holding period, and with the exchange switched off: the payout happens all the same.
        exchange.terms = exchange.terms.withEnabled(false);
        assertEquals(2, exchange.exchange().payOutDelisted(exchange.env()));
        // A delisted company is paid out as a steady share: the bid of 300.00 is 298.50. Five fetch 1492.
        assertEquals(1_010L, exchange.rows(alice, TransactionType.SHARE_SELL).get(0).amount());
        assertEquals(482L, exchange.rows(alice, TransactionType.CAPITAL_GAIN).get(0).amount());
        assertEquals(15L, exchange.rows(alice, TransactionType.FEE_EXCHANGE).get(0).amount());
        assertEquals(aliceBefore + 1_492L - 15L, balance(alice));
        assertNull(exchange.holding(alice, WILD));
        assertNull(exchange.holding(bob, WILD));
        assertNotNull(exchange.holding(bob, CALM));
        // Bob's two fetch 597, a gain of 193, less a fee of 6.
        assertEquals(reserve - 482L - 193L + 15L + 6L, exchange.world.reserve());
        // The practice holder is paid in play credits: four fetch 1194, less a fee of 12.
        assertNull(exchange.practice().portfolio(carol).holding(WILD));
        assertEquals(play + 1_194L - 12L, exchange.practice().balance(exchange.terms, carol));
        // Nobody holds it any more: the company is gone, and each real holder was told.
        assertNull(exchange.exchange().state(WILD));
        assertTrue(exchange.exchange().delisted().isEmpty());
        assertEquals(List.of(Map.entry(ExchangeWorld.account(alice), EconomyAlerts.Kind.STOCK_DELISTED),
                Map.entry(ExchangeWorld.account(bob), EconomyAlerts.Kind.STOCK_DELISTED)), exchange.alerts);
        exchange.check();
        assertEquals(0, exchange.exchange().payOutDelisted(exchange.env()));
    }

    @Test void aPayoutAtALossReturnsWhatTheSharesFetchedAndTheLossGoesToTheReserve() {
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, WILD, 5L);
        exchange.price(WILD, 10_000L);
        long before = balance(alice);
        long reserve = exchange.world.reserve();
        exchange.delist(ExchangeWorld.WILD);
        assertEquals(1, exchange.exchange().payOutDelisted(exchange.env()));
        // Five at 99.50 fetch 497 of the 1010 they cost; the fee on 497 is 5.
        assertEquals(before + 497L - 5L, balance(alice));
        assertEquals(reserve + 513L + 5L, exchange.world.reserve());
        assertEquals(0L, exchange.exchangeBalance());
        exchange.check();
    }

    @Test void aPayoutWhoseGainTheBudgetCannotCoverStillReturnsThePrincipalInFull() {
        exchange.terms = exchange.terms.withLimits(100_000L, 400_000L, 8);
        UUID alice = player(1, 100_000L);
        exchange.buy(alice, WILD, 300L);                                         // 60600
        exchange.price(WILD, 40_000L);
        exchange.delist(ExchangeWorld.WILD);
        long before = balance(alice);
        assertEquals(1, exchange.exchange().payOutDelisted(exchange.env()));
        // 300 at 398.00 fetch 119400: a gain of 58800, of which today's budget pays 15000. Nobody is asked.
        assertEquals(60_600L, exchange.rows(alice, TransactionType.SHARE_SELL).get(0).amount());
        assertEquals(15_000L, exchange.rows(alice, TransactionType.CAPITAL_GAIN).get(0).amount());
        assertEquals(before + 60_600L + 15_000L - 756L, balance(alice));
        assertEquals(0L, exchange.budgetLeft());
        assertEquals(0L, exchange.exchangeBalance());
        exchange.check();
    }

    @Test void theSimulationPaysADelistingOutByItselfAndACompanyThatComesBackIsNotPaidOut() {
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, WILD, 5L);
        exchange.buy(alice, CALM, 5L);
        long before = balance(alice);
        // The file comes back before any step ran: the company carries on, and nobody is paid out.
        StockRegistry all = exchange.registry;
        exchange.delist(ExchangeWorld.WILD);
        exchange.registry = all;
        exchange.exchange().reconcile(all);
        exchange.advance(STEP);
        exchange.runDueSteps();
        assertEquals(5L, exchange.holding(alice, WILD).units());
        assertEquals(before, balance(alice));
        // Gone for good: the next step pays its holders, online or not.
        exchange.delist(ExchangeWorld.WILD);
        exchange.advance(STEP);
        exchange.runDueSteps();
        assertNull(exchange.holding(alice, WILD));
        assertTrue(balance(alice) > before);
        assertNull(exchange.exchange().state(WILD));
        assertEquals(5L, exchange.holding(alice, CALM).units());
        assertFalse(exchange.alerts.isEmpty());
        exchange.check();
    }

    @Test void aDelistedCompanyIsKeptForAsLongAsSomebodyHoldsIt() {
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, WILD, 5L);
        exchange.delist(ExchangeWorld.WILD);
        assertFalse(exchange.exchange().removeDelisted(WILD));
        assertNotNull(exchange.exchange().state(WILD));
        exchange.exchange().forgetPlayer(alice);
        assertTrue(exchange.exchange().removeDelisted(WILD));
    }
}
