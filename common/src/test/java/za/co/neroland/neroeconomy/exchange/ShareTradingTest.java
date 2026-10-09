package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * Buying and selling shares and the index fund with real credits: the arithmetic, where every
 * credit goes, the holding period, the limits, every refusal, and Invariant C after each of them.
 */
class ShareTradingTest {
    private static final String CALM = ExchangeWorld.CALM.id();
    private static final String PLAIN = ExchangeWorld.PLAIN.id();
    private static final String WILD = ExchangeWorld.WILD.id();

    private final ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() {
        EconomyHealth.clear();
        exchange.close();
    }

    private UUID player(int number, long credits) { return exchange.world.player(number, credits); }
    private long balance(UUID player) { return exchange.world.balance(player); }
    private long reserve() { return exchange.world.reserve(); }

    /** The same terms with no holding period, for tests that are about something else. */
    private void noHold() { exchange.terms = exchange.terms.withHold(0L); }

    // --- buying ----------------------------------------------------------------------------------

    @Test void aPurchasePaysTheAskRoundedUpPlusTheFeeAndThePrincipalSitsInTheExchangeAccount() {
        UUID alice = player(1, 10_000L);
        long reserve = reserve();
        TradeQuote quote = exchange.desk().quoteBuy(exchange.env(), ExchangeWorld.account(alice), CALM, 10L);
        // 100.00 at a 1 % spread: the ask is 100.50. Ten units cost 1005; 1 % of that, rounded up, is 11.
        assertTrue(quote.ok());
        assertEquals(TradeSide.BUY, quote.side());
        assertEquals(10_050L, quote.unitPriceCents());
        assertEquals(1_005L, quote.total());
        assertEquals(11L, quote.fee());
        assertEquals(1_016L, quote.net());
        assertEquals("", quote.reasonKey());
        // A quote moves nothing.
        assertEquals(10_000L, balance(alice));
        assertEquals(0L, exchange.exchangeBalance());

        TradeQuote done = exchange.buy(alice, CALM, 10L);
        assertEquals(quote, done);
        assertEquals(10_000L - 1_016L, balance(alice));
        assertEquals(1_005L, exchange.exchangeBalance());
        assertEquals(reserve + 11L, reserve());
        assertEquals(List.of(new Holding.Lot(10L, 1_005L, exchange.now())), exchange.holding(alice, CALM).lots());
        assertEquals(1_005L, exchange.rows(alice, TransactionType.SHARE_BUY).get(0).amount());
        assertEquals(11L, exchange.rows(alice, TransactionType.FEE_EXCHANGE).get(0).amount());
        exchange.check();
    }

    @Test void buyingForAnAmountBuysAsManyWholeUnitsAsItCoversFeeIncluded() {
        UUID alice = player(1, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        TradeQuote done = exchange.desk().buyForCredits(exchange.env(), account, CALM, 1_016L);
        assertTrue(done.ok());
        assertEquals(10L, done.units());
        assertEquals(1_016L, done.net());
        // One credit less covers nine: 905 and a fee of 10.
        TradeQuote nine = exchange.desk().quoteBuyForCredits(exchange.env(), account, CALM, 1_015L);
        assertEquals(9L, nine.units());
        assertEquals(915L, nine.net());
        // Less than one unit and its fee is refused before anything moves.
        TradeQuote none = exchange.desk().buyForCredits(exchange.env(), account, CALM, 100L);
        assertEquals(TradeResult.TOO_SMALL, none.result());
        assertEquals("message.neroeconomy.invest.too_small", none.reasonKey());
        assertEquals(10_000L - 1_016L, balance(alice));
        exchange.check();
    }

    @Test void aPurchaseIsRefusedWhenThePriceHasRisenPastWhatWasAgreedTo() {
        UUID alice = player(1, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        long agreed = exchange.desk().quoteBuy(exchange.env(), account, CALM, 10L).net();
        exchange.price(CALM, 10_100L);
        TradeQuote moved = exchange.desk().buy(exchange.env(), account, CALM, 10L, agreed);
        assertEquals(TradeResult.PRICE_MOVED, moved.result());
        // The refusal carries the new price, so the screen can show it.
        assertEquals(10_151L, moved.unitPriceCents());
        assertEquals(10_000L, balance(alice));
        assertNull(exchange.holding(alice, CALM));
        // At the old price, or a lower one, it goes through.
        exchange.price(CALM, 9_900L);
        assertTrue(exchange.desk().buy(exchange.env(), account, CALM, 10L, agreed).ok());
        exchange.check();
    }

    @Test void theStartingBalanceLockStopsAPurchaseButMayPayTheFee() {
        UUID newcomer = exchange.world.newcomer(5);
        assertEquals(100L, exchange.ledger().locked(newcomer));
        // One unit at 50.25 costs 51, and a fee of 1. Locked starting credits do not buy shares (JF1 A1):
        // sold again, they would come back unlocked.
        TradeQuote refused = exchange.buy(newcomer, PLAIN, 1L);
        assertEquals(TradeResult.FUNDS_LOCKED, refused.result());
        assertEquals(100L, balance(newcomer));
        assertEquals(100L, exchange.ledger().locked(newcomer));
        // With 51 earned credits the shares are paid from those, and the fee from the locked part.
        exchange.ledger().deposit(newcomer, NeroEconomyCommon.CREDITS, 51L, TransactionType.ADMIN_GRANT);
        TradeQuote done = exchange.buy(newcomer, PLAIN, 1L);
        assertTrue(done.ok());
        assertEquals(52L, done.net());
        assertEquals(99L, balance(newcomer));
        assertEquals(99L, exchange.ledger().locked(newcomer));
        exchange.check();
    }

    @Test void aPurchaseNeedsTheCreditsForThePrincipalAndTheFee() {
        UUID alice = player(1, 1_015L);
        TradeQuote refused = exchange.buy(alice, CALM, 10L);
        assertEquals(TradeResult.INSUFFICIENT_FUNDS, refused.result());
        assertEquals(1_016L, refused.net());
        assertEquals(1_015L, balance(alice));
        assertEquals(0L, exchange.exchangeBalance());
        assertTrue(exchange.desk().isEmpty());
        exchange.check();
    }

    // --- selling ---------------------------------------------------------------------------------

    @Test void aSaleAtAGainReturnsThePrincipalAndTheReservePaysTheGainWithinTheBudget() {
        noHold();
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, CALM, 10L);
        long reserve = reserve();
        long budget = exchange.budgetLeft();
        exchange.price(CALM, 12_000L);
        // The bid is 119.40: ten units fetch 1194, 189 more than they cost. The fee is 1 % of 1194, rounded up.
        TradeQuote done = exchange.sell(alice, CALM, 10L);
        assertTrue(done.ok());
        assertEquals(TradeSide.SELL, done.side());
        assertEquals(11_940L, done.unitPriceCents());
        assertEquals(1_194L, done.total());
        assertEquals(1_005L, done.costBasis());
        assertEquals(189L, done.gain());
        assertEquals(189L, done.gainPayable());
        assertEquals(0L, done.gainForfeited());
        assertEquals(0L, done.loss());
        assertEquals(12L, done.fee());
        assertEquals(1_182L, done.net());
        assertEquals(10_000L - 1_016L + 1_182L, balance(alice));
        assertEquals(0L, exchange.exchangeBalance());
        assertEquals(reserve - 189L + 12L, reserve());
        assertEquals(budget - 189L, exchange.budgetLeft());
        assertEquals(1_005L, exchange.rows(alice, TransactionType.SHARE_SELL).get(0).amount());
        assertEquals(189L, exchange.rows(alice, TransactionType.CAPITAL_GAIN).get(0).amount());
        assertNull(exchange.desk().portfolio(ExchangeWorld.account(alice)));
        exchange.check();
    }

    @Test void aSaleAtALossPaysOnlyWhatTheUnitsFetchedAndTheLossGoesToTheReserve() {
        noHold();
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, CALM, 10L);
        long reserve = reserve();
        long budget = exchange.budgetLeft();
        exchange.price(CALM, 8_000L);
        // The bid is 79.60: ten units fetch 796, 209 less than they cost.
        TradeQuote done = exchange.sell(alice, CALM, 10L);
        assertTrue(done.ok());
        assertEquals(796L, done.total());
        assertEquals(209L, done.loss());
        assertEquals(0L, done.gain());
        assertEquals(8L, done.fee());
        assertEquals(788L, done.net());
        assertEquals(10_000L - 1_016L + 788L, balance(alice));
        assertEquals(0L, exchange.exchangeBalance());
        assertEquals(reserve + 209L + 8L, reserve());
        // A loss uses none of the budget.
        assertEquals(budget, exchange.budgetLeft());
        assertEquals(796L, exchange.rows(alice, TransactionType.SHARE_SELL).get(0).amount());
        assertTrue(exchange.rows(alice, TransactionType.CAPITAL_GAIN).isEmpty());
        exchange.check();
    }

    @Test void sellingPartOfAHoldingLeavesTheRestWithItsOwnCost() {
        noHold();
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, CALM, 10L);
        exchange.price(CALM, 11_000L);
        exchange.buy(alice, CALM, 4L);
        // 4 units at 110.55 cost 443 (442.2 rounded up).
        assertEquals(1_005L + 443L, exchange.exchangeBalance());
        // Twelve units: the ten oldest and two of the four, whose share of 443 is 221.
        TradeQuote done = exchange.sell(alice, CALM, 12L);
        assertEquals(1_005L + 221L, done.costBasis());
        assertEquals(List.of(new Holding.Lot(2L, 222L, exchange.now())), exchange.holding(alice, CALM).lots());
        assertEquals(222L, exchange.exchangeBalance());
        exchange.check();
        // "All" sells what is left.
        TradeQuote rest = exchange.sell(alice, CALM, Quotes.ALL);
        assertEquals(2L, rest.units());
        assertEquals(0L, exchange.exchangeBalance());
        exchange.check();
    }

    @Test void aGainTheBudgetCannotCoverIsRefusedUnlessTheLowerAmountIsAccepted() {
        noHold();
        exchange.terms = exchange.terms.withLimits(100_000L, 200_000L, 8);
        UUID alice = player(1, 100_000L);
        AccountId account = ExchangeWorld.account(alice);
        assertTrue(exchange.buy(alice, CALM, 600L).ok());
        long afterBuying = balance(alice);
        assertEquals(60_300L, exchange.exchangeBalance());
        exchange.price(CALM, 15_000L);
        // 600 units at 149.25 fetch 89550: a gain of 29250, and today's exchange budget is 15000.
        long budget = exchange.budgetLeft();
        assertEquals(15_000L, budget);
        TradeQuote quote = exchange.desk().quoteSell(exchange.env(), account, CALM, 600L);
        assertEquals(TradeResult.GAIN_REDUCED, quote.result());
        assertEquals("message.neroeconomy.invest.gain_reduced", quote.reasonKey());
        assertEquals(29_250L, quote.gain());
        assertEquals(15_000L, quote.gainPayable());
        assertEquals(14_250L, quote.gainForfeited());
        // The principal is in the quote in full, and the fee is on what would actually be received.
        assertEquals(60_300L, quote.costBasis());
        assertEquals(753L, quote.fee());
        assertEquals(60_300L + 15_000L - 753L, quote.net());

        // Without the acceptance nothing is sold and nothing moves.
        TradeQuote refused = exchange.sell(alice, CALM, 600L);
        assertEquals(TradeResult.GAIN_REDUCED, refused.result());
        assertEquals(afterBuying, balance(alice));
        assertEquals(600L, exchange.holding(alice, CALM).units());
        assertEquals(budget, exchange.budgetLeft());
        // Accepting more than can be paid is not an acceptance of what can be.
        TradeQuote changed = exchange.desk().sell(exchange.env(), account, CALM, 600L, 15_001L);
        assertEquals(TradeResult.GAIN_CHANGED, changed.result());
        assertEquals(15_000L, changed.gainPayable());
        assertEquals(afterBuying, balance(alice));
        exchange.check();

        // Selling fewer shares is paid in full: 300 units fetch 44775, a gain of 14625.
        TradeQuote fewer = exchange.sell(alice, CALM, 300L);
        assertTrue(fewer.ok());
        assertEquals(14_625L, fewer.gainPayable());
        assertEquals(0L, fewer.gainForfeited());
        assertEquals(375L, exchange.budgetLeft());
        exchange.check();

        // The rest has a gain of 14625 too, of which 375 can still be paid today. Accepted: it sells.
        long reserve = reserve();
        long before = balance(alice);
        TradeQuote accepted = exchange.desk().sell(exchange.env(), account, CALM, 300L, 375L);
        assertTrue(accepted.ok());
        assertEquals(14_625L, accepted.gain());
        assertEquals(375L, accepted.gainPayable());
        assertEquals(14_250L, accepted.gainForfeited());
        // The principal came back in full, whatever happened to the gain.
        assertEquals(30_150L, accepted.costBasis());
        assertEquals(306L, accepted.fee());
        assertEquals(before + 30_150L + 375L - 306L, balance(alice));
        assertEquals(reserve - 375L + 306L, reserve());
        assertEquals(0L, exchange.budgetLeft());
        assertEquals(0L, exchange.exchangeBalance());
        exchange.check();
    }

    @Test void aGainTheReserveItselfCannotCoverIsHandledTheSameWay() {
        noHold();
        UUID alice = player(1, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        exchange.buy(alice, CALM, 10L);
        exchange.price(CALM, 12_000L);
        // An operator empties the Reserve down to 50 credits.
        assertTrue(exchange.ledger().withdraw(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS, reserve() - 50L,
                TransactionType.ADMIN_TAKE));
        TradeQuote quote = exchange.desk().quoteSell(exchange.env(), account, CALM, 10L);
        assertEquals(TradeResult.GAIN_REDUCED, quote.result());
        assertEquals(50L, quote.gainPayable());
        assertEquals(139L, quote.gainForfeited());
        assertEquals(TradeResult.GAIN_REDUCED, exchange.sell(alice, CALM, 10L).result());
        TradeQuote accepted = exchange.desk().sell(exchange.env(), account, CALM, 10L, quote.gainPayable());
        assertTrue(accepted.ok());
        // 1005 back, 50 of the gain, less a fee of 11 on the 1055 received.
        assertEquals(1_005L + 50L - 11L, accepted.net());
        assertEquals(11L, reserve());
        exchange.check();
    }

    @Test void whenMoreCanBePaidThanWasAcceptedTheSellerGetsTheMore() {
        noHold();
        exchange.terms = exchange.terms.withLimits(100_000L, 200_000L, 8);
        UUID alice = player(1, 100_000L);
        exchange.buy(alice, CALM, 600L);
        exchange.price(CALM, 15_000L);
        // Accepted 10000 of the gain, and 15000 can be paid: 15000 is paid.
        TradeQuote done = exchange.desk().sell(exchange.env(), ExchangeWorld.account(alice), CALM, 600L, 10_000L);
        assertTrue(done.ok());
        assertEquals(15_000L, done.gainPayable());
        exchange.check();
    }

    // --- the holding period ----------------------------------------------------------------------

    @Test void aLotCannotBeSoldForAnHourAfterItWasBought() {
        UUID alice = player(1, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        long bought = exchange.now();
        exchange.buy(alice, CALM, 10L);
        exchange.advance(59L * ExchangeWorld.MINUTE);
        TradeQuote early = exchange.sell(alice, CALM, 10L);
        assertEquals(TradeResult.HOLDING_PERIOD, early.result());
        assertEquals(0L, early.sellableUnits());
        assertEquals(bought + ExchangeWorld.HOUR, early.earliestSellAt());
        assertEquals(TradeResult.HOLDING_PERIOD, exchange.sell(alice, CALM, Quotes.ALL).result());
        exchange.advance(ExchangeWorld.MINUTE);
        assertEquals(bought + ExchangeWorld.HOUR, exchange.now());
        // On the hour the first ten may be sold.
        TradeQuote quote = exchange.desk().quoteSell(exchange.env(), account, CALM, Quotes.ALL);
        assertTrue(quote.ok());
        assertEquals(10L, quote.units());
        assertEquals(0L, quote.earliestSellAt());
        // A second purchase is held back for its own hour, and does not hold the first back.
        exchange.buy(alice, CALM, 5L);
        assertEquals(TradeResult.HOLDING_PERIOD, exchange.sell(alice, CALM, 11L).result());
        TradeQuote all = exchange.sell(alice, CALM, Quotes.ALL);
        assertTrue(all.ok());
        assertEquals(10L, all.units());
        assertEquals(5L, exchange.holding(alice, CALM).units());
        // The holding period can be switched off.
        noHold();
        assertTrue(exchange.sell(alice, CALM, 5L).ok());
        exchange.check();
    }

    @Test void tooManyPurchasesInsideTheHoldingPeriodAreToldToWaitForTheFirstToSettle() {
        UUID alice = player(1, 10_000L);
        for (int purchase = 0; purchase < Holding.MAX_YOUNG_LOTS; purchase++) {
            assertTrue(exchange.buy(alice, CALM, 1L).ok(), "purchase " + purchase);
            exchange.advance(5L * ExchangeWorld.MINUTE);
        }
        long before = balance(alice);
        TradeQuote refused = exchange.buy(alice, CALM, 1L);
        assertEquals(TradeResult.SETTLING, refused.result());
        assertEquals("message.neroeconomy.invest.settling", refused.reasonKey());
        assertEquals(before, balance(alice));
        // Another stock is not affected.
        assertTrue(exchange.buy(alice, PLAIN, 1L).ok());
        // Once the first purchase is an hour old there is room again, and the old lots are merged.
        exchange.advance(25L * ExchangeWorld.MINUTE);
        assertTrue(exchange.buy(alice, CALM, 1L).ok());
        assertTrue(exchange.holding(alice, CALM).lotCount() <= Holding.MAX_YOUNG_LOTS + 1);
        assertEquals(9L, exchange.holding(alice, CALM).units());
        exchange.check();
    }

    // --- limits ----------------------------------------------------------------------------------

    @Test void aPositionIsCappedAtCostPerStock() {
        UUID alice = player(1, 50_000L);
        // 49 units at 100.50 cost 4925 (4924.5 rounded up): inside the 5000 a stock may hold.
        assertTrue(exchange.buy(alice, CALM, 49L).ok());
        long before = balance(alice);
        // One more unit costs 101: past it.
        assertEquals(TradeResult.POSITION_LIMIT, exchange.buy(alice, CALM, 1L).result());
        assertEquals(before, balance(alice));
        // The limit is on what was paid, not on what it is worth: a price that has halved does not make room…
        exchange.price(CALM, 5_000L);
        assertEquals(TradeResult.POSITION_LIMIT, exchange.buy(alice, CALM, 2L).result());
        // …but exactly up to the limit is allowed: one unit at 50.25 costs 51.
        assertTrue(exchange.buy(alice, CALM, 1L).ok());
        assertEquals(4_976L, exchange.holding(alice, CALM).cost());
        // Another stock has its own limit.
        assertTrue(exchange.buy(alice, WILD, 10L).ok());
        exchange.check();
    }

    @Test void thePortfolioIsCappedAtCostAcrossSharesIndexAndBonds() {
        exchange.terms = exchange.terms.withLimits(5_000L, 6_000L, 8);
        UUID alice = player(1, 50_000L);
        AccountId account = ExchangeWorld.account(alice);
        assertTrue(exchange.buy(alice, CALM, 20L).ok());                      // 2010
        assertTrue(exchange.buy(alice, ExchangeWorld.INDEX, 10L).ok());       // 1005
        assertTrue(exchange.desk().buyBond(exchange.env(), account, BondTerm.ONE_DAY, 2_000L, null).ok());
        // 5015 in: a risky share at 202 fits four times (808) and not five (1010).
        assertEquals(TradeResult.PORTFOLIO_LIMIT, exchange.buy(alice, WILD, 5L).result());
        assertEquals(TradeResult.PORTFOLIO_LIMIT,
                exchange.desk().buyBond(exchange.env(), account, BondTerm.ONE_DAY, 986L, null).result());
        assertTrue(exchange.buy(alice, WILD, 4L).ok());
        assertEquals(5_823L, exchange.desk().portfolio(account).invested());
        exchange.check();
    }

    // --- refusals --------------------------------------------------------------------------------

    @Test void nothingTradesWhileAStockIsHaltedOrDelistedOrTheExchangeIsOffOrTheEconomyFrozen() {
        noHold();
        UUID alice = player(1, 10_000L);
        UUID bob = player(2, 100L);
        AccountId account = ExchangeWorld.account(alice);
        exchange.buy(alice, CALM, 5L);
        exchange.buy(alice, WILD, 5L);
        JsonElement ledger = exchange.ledgerSnapshot();
        JsonElement section = exchange.exchangeSnapshot();

        exchange.halt(CALM);
        assertEquals(TradeResult.HALTED, exchange.buy(alice, CALM, 1L).result());
        assertEquals(TradeResult.HALTED, exchange.sell(alice, CALM, 1L).result());
        assertEquals(TradeResult.HALTED, exchange.desk().quoteBuy(exchange.env(), account, CALM, 1L).result());
        assertEquals("message.neroeconomy.invest.halted", exchange.sell(alice, CALM, Quotes.ALL).reasonKey());
        // A halt on one stock stops no other.
        assertTrue(exchange.desk().quoteSell(exchange.env(), account, WILD, 1L).ok());
        exchange.exchange().state(CALM).haltedUntil = StockState.NEVER;

        exchange.terms = exchange.terms.withEnabled(false);
        assertEquals(TradeResult.EXCHANGE_OFF, exchange.buy(alice, CALM, 1L).result());
        assertEquals(TradeResult.EXCHANGE_OFF, exchange.sell(alice, CALM, 1L).result());
        assertEquals(TradeResult.EXCHANGE_OFF, exchange.desk().buyForCredits(exchange.env(), account, CALM, 500L).result());
        assertEquals(TradeResult.EXCHANGE_OFF,
                exchange.desk().buyBond(exchange.env(), account, BondTerm.ONE_DAY, 100L, null).result());
        exchange.terms = exchange.terms.withEnabled(true);

        EconomyHealth.freeze();
        assertEquals(TradeResult.FROZEN, exchange.buy(alice, CALM, 1L).result());
        assertEquals(TradeResult.FROZEN, exchange.sell(alice, CALM, 1L).result());
        assertEquals(EconomyHealth.FROZEN_KEY, exchange.sell(alice, CALM, 1L).reasonKey());
        assertEquals(TradeResult.FROZEN, exchange.desk().quoteBuy(exchange.env(), account, CALM, 1L).result());
        EconomyHealth.clear();

        assertEquals(TradeResult.UNKNOWN_STOCK, exchange.buy(alice, "test:nothing", 1L).result());
        assertEquals(TradeResult.UNKNOWN_STOCK, exchange.sell(alice, "test:nothing", 1L).result());
        assertEquals(TradeResult.INVALID, exchange.buy(alice, CALM, 0L).result());
        assertEquals(TradeResult.INVALID, exchange.buy(alice, CALM, -4L).result());
        assertEquals(TradeResult.INVALID, exchange.buy(alice, CALM, Holding.MAX_UNITS + 1L).result());
        assertEquals(TradeResult.INVALID, exchange.sell(alice, CALM, 0L).result());
        assertEquals(TradeResult.INVALID, exchange.desk().buy(exchange.env(), SystemAccountId.RESERVE, CALM, 1L,
                ExchangeDesk.ANY_PRICE).result());
        assertEquals(TradeResult.NOT_ENOUGH_UNITS, exchange.sell(alice, CALM, 6L).result());
        assertEquals(TradeResult.NOTHING_HELD, exchange.sell(alice, PLAIN, 1L).result());
        assertEquals(TradeResult.NOTHING_HELD, exchange.sell(bob, CALM, 1L).result());

        // Through all of that not a credit moved and not a unit changed hands.
        assertEquals(ledger, exchange.ledgerSnapshot());
        assertEquals(section, exchange.exchangeSnapshot());

        // A delisted company can be neither bought nor sold; its holders are paid out by the exchange.
        exchange.delist(ExchangeWorld.WILD);
        assertEquals(TradeResult.DELISTED, exchange.buy(alice, WILD, 1L).result());
        assertEquals(TradeResult.DELISTED, exchange.sell(alice, WILD, 1L).result());
        assertEquals(5L, exchange.holding(alice, WILD).units());
        exchange.check();
    }

    @Test void anAccountMayMakeOnlySoManyExchangeActionsAMinute() {
        exchange.terms = exchange.terms.withActionsPerMinute(3).withHold(0L);
        UUID alice = player(1, 10_000L);
        UUID bob = player(2, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        assertTrue(exchange.buy(alice, CALM, 1L).ok());
        assertTrue(exchange.sell(alice, CALM, 1L).ok());
        // A refused action counts as well.
        assertEquals(TradeResult.NOTHING_HELD, exchange.sell(alice, CALM, 1L).result());
        long before = balance(alice);
        assertEquals(TradeResult.TOO_FAST, exchange.buy(alice, CALM, 1L).result());
        assertEquals(TradeResult.TOO_FAST,
                exchange.desk().buyBond(exchange.env(), account, BondTerm.ONE_DAY, 100L, null).result());
        assertEquals(before, balance(alice));
        // Quotes are not actions, and somebody else has their own count.
        assertTrue(exchange.desk().quoteBuy(exchange.env(), account, CALM, 1L).ok());
        assertTrue(exchange.buy(bob, CALM, 1L).ok());
        // A minute after the first action the count starts again.
        exchange.advance(59_999L);
        assertEquals(TradeResult.TOO_FAST, exchange.buy(alice, CALM, 1L).result());
        exchange.advance(1L);
        assertTrue(exchange.buy(alice, CALM, 1L).ok());
        exchange.check();
    }

    // --- the index fund --------------------------------------------------------------------------

    @Test void theIndexFundTradesLikeAShareAtTheIndexPriceWithItsOwnSpread() {
        exchange.terms = exchange.terms.withCosts(100, 100, 200, 300).withHold(0L);
        UUID alice = player(1, 10_000L);
        // The index starts at 100.00; at a 3 % spread the ask is 101.50 and the bid 98.50.
        TradeQuote bought = exchange.buy(alice, ExchangeWorld.INDEX, 10L);
        assertTrue(bought.ok());
        assertEquals(10_150L, bought.unitPriceCents());
        assertEquals(1_015L, bought.total());
        assertEquals(1_015L, exchange.exchangeBalance());
        assertEquals(1_015L, exchange.desk().portfolio(ExchangeWorld.account(alice)).invested());
        exchange.indexPrice(13_000L);
        TradeQuote sold = exchange.sell(alice, ExchangeWorld.INDEX, 10L);
        assertTrue(sold.ok());
        assertEquals(12_805L, sold.unitPriceCents());
        assertEquals(1_280L, sold.total());
        assertEquals(265L, sold.gain());
        exchange.check();
        // With nothing listed there is no basket to buy.
        exchange.registry = StockRegistry.EMPTY;
        exchange.exchange().reconcile(exchange.registry);
        assertEquals(TradeResult.UNKNOWN_STOCK, exchange.buy(alice, ExchangeWorld.INDEX, 1L).result());
    }

    // --- the two properties, through the real desk -----------------------------------------------

    @Test void anImmediateRoundTripAlwaysLosesThroughTheDeskForEveryStockAndTheIndex() {
        noHold();
        exchange.terms = exchange.terms.withLimits(1_000_000L, 4_000_000L, 8).withActionsPerMinute(600);
        UUID alice = player(1, 3_000_000L);
        Random random = new Random(99L);
        List<String> products = List.of(CALM, PLAIN, WILD, ExchangeWorld.INDEX);
        long reserve = reserve();
        for (int round = 0; round < 800; round++) {
            String product = products.get(round % products.size());
            long price = 1L + (round % 3 == 0 ? random.nextInt(400) : random.nextInt(200_000));
            if (product.equals(ExchangeWorld.INDEX)) exchange.indexPrice(price);
            else exchange.price(product, price);
            long units = 1L + random.nextInt(round % 5 == 0 ? 3 : 400);
            // Every round loses a little, so the balance is made up again first.
            player(1, 3_000_000L);
            long before = balance(alice);
            TradeQuote bought = exchange.buy(alice, product, units);
            assertTrue(bought.ok(), product + " at " + price + ": " + bought.result());
            TradeQuote sold = exchange.sell(alice, product, units);
            assertTrue(sold.ok(), product + " at " + price + ": " + sold.result());
            assertTrue(balance(alice) < before, units + " " + product + " at " + price + " did not lose");
            assertTrue(sold.total() < bought.total());
            assertEquals(0L, sold.gain());
            assertEquals(0L, exchange.exchangeBalance());
            // What the trader lost is exactly what the Reserve gained: nothing was made or destroyed.
            assertEquals(before - balance(alice), reserve() - reserve);
            reserve = reserve();
            exchange.check();
            exchange.advance(1_000L);
        }
        // Round trips never draw on the exchange budget.
        assertEquals(15_000L, exchange.budgetLeft());
    }

    @Test void buyingOrSellingInSinglesNeverDoesBetterThanTheWholeOrderThroughTheDesk() {
        noHold();
        exchange.terms = exchange.terms.withLimits(1_000_000L, 4_000_000L, 8).withActionsPerMinute(600);
        UUID whole = player(1, 1_000_000L);
        UUID singles = player(2, 1_000_000L);
        Random random = new Random(3L);
        for (int round = 0; round < 60; round++) {
            String product = round % 2 == 0 ? CALM : WILD;
            exchange.price(product, 50L + random.nextInt(60_000));
            int units = 2 + random.nextInt(12);
            long wholeBefore = balance(whole);
            long singlesBefore = balance(singles);
            assertTrue(exchange.buy(whole, product, units).ok());
            for (int unit = 0; unit < units; unit++) assertTrue(exchange.buy(singles, product, 1L).ok());
            assertTrue(singlesBefore - balance(singles) >= wholeBefore - balance(whole), "buying singles was cheaper");
            // A price that fell: both sell at a loss.
            exchange.price(product, Math.max(1L, exchange.exchange().state(product).price() * 9L / 10L));
            wholeBefore = balance(whole);
            singlesBefore = balance(singles);
            assertTrue(exchange.sell(whole, product, units).ok());
            for (int unit = 0; unit < units; unit++) assertTrue(exchange.sell(singles, product, 1L).ok());
            assertTrue(balance(singles) - singlesBefore <= balance(whole) - wholeBefore, "selling singles fetched more");
            exchange.check();
            exchange.advance(61_000L);
        }
    }

    // --- persistence, erasure, export ------------------------------------------------------------

    @Test void holdingsSurviveASaveAndALoad() {
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, CALM, 10L);
        exchange.advance(10L * ExchangeWorld.MINUTE);
        exchange.buy(alice, WILD, 3L);
        List<Holding.Lot> calm = exchange.holding(alice, CALM).lots();
        JsonElement before = exchange.exchangeSnapshot();
        exchange.world.reload();
        assertEquals(before, exchange.exchangeSnapshot());
        assertEquals(calm, exchange.holding(alice, CALM).lots());
        assertEquals(3L, exchange.holding(alice, WILD).units());
        assertFalse(exchange.exchange().isEmpty());
        exchange.check();
        // And the reloaded store carries on: the holding period still counts from the purchase.
        assertEquals(TradeResult.HOLDING_PERIOD, exchange.sell(alice, CALM, 10L).result());
        exchange.advance(50L * ExchangeWorld.MINUTE);
        assertTrue(exchange.sell(alice, CALM, 10L).ok());
        exchange.check();
    }

    @Test void aStoreSavedBeforeTradingExistedLoadsWithNobodyHoldingAnything() {
        // A section that only ever priced companies is saved exactly as it was before trading
        // existed: none of the fields added with it is written.
        exchange.advance(ExchangeWorld.STEP);
        exchange.runDueSteps();
        JsonObject old = exchange.exchangeSnapshot().getAsJsonObject();
        for (String added : List.of("portfolios", "practice")) assertFalse(old.has(added), added);
        for (JsonElement stock : old.getAsJsonArray("stocks")) assertFalse(stock.getAsJsonObject().has("split"));
        old.remove("dividends");
        ExchangeSection loaded = ExchangeSection.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, old).getOrThrow();
        assertTrue(loaded.desk().isEmpty());
        assertTrue(loaded.practice().isEmpty());
        assertEquals(0L, loaded.held());
        assertFalse(loaded.dividends().happened());
        assertEquals(0, loaded.state(CALM).splits());
        assertEquals(exchange.exchange().state(CALM).price(), loaded.state(CALM).price());
        // Once somebody holds something, it is written — and a broken row among good ones is left out.
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, CALM, 10L);
        JsonObject saved = exchange.exchangeSnapshot().getAsJsonObject();
        assertEquals(1, saved.getAsJsonArray("portfolios").size());
        JsonObject broken = saved.getAsJsonArray("portfolios").get(0).getAsJsonObject().deepCopy();
        broken.addProperty("account", "nobody");
        saved.getAsJsonArray("portfolios").add(broken);
        ExchangeSection again = ExchangeSection.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(1, again.desk().size());
        assertEquals(1_005L, again.held());
    }

    @Test void erasingAPlayerSweepsTheirPrincipalToTheReserveAndRemovesTheirHoldings() {
        UUID alice = player(1, 10_000L);
        UUID bob = player(2, 10_000L);
        AccountId account = ExchangeWorld.account(alice);
        exchange.buy(alice, CALM, 10L);                                         // 1005
        exchange.buy(alice, ExchangeWorld.INDEX, 2L);                           // 201
        assertTrue(exchange.desk().buyBond(exchange.env(), account, BondTerm.SEVEN_DAYS, 1_000L, null).ok());
        exchange.buy(bob, CALM, 3L);                                            // 302
        // Alice's principal, and the 80 credits of interest reserved for her bond.
        assertEquals(1_005L + 201L + 1_000L + 80L + 302L, exchange.exchangeBalance());
        long reserve = reserve();
        assertEquals(2_286L, exchange.exchange().forgetPlayer(alice));
        assertEquals(302L, exchange.exchangeBalance());
        assertEquals(reserve + 2_286L, reserve());
        assertNull(exchange.desk().portfolio(account));
        assertNotNull(exchange.holding(bob, CALM));
        assertEquals(1, exchange.desk().size());
        exchange.check();
        // Erasing somebody who holds nothing, or again, does nothing.
        assertEquals(0L, exchange.exchange().forgetPlayer(alice));
        assertEquals(0L, exchange.exchange().forgetPlayer(UUID.randomUUID()));
        assertEquals(0L, exchange.exchange().forgetPlayer(null));
        exchange.check();
    }

    @Test void erasureWorksWhileTheEconomyIsFrozen() {
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, CALM, 10L);
        EconomyHealth.freeze();
        assertEquals(1_005L, exchange.exchange().forgetPlayer(alice));
        EconomyHealth.clear();
        assertEquals(0L, exchange.exchangeBalance());
        exchange.check();
    }

    @Test void theExportHoldsTheSubjectsOwnHoldingsAndNothingElse() {
        UUID alice = player(1, 10_000L);
        UUID bob = player(2, 10_000L);
        long bought = exchange.now();
        exchange.buy(alice, CALM, 10L);
        exchange.buy(bob, WILD, 7L);
        JsonObject export = exchange.exchange().exportFor(alice);
        assertEquals(1, export.getAsJsonArray("holdings").size());
        JsonObject holding = export.getAsJsonArray("holdings").get(0).getAsJsonObject();
        assertEquals(CALM, holding.get("stock").getAsString());
        assertEquals(10L, holding.get("units").getAsLong());
        assertEquals(1_005L, holding.get("cost").getAsLong());
        JsonObject lot = holding.getAsJsonArray("lots").get(0).getAsJsonObject();
        assertEquals(bought, lot.get("acquired_at").getAsLong());
        assertEquals(0, export.getAsJsonArray("bonds").size());
        assertTrue(export.has("practice"));
        // Nobody else is in it: no id of anyone, and nothing of what Bob holds.
        String text = export.toString();
        assertFalse(text.contains(bob.toString()));
        assertFalse(text.contains(alice.toString()));
        assertFalse(text.contains(WILD));
        // Somebody with nothing gets empty lists, not an error.
        JsonObject empty = exchange.exchange().exportFor(UUID.randomUUID());
        assertEquals(0, empty.getAsJsonArray("holdings").size());
        assertEquals(0, empty.getAsJsonArray("bonds").size());
    }

    @Test void theReserveBudgetAndInvariantCAreReportedForOperators() {
        noHold();
        UUID alice = player(1, 10_000L);
        exchange.buy(alice, CALM, 10L);
        assertTrue(exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(alice), BondTerm.SEVEN_DAYS, 1_000L,
                null).ok());
        assertEquals(1_005L + 1_000L + 80L, exchange.world.store.supplyReport().exchangeHeld());
        assertEquals(exchange.exchangeBalance(), exchange.world.store.supplyReport().exchange());
        assertTrue(exchange.world.store.supplyReport().exchangeBalanced());
        long[] parts = exchange.desk().heldParts();
        assertEquals(1_005L, parts[0]);
        assertEquals(1_000L, parts[1]);
        assertEquals(80L, parts[2]);
        // The interest was reserved under the exchange budget.
        assertEquals(80L, exchange.ledger().budgetUsed(BudgetKind.EXCHANGE));
        // A credit that reaches the exchange account by any other road breaks Invariant C, and it shows.
        exchange.ledger().apply(za.co.neroland.neroeconomy.economy.LedgerPlan.of(ExchangeWorld.account(alice),
                SystemAccountId.EXCHANGE, 5L, TransactionType.FEE_EXCHANGE));
        assertFalse(exchange.world.store.checkInvariants());
        assertFalse(exchange.world.store.supplyReport().exchangeBalanced());
        assertFalse(exchange.world.store.supplyReport().healthy());
        assertTrue(exchange.world.store.supplyReport().supplyBalanced());
    }
}
