package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
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
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * Practice mode: the same quotes, spreads, fees, holding period and limits as the real exchange,
 * on play credits that can never touch the ledger, the Reserve, the vault or an alert.
 */
class PracticeModeTest {
    private static final String CALM = ExchangeWorld.CALM.id();
    private static final String PLAIN = ExchangeWorld.PLAIN.id();
    private static final String WILD = ExchangeWorld.WILD.id();

    private final ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() {
        EconomyHealth.clear();
        exchange.close();
    }

    private PracticeBook practice() { return exchange.practice(); }
    private AccountId account(int number) { return ExchangeWorld.account(new UUID(7L, number)); }
    private long play(AccountId account) { return practice().balance(exchange.terms, account); }

    private TradeQuote buy(AccountId account, String stockId, long units) {
        return practice().buy(exchange.env(), account, stockId, units, ExchangeDesk.ANY_PRICE);
    }

    private TradeQuote sell(AccountId account, String stockId, long units) {
        return practice().sell(exchange.env(), account, stockId, units);
    }

    /** The whole store as it would be saved, without the exchange section and the clock's watermark. */
    private String realEconomy() {
        JsonObject saved = StoreTesting.encode(exchange.world.store).getAsJsonObject();
        JsonObject section = saved.has("exchange") ? saved.getAsJsonObject("exchange") : new JsonObject();
        // Of the exchange section the real desk's part is the real economy; the practice part is not.
        JsonElement portfolios = section.get("portfolios");
        saved.remove("exchange");
        saved.getAsJsonObject("ledger").remove("clock_last_wall");
        return saved + "|" + portfolios;
    }

    // --- the same rules --------------------------------------------------------------------------

    @Test void aPracticeTradeIsQuotedExactlyAsARealOneIs() {
        exchange.terms = exchange.terms.withHold(0L);
        UUID real = exchange.world.player(1, 10_000L);
        AccountId sandbox = account(2);
        assertEquals(10_000L, play(sandbox));
        // The same ten units of the same share: the same price, total, fee and spend.
        TradeQuote realQuote = exchange.desk().quoteBuy(exchange.env(), ExchangeWorld.account(real), CALM, 10L);
        TradeQuote playQuote = practice().quoteBuy(exchange.env(), sandbox, CALM, 10L);
        assertEquals(realQuote, playQuote);
        assertEquals(realQuote, buy(sandbox, CALM, 10L));
        assertEquals(realQuote, exchange.buy(real, CALM, 10L));
        assertEquals(10_000L - 1_016L, play(sandbox));
        assertEquals(practice().portfolio(sandbox).holding(CALM).lots(), exchange.holding(real, CALM).lots());
        // And the same on the way out, gain and fee and all.
        exchange.price(CALM, 12_000L);
        TradeQuote realSale = exchange.desk().quoteSell(exchange.env(), ExchangeWorld.account(real), CALM, 10L);
        assertEquals(realSale, practice().quoteSell(exchange.env(), sandbox, CALM, 10L));
        assertEquals(realSale, sell(sandbox, CALM, 10L));
        assertEquals(10_000L - 1_016L + 1_182L, play(sandbox));
        // Buying for an amount, too.
        assertEquals(exchange.desk().quoteBuyForCredits(exchange.env(), ExchangeWorld.account(real), WILD, 2_500L),
                practice().quoteBuyForCredits(exchange.env(), sandbox, WILD, 2_500L));
        assertTrue(practice().buyForCredits(exchange.env(), sandbox, WILD, 2_500L).ok());
    }

    @Test void theHoldingPeriodTheLimitsAndEveryRefusalApplyToPracticeToo() {
        AccountId sandbox = account(2);
        assertTrue(buy(sandbox, CALM, 10L).ok());
        assertEquals(TradeResult.HOLDING_PERIOD, sell(sandbox, CALM, 10L).result());
        assertEquals(TradeResult.HOLDING_PERIOD, sell(sandbox, CALM, Quotes.ALL).result());
        assertEquals(TradeResult.NOT_ENOUGH_UNITS, sell(sandbox, CALM, 11L).result());
        assertEquals(TradeResult.NOTHING_HELD, sell(sandbox, WILD, 1L).result());
        assertEquals(TradeResult.UNKNOWN_STOCK, buy(sandbox, "test:nothing", 1L).result());
        assertEquals(TradeResult.INVALID, buy(sandbox, CALM, 0L).result());
        assertEquals(TradeResult.INVALID, buy(SystemAccountId.RESERVE, CALM, 1L).result());
        // 39 more units take the position to 4925 of the 5000 a stock may hold; one more is past it.
        assertTrue(buy(sandbox, CALM, 39L).ok());
        assertEquals(TradeResult.POSITION_LIMIT, buy(sandbox, CALM, 1L).result());
        // The play balance is a limit of its own: 99 units at 50.25 cost 4975 and a fee of 50, one more than is left.
        assertEquals(5_024L, play(sandbox));
        assertEquals(TradeResult.INSUFFICIENT_FUNDS, buy(sandbox, PLAIN, 99L).result());
        exchange.halt(WILD);
        assertEquals(TradeResult.HALTED, buy(sandbox, WILD, 1L).result());
        exchange.terms = exchange.terms.withEnabled(false);
        assertEquals(TradeResult.EXCHANGE_OFF, buy(sandbox, PLAIN, 1L).result());
        exchange.terms = exchange.terms.withEnabled(true);
        EconomyHealth.freeze();
        assertEquals(TradeResult.FROZEN, buy(sandbox, PLAIN, 1L).result());
        EconomyHealth.clear();
        exchange.delist(ExchangeWorld.PLAIN);
        assertEquals(TradeResult.DELISTED, buy(sandbox, PLAIN, 1L).result());
        // After the hour the first purchase may be sold.
        exchange.advance(ExchangeWorld.HOUR);
        assertTrue(sell(sandbox, CALM, Quotes.ALL).ok());
        assertNull(practice().portfolio(sandbox).holding(CALM));
    }

    @Test void tooManyPracticePurchasesInARowAreToldToSettleAndThePracticeRateLimitIsItsOwn() {
        exchange.terms = exchange.terms.withActionsPerMinute(9);
        AccountId sandbox = account(2);
        UUID real = exchange.world.player(2, 10_000L);
        for (int purchase = 0; purchase < Holding.MAX_YOUNG_LOTS; purchase++) assertTrue(buy(sandbox, CALM, 1L).ok());
        assertEquals(TradeResult.SETTLING, buy(sandbox, CALM, 1L).result());
        // Nine practice actions this minute: the tenth is too fast — but the same account's real trades are not.
        assertEquals(TradeResult.TOO_FAST, buy(sandbox, PLAIN, 1L).result());
        assertTrue(exchange.buy(real, PLAIN, 1L).ok());
        exchange.advance(ExchangeWorld.MINUTE);
        assertTrue(buy(sandbox, PLAIN, 1L).ok());
    }

    @Test void aPracticeGainIsPaidInFullEvenWhenTheReserveIsEmpty() {
        exchange.terms = exchange.terms.withHold(0L);
        AccountId sandbox = account(2);
        buy(sandbox, CALM, 10L);
        exchange.price(CALM, 12_000L);
        assertTrue(exchange.ledger().withdraw(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS,
                exchange.world.reserve(), TransactionType.ADMIN_TAKE));
        TradeQuote sold = sell(sandbox, CALM, 10L);
        assertTrue(sold.ok());
        assertEquals(189L, sold.gainPayable());
        assertEquals(0L, sold.gainForfeited());
        assertEquals(0L, exchange.world.reserve());
    }

    // --- practice bonds --------------------------------------------------------------------------

    @Test void practiceBondsPayPlayCreditsAtMaturityAndThePrincipalWhenCashedInEarly() {
        AccountId sandbox = account(2);
        BondOutcome seven = practice().buyBond(exchange.env(), sandbox, BondTerm.SEVEN_DAYS, 1_000L, null);
        BondOutcome one = practice().buyBond(exchange.env(), sandbox, BondTerm.ONE_DAY, 5_000L, null);
        assertTrue(seven.ok());
        assertEquals(80L, seven.bond().interest());
        assertEquals(40L, one.bond().interest());
        assertEquals(4_000L, play(sandbox));
        // They count toward the practice portfolio limit and the bond cap, as real ones do.
        assertEquals(6_000L, practice().portfolio(sandbox).invested());
        assertEquals(TradeResult.INSUFFICIENT_FUNDS,
                practice().buyBond(exchange.env(), sandbox, BondTerm.ONE_DAY, 4_001L, null).result());
        // The one-day bond pays out at the day's step, with nobody doing anything — and with no alert.
        exchange.advance(ExchangeWorld.DAY);
        exchange.runDueSteps();
        assertEquals(4_000L + 5_040L, play(sandbox));
        assertTrue(exchange.alerts.isEmpty());
        // The seven-day bond, cashed in early: the play principal only.
        BondOutcome early = practice().redeemBond(exchange.env(), sandbox, seven.bond().id());
        assertTrue(early.ok());
        assertEquals(1_000L, early.paid());
        assertEquals(10_040L, play(sandbox));
        assertEquals(TradeResult.BOND_NOT_FOUND,
                practice().redeemBond(exchange.env(), sandbox, seven.bond().id()).result());
        // Not a real credit was involved, and the Reserve's exchange budget was not drawn on for the interest.
        assertEquals(0L, exchange.exchangeBalance());
        assertEquals(15_000L, exchange.budgetLeft());
        exchange.check();
    }

    @Test void thePracticeBondCapIsTheRealOne() {
        AccountId sandbox = account(2);
        for (int bond = 0; bond < 8; bond++)
            assertTrue(practice().buyBond(exchange.env(), sandbox, BondTerm.ONE_DAY, 100L, null).ok());
        assertEquals(TradeResult.BOND_LIMIT,
                practice().buyBond(exchange.env(), sandbox, BondTerm.ONE_DAY, 100L, null).result());
    }

    // --- reset -----------------------------------------------------------------------------------

    @Test void resetRestoresThePlayBalanceAndClearsThePortfolioAtMostOnceADay() {
        AccountId sandbox = account(2);
        buy(sandbox, CALM, 10L);
        practice().buyBond(exchange.env(), sandbox, BondTerm.SEVEN_DAYS, 1_000L, null);
        assertTrue(practice().canReset(sandbox));
        assertEquals(TradeResult.OK, practice().reset(exchange.env(), sandbox));
        assertEquals(10_000L, play(sandbox));
        assertTrue(practice().portfolio(sandbox).isEmpty());
        assertFalse(practice().canReset(sandbox));
        // A second reset the same economy-day is refused, and changes nothing.
        buy(sandbox, CALM, 5L);
        long before = play(sandbox);
        assertEquals(TradeResult.PRACTICE_RESET_USED, practice().reset(exchange.env(), sandbox));
        assertEquals(before, play(sandbox));
        assertEquals(5L, practice().portfolio(sandbox).holding(CALM).units());
        // The next day it may be reset again, to whatever the starting amount is by then.
        exchange.advance(ExchangeWorld.DAY);
        exchange.terms = exchange.terms.withPractice(25_000L);
        assertTrue(practice().canReset(sandbox));
        assertEquals(TradeResult.OK, practice().reset(exchange.env(), sandbox));
        assertEquals(25_000L, play(sandbox));
        // Somebody who never practised can reset too: it simply opens their sandbox.
        assertEquals(TradeResult.OK, practice().reset(exchange.env(), account(9)));
        assertEquals(25_000L, play(account(9)));
        assertEquals(TradeResult.INVALID, practice().reset(exchange.env(), SystemAccountId.RESERVE));
    }

    // --- persistence, erasure, export, purge -----------------------------------------------------

    @Test void aPracticePortfolioSurvivesASaveAndALoadIsExportedAndIsErased() {
        AccountId sandbox = account(2);
        UUID player = new UUID(7L, 2L);
        long bought = exchange.now();
        buy(sandbox, CALM, 10L);
        BondOutcome bond = practice().buyBond(exchange.env(), sandbox, BondTerm.THREE_DAYS, 2_000L, null);
        JsonElement before = exchange.exchangeSnapshot();
        exchange.world.reload();
        assertEquals(before, exchange.exchangeSnapshot());
        assertEquals(10_000L - 1_016L - 2_000L, play(sandbox));
        assertEquals(10L, practice().portfolio(sandbox).holding(CALM).units());
        assertEquals(List.of(bond.bond()), practice().portfolio(sandbox).bonds());

        JsonObject export = exchange.exchange().exportFor(player).getAsJsonObject("practice");
        assertEquals(6_984L, export.get("play_balance").getAsLong());
        JsonObject holding = export.getAsJsonArray("holdings").get(0).getAsJsonObject();
        assertEquals(CALM, holding.get("stock").getAsString());
        assertEquals(10L, holding.get("units").getAsLong());
        assertEquals(1_005L, holding.get("cost").getAsLong());
        assertEquals(bought, holding.getAsJsonArray("lots").get(0).getAsJsonObject().get("acquired_at").getAsLong());
        assertEquals(2_000L, export.getAsJsonArray("bonds").get(0).getAsJsonObject().get("principal").getAsLong());
        // The real part of the same export is empty: the two are never mixed.
        assertEquals(0, exchange.exchange().exportFor(player).getAsJsonArray("holdings").size());

        assertEquals(0L, exchange.exchange().forgetPlayer(player));
        assertNull(practice().portfolio(sandbox));
        assertEquals(0, practice().size());
        assertEquals(exchange.terms.practiceStart(), play(sandbox));
        assertFalse(practice().forget(player));
    }

    @Test void aSandboxNobodyHasUsedForAMonthIsDropped() {
        AccountId idle = account(2);
        AccountId active = account(3);
        buy(idle, CALM, 1L);
        buy(active, CALM, 1L);
        for (int day = 0; day < 29; day++) exchange.advance(ExchangeWorld.DAY);
        buy(active, PLAIN, 1L);
        exchange.exchange().settle(exchange.env());
        assertEquals(2, practice().size());
        exchange.advance(ExchangeWorld.DAY);
        exchange.exchange().settle(exchange.env());
        assertNull(practice().portfolio(idle));
        assertNotNull(practice().portfolio(active));
        // It starts afresh when it is next used.
        assertEquals(10_000L, play(idle));
    }

    // --- isolation -------------------------------------------------------------------------------

    @Test void thePracticeBookHasNoWayToReachTheStoreOrTheLedger() {
        // By construction: none of its fields has a type through which a credit could be moved. (It
        // holds the section only as a monitor to synchronise on, typed Object.)
        List<String> fields = new ArrayList<>();
        for (Field field : PracticeBook.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            fields.add(field.getName() + ":" + field.getType().getSimpleName());
            assertFalse(EconomyStore.class.isAssignableFrom(field.getType()), field.getName());
            assertFalse(EconomySavedData.class.isAssignableFrom(field.getType()), field.getName());
            assertFalse(ExchangeSection.class.isAssignableFrom(field.getType()), field.getName());
            assertFalse(ExchangeDesk.class.isAssignableFrom(field.getType()), field.getName());
        }
        assertTrue(fields.contains("market:Market"), fields.toString());
        assertTrue(fields.contains("lock:Object"), fields.toString());
        assertEquals(3, fields.size(), fields.toString());
        // And the one window it has shows prices, the time and a dirty flag, nothing that moves a credit.
        List<String> window = new ArrayList<>();
        for (var method : PracticeBook.Market.class.getDeclaredMethods()) window.add(method.getName());
        window.sort(null);
        assertEquals(List.of("changed", "dividendTerms", "frozen", "listing", "now", "seasonedBefore"), window);
    }

    @Test void thousandsOfPracticeOperationsLeaveTheRealEconomyByteForByteUnchanged() {
        exchange.terms = exchange.terms.withHold(10L * ExchangeWorld.MINUTE).withActionsPerMinute(600);
        // A real economy with something in every place practice must not touch.
        UUID alice = exchange.world.player(1, 10_000L);
        UUID bob = exchange.world.player(2, 10_000L);
        exchange.buy(alice, CALM, 10L);
        exchange.buy(bob, WILD, 3L);
        assertTrue(exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(alice), BondTerm.SEVEN_DAYS, 1_000L,
                new UUID(9L, 9L)).ok());
        exchange.world.stock(alice, new za.co.neroland.neroeconomy.goods.Lot("minecraft:diamond", 5, 0));
        long reserve = exchange.world.reserve();
        long exchangeAccount = exchange.exchangeBalance();
        int rows = exchange.ledger().history(alice, 1_000).size();
        String before = realEconomy();

        Random random = new Random(20_261_003L);
        List<String> products = List.of(CALM, PLAIN, WILD, ExchangeWorld.INDEX);
        int[] done = new int[8];
        // The same accounts that hold real positions practise too, and so do strangers.
        List<AccountId> accounts = List.of(ExchangeWorld.account(alice), ExchangeWorld.account(bob), account(5), account(6));
        for (int operation = 0; operation < 12_000; operation++) {
            AccountId account = accounts.get(random.nextInt(accounts.size()));
            String product = products.get(random.nextInt(products.size()));
            switch (random.nextInt(9)) {
                case 0, 1 -> done[0] += buy(account, product, 1L + random.nextInt(8)).ok() ? 1 : 0;
                case 2 -> done[1] += practice().buyForCredits(exchange.env(), account, product,
                        1L + random.nextInt(1_500)).ok() ? 1 : 0;
                case 3, 4 -> done[2] += sell(account, product, random.nextBoolean() ? Quotes.ALL
                        : 1L + random.nextInt(8)).ok() ? 1 : 0;
                case 5 -> done[3] += practice().buyBond(exchange.env(), account,
                        BondTerm.values()[random.nextInt(3)], 1L + random.nextInt(900), null).ok() ? 1 : 0;
                case 6 -> {
                    Portfolio portfolio = practice().portfolio(account);
                    if (portfolio != null && portfolio.bondCount() > 0) done[4] += practice().redeemBond(exchange.env(),
                            account, portfolio.bonds().get(random.nextInt(portfolio.bondCount())).id()).ok() ? 1 : 0;
                }
                case 7 -> {
                    // The price moves, as a step would move it; days roll over, with their practice
                    // dividends, maturities and merges — everything the simulation does to the book.
                    exchange.price(product.equals(ExchangeWorld.INDEX) ? CALM : product, 500L + random.nextInt(40_000));
                    if (random.nextInt(6) == 0) {
                        practice().payDividends(exchange.env(), exchange.today());
                        practice().matureBonds(exchange.now());
                        practice().settle(exchange.terms, exchange.now());
                        done[5]++;
                    }
                }
                default -> {
                    if (random.nextInt(40) == 0) done[6] += practice().reset(exchange.env(), account).ok() ? 1 : 0;
                    practice().quoteSell(exchange.env(), account, product, Quotes.ALL);
                    practice().quoteBuy(exchange.env(), account, product, 3L);
                }
            }
            exchange.advance(random.nextInt(4) == 0 ? 20L * ExchangeWorld.MINUTE : 5_000L);
        }
        // It really was thousands of operations that did something, of every kind.
        for (int kind = 0; kind < 7; kind++) assertTrue(done[kind] > 10, "kind " + kind + " ran " + done[kind] + " times");
        assertTrue(done[0] + done[1] + done[2] > 3_000, "only " + (done[0] + done[1] + done[2]) + " trades");
        assertTrue(practice().size() >= 4);

        // The ledger, the Reserve, the exchange account, the transaction log, the vault, the
        // market and the real portfolios: exactly as they were, byte for byte.
        assertEquals(before, realEconomy());
        assertEquals(reserve, exchange.world.reserve());
        assertEquals(exchangeAccount, exchange.exchangeBalance());
        assertEquals(rows, exchange.ledger().history(alice, 1_000).size());
        assertEquals(10_000L - 1_016L - 1_000L, exchange.world.balance(alice));
        assertEquals(5L, exchange.world.held(alice, "minecraft:diamond"));
        assertTrue(exchange.alerts.isEmpty());
        exchange.check();
    }
}
