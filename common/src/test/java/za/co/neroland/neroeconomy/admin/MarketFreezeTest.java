package za.co.neroland.neroeconomy.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.BookResult;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.exchange.BondOutcome;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.ExchangeWorld;
import za.co.neroland.neroeconomy.exchange.TradeResult;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.offers.OfferOutcome;
import za.co.neroland.neroeconomy.offers.OfferResult;
import za.co.neroland.neroeconomy.offers.OffersSection;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The operator's market freeze against a real store: while the market is closed every trading
 * path — the book, offers, auto-trade rules and the exchange — refuses with its own result and
 * moves nothing, while collecting claims and every refund (cancelling an order, taking back an
 * offer, deleting a rule, cashing in a bond) still works. The switch is saved with the store, a
 * store saved before it existed loads with the market open, and it is not the corruption freeze.
 */
class MarketFreezeTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private final TradeWorld world = new TradeWorld();

    @AfterEach void done() {
        assertTrue(world.invariants(), "the ledger invariants must hold at the end of every test");
        world.close();
        EconomyHealth.clear();
    }

    private BookSection book() { return world.store.book(); }

    private Placed sell(UUID seller, int count, long price) {
        AccountId account = TradeWorld.account(seller);
        return book().placeSell(world.env, account, Lot.of(DIAMOND, count), price, OrderSource.VAULT,
                VaultGoods.origin(world.store, world.env, account), null);
    }

    private Placed buy(UUID buyer, int count, long limit) {
        AccountId account = TradeWorld.account(buyer);
        return book().placeBuy(world.env, account, DIAMOND, count, limit, OrderSource.VAULT, false,
                VaultGoods.receiver(world.store, world.env, account), null);
    }

    private void close(boolean closed) { world.store.admin().setMarketFrozen(closed); }

    /** Everything about these players a trade could change. */
    private String snapshot(UUID... players) {
        StringBuilder out = new StringBuilder();
        for (UUID player : players) out.append(world.balance(player)).append('/').append(world.claim(player)).append('/')
                .append(world.store.vault().stacks(player)).append(';');
        return out.append(world.escrow()).append(';').append(world.reserve()).append(';').append(book().size())
                .append(';').append(world.store.offers().size()).toString();
    }

    @Test void theBookRefusesEveryTradeButCancellingAndCollectingStillWork() {
        UUID seller = world.player(1, 100L);
        world.stock(seller, Lot.of(DIAMOND, 10));
        UUID buyer = world.player(2, 1_000L);
        // Before the market closes: one sale, so the seller has proceeds waiting, and one resting order each way.
        assertTrue(sell(seller, 2, 5L).ok());
        assertEquals(2, buy(buyer, 2, 5L).filled());
        Placed resting = sell(seller, 3, 9L);
        Placed bid = buy(buyer, 4, 2L);
        assertTrue(resting.ok() && bid.ok());
        long waiting = world.claim(seller);
        assertTrue(waiting > 0L);

        close(true);
        assertTrue(world.store.marketClosed());
        String before = snapshot(seller, buyer);
        assertEquals(BookResult.MARKET_CLOSED, sell(seller, 1, 5L).result());
        assertEquals(BookResult.MARKET_CLOSED, buy(buyer, 1, 20L).result());
        assertEquals(BookResult.MARKET_CLOSED, book().buyFrom(world.env, TradeWorld.account(buyer), resting.orderId(), 1,
                9L, VaultGoods.receiver(world.store, world.env, TradeWorld.account(buyer))).result());
        assertEquals(BookResult.MARKET_CLOSED, book().sellTo(world.env, TradeWorld.account(seller), bid.orderId(),
                Lot.of(DIAMOND, 1), VaultGoods.origin(world.store, world.env, TradeWorld.account(seller))).result());
        assertEquals(before, snapshot(seller, buyer), "a refused trade moved something");
        assertEquals(EconomyHealth.MARKET_CLOSED_KEY, BookResult.MARKET_CLOSED.translationKey());
        assertEquals(EconomyHealth.MARKET_CLOSED_KEY, PurchaseResult.MARKET_CLOSED.translationKey());

        // Refunds and claims: the orders come back whole, and the waiting proceeds are paid out.
        long sellerVault = world.held(seller, DIAMOND);
        assertEquals(CancelResult.CANCELLED, book().cancel(world.env, resting.orderId(), TradeWorld.account(seller)));
        assertEquals(sellerVault + 3L, world.held(seller, DIAMOND));
        // The bid's escrow comes back to the buyer, to their balance or to collect.
        long buyerHeld = world.balance(buyer) + world.claim(buyer);
        assertEquals(CancelResult.CANCELLED, book().cancel(world.env, bid.orderId(), TradeWorld.account(buyer)));
        assertEquals(buyerHeld + 8L, world.balance(buyer) + world.claim(buyer));
        long sellerBalance = world.balance(seller);
        assertEquals(waiting, world.store.market().collectCreditsTotal(seller));
        assertEquals(sellerBalance + waiting, world.balance(seller));

        // Opened again, trading carries on as before.
        close(false);
        assertTrue(sell(seller, 1, 5L).ok());
    }

    @Test void offersCannotBeSentOrAcceptedButCanBeTakenBack() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 500L);
        world.stock(sender, Lot.of(DIAMOND, 5));
        AccountId from = TradeWorld.account(sender);
        List<OffersSection.Given> given = List.of(new OffersSection.Given(Lot.of(DIAMOND, 2),
                VaultGoods.origin(world.store, world.env, from)));
        OfferOutcome sent = world.store.offers().send(world.env, from, TradeWorld.account(recipient), given, 0L, "", 0,
                20L, null);
        assertTrue(sent.ok(), sent.result().name());

        close(true);
        String before = snapshot(sender, recipient);
        assertEquals(OfferResult.MARKET_CLOSED, world.store.offers().accept(world.env, sent.offer().id(),
                TradeWorld.account(recipient)).result());
        assertEquals(OfferResult.MARKET_CLOSED, world.store.offers().send(world.env, from, TradeWorld.account(recipient),
                List.of(new OffersSection.Given(Lot.of(DIAMOND, 1), VaultGoods.origin(world.store, world.env, from))), 0L,
                "", 0, 5L, null).result());
        assertEquals(before, snapshot(sender, recipient));
        // Taking it back is a refund: the goods come home.
        assertTrue(world.store.offers().cancel(world.env, sent.offer().id(), from).ok());
        assertEquals(5L, world.held(sender, DIAMOND));
    }

    @Test void rulesDoNotTradeWhileClosedButTheirBudgetComesBack() {
        UUID seller = world.player(1, 100L);
        world.stock(seller, Lot.of(DIAMOND, 10));
        UUID owner = world.player(2, 1_000L);
        RulesSection rules = world.store.rules();
        RuleOutcome added = rules.add(world.env, TradeWorld.account(owner), RuleSide.BUY, false, DIAMOND, 10L, 5, 100L,
                RulesSection.VAULT, null);
        assertTrue(added.ok(), added.result().name());

        close(true);
        // A seller cannot post while closed, and the rule is not evaluated: nothing is bought.
        assertEquals(BookResult.MARKET_CLOSED, sell(seller, 5, 5L).result());
        rules.enqueueAll();
        assertEquals(0, rules.evaluatePending(world.env, 1L));
        assertEquals(0L, world.held(owner, DIAMOND));
        // Deleting the rule gives its budget back.
        long balance = world.balance(owner);
        RuleOutcome deleted = rules.delete(world.env, TradeWorld.account(owner), added.rule().id());
        assertTrue(deleted.ok(), deleted.result().name());
        assertTrue(world.balance(owner) + world.claim(owner) > balance);
    }

    @Test void theExchangeRefusesBuyingAndSellingButABondCanStillBeCashedIn() {
        ExchangeWorld exchange = new ExchangeWorld();
        try {
            exchange.terms = exchange.terms.withHold(0L);
            UUID alice = exchange.world.player(1, 10_000L);
            assertTrue(exchange.buy(alice, ExchangeWorld.CALM.id(), 5L).ok());
            BondOutcome bond = exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(alice), BondTerm.ONE_DAY,
                    100L, null);
            assertTrue(bond.result().ok());

            exchange.world.store.admin().setMarketFrozen(true);
            long balance = exchange.world.balance(alice);
            assertEquals(TradeResult.MARKET_CLOSED, exchange.buy(alice, ExchangeWorld.CALM.id(), 1L).result());
            assertEquals(TradeResult.MARKET_CLOSED, exchange.sell(alice, ExchangeWorld.CALM.id(), 1L).result());
            assertEquals(TradeResult.MARKET_CLOSED, exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(alice),
                    BondTerm.ONE_DAY, 100L, null).result());
            assertEquals(balance, exchange.world.balance(alice));
            // Cashing in early returns the principal: a refund, so it works while closed.
            BondOutcome redeemed = exchange.desk().redeemBond(exchange.env(), ExchangeWorld.account(alice),
                    bond.bond().id());
            assertTrue(redeemed.result().ok(), redeemed.result().name());
            assertTrue(exchange.world.balance(alice) > balance);
            assertTrue(exchange.world.invariants());
        } finally {
            exchange.close();
        }
    }

    @Test void theSwitchIsSavedCountedAndNotTheCorruptionFreeze() {
        assertFalse(world.store.marketClosed());
        assertTrue(world.store.admin().isEmpty());
        assertTrue(world.store.admin().setMarketFrozen(true));
        assertFalse(world.store.admin().setMarketFrozen(true));
        world.store.admin().record(AdminSection.Action.MARKET_CLOSE);
        world.store.admin().record(AdminSection.Action.GRANT);
        world.store.admin().record(AdminSection.Action.GRANT);
        assertFalse(EconomyHealth.frozen());
        world.reload();
        assertTrue(world.store.marketClosed());
        assertEquals(2L, world.store.admin().count(AdminSection.Action.GRANT));
        assertEquals(1L, world.store.admin().count(AdminSection.Action.MARKET_CLOSE));
        assertEquals(0L, world.store.admin().count(AdminSection.Action.ERASE));
        assertTrue(world.store.sectionsPresent().contains("admin"));
        // Opened and with nothing counted, the section is left out of the saved store, and loads open.
        za.co.neroland.neroeconomy.economy.EconomyStore fresh = za.co.neroland.neroeconomy.economy.StoreTesting.reload(
                new za.co.neroland.neroeconomy.economy.EconomyStore());
        assertFalse(fresh.marketClosed());
        assertFalse(fresh.sectionsPresent().contains("admin"));
        // The corruption freeze refuses with its own result, ahead of the market switch.
        UUID seller = world.player(1, 100L);
        world.stock(seller, Lot.of(DIAMOND, 1));
        EconomyHealth.freeze();
        assertEquals(BookResult.FROZEN, sell(seller, 1, 5L).result());
        EconomyHealth.clear();
        assertEquals(BookResult.MARKET_CLOSED, sell(seller, 1, 5L).result());
    }
}
