package za.co.neroland.neroeconomy.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * Auto-trade rules against a real store: escrow of the budget, the orders a rule keeps, daily
 * quantities, every status, the evaluation budget, the daily value cap, two rules feeding each
 * other, erasure and the saved form. Every test ends with both ledger invariants holding.
 */
class RulesSectionTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private static final String IRON = TradeWorld.IRON;
    private static final String GOLD = TradeWorld.GOLD;
    private static final String GEMS = "c:gems";
    /** Diamonds and gold are gems; iron is not. */
    private static final ItemCatalog CATALOG = new ItemCatalog() {
        @Override public boolean known(String item) { return item.startsWith("minecraft:"); }
        @Override public boolean tradeable(String item) { return known(item); }
        @Override public Set<String> tagsOf(String item) {
            return DIAMOND.equals(item) || GOLD.equals(item) ? Set.of(GEMS) : Set.of();
        }
        @Override public boolean tagKnown(String tag) { return GEMS.equals(tag); }
    };

    private final TradeWorld world = new TradeWorld();
    private long tick;

    RulesSectionTest() { world.env = world.env.withItems(CATALOG); }

    @AfterEach void done() {
        assertTrue(world.invariants(), "the ledger invariants must hold at the end of every test");
        world.close();
    }

    private RulesSection rules() { return world.store.rules(); }
    private BookSection book() { return world.store.book(); }

    /** One server tick's worth of rule evaluation. */
    private int tick() { return rules().evaluatePending(world.env, ++tick); }

    /** Ticks until no rule is waiting any more. */
    private void settle() {
        for (int round = 0; round < 50 && rules().hasWork(); round++) tick();
        assertFalse(rules().hasWork(), "rules kept queueing themselves");
    }

    /** A simulation step comes round: every rule is looked at again. */
    private void step() {
        rules().enqueueAll();
        settle();
    }

    private TradeRule buyRule(UUID owner, String item, long limit, int perDay, long budget) {
        RuleOutcome added = rules().add(world.env, TradeWorld.account(owner), RuleSide.BUY, false, item, limit, perDay,
                budget, RulesSection.VAULT, null);
        assertTrue(added.ok(), added.result().name());
        return added.rule();
    }

    private TradeRule sellRule(UUID owner, String item, long limit, int perDay, long quantity) {
        RuleOutcome added = rules().add(world.env, TradeWorld.account(owner), RuleSide.SELL, false, item, limit, perDay,
                quantity, RulesSection.VAULT, null);
        assertTrue(added.ok(), added.result().name());
        return added.rule();
    }

    private Placed sell(UUID seller, String item, int count, long price) {
        AccountId account = TradeWorld.account(seller);
        return book().placeSell(world.env, account, Lot.of(item, count), price, OrderSource.VAULT,
                VaultGoods.origin(world.store, world.env, account), null);
    }

    private Placed buy(UUID buyer, String item, int count, long limit) {
        AccountId account = TradeWorld.account(buyer);
        return book().placeBuy(world.env, account, item, count, limit, OrderSource.VAULT, false,
                VaultGoods.receiver(world.store, world.env, account), null);
    }

    private TradeRule rule(TradeRule rule) { return rules().rule(rule.id()); }

    // --- the budget --------------------------------------------------------------------------

    @Test void aBuyRuleEscrowsItsWholeBudgetWhenItIsCreatedOrRaised() {
        UUID owner = world.player(1, 1_000L);
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 5, 300L);
        assertEquals(700L, world.balance(owner));
        assertEquals(300L, world.escrow());
        assertEquals(300L, rules().escrowObligations());
        assertEquals(300L, rule.budget());
        assertEquals(RuleStatus.RUNNING, rule.status());

        RuleOutcome raised = rules().raise(world.env, TradeWorld.account(owner), rule.id(), 100L);
        assertTrue(raised.ok());
        assertEquals(100L, raised.escrowed());
        assertEquals(600L, world.balance(owner));
        assertEquals(400L, rule(rule).budget());
        assertEquals(400L, rule(rule).total());

        // A budget the owner cannot fund creates nothing, and neither can a locked starting balance.
        assertEquals(RuleResult.INSUFFICIENT_FUNDS, rules().add(world.env, TradeWorld.account(owner), RuleSide.BUY, false,
                DIAMOND, 10L, 5, 5_000L, RulesSection.VAULT, null).result());
        assertEquals(RuleResult.INSUFFICIENT_FUNDS, rules().raise(world.env, TradeWorld.account(owner), rule.id(), 5_000L).result());
        UUID newcomer = world.newcomer(2);
        assertEquals(RuleResult.FUNDS_LOCKED, rules().add(world.env, TradeWorld.account(newcomer), RuleSide.BUY, false,
                DIAMOND, 10L, 5, 50L, RulesSection.VAULT, null).result());
        assertEquals(600L, world.balance(owner));
        assertEquals(100L, world.balance(newcomer));
        assertEquals(400L, world.escrow());
        assertEquals(1, rules().size());
    }

    @Test void deletingARulePaysBackItsBudgetAndWhatItsOrderHeld() {
        UUID owner = world.player(1, 1_000L);
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 5, 300L);
        settle();
        // One order for today's five, paid for out of the budget together with its listing fee.
        BuyOrder order = book().buy(rule(rule).orderId());
        assertEquals(5, order.remaining());
        assertEquals(50L, order.escrow());
        assertEquals(OrderSource.RULE, order.source());
        assertEquals(249L, rule(rule).budget());
        assertEquals(299L, world.escrow());

        RuleOutcome deleted = rules().delete(world.env, TradeWorld.account(owner), rule.id());

        assertTrue(deleted.ok());
        assertEquals(299L, deleted.refunded());
        assertFalse(deleted.refundedToClaim());
        // Everything but the one listing fee is back in the balance, and nothing is left behind.
        assertEquals(999L, world.balance(owner));
        assertEquals(0L, world.escrow());
        assertEquals(0, book().size());
        assertEquals(0, rules().size());
        assertEquals(RuleResult.NOT_FOUND, rules().delete(world.env, TradeWorld.account(owner), rule.id()).result());
    }

    // --- buying ------------------------------------------------------------------------------

    @Test void anItemBuyRuleKeepsOneOrderForTodaysQuantityAndStartsAgainTheNextDay() {
        UUID owner = world.player(1, 1_000L);
        UUID seller = world.player(2, 100L);
        world.stock(seller, Lot.of(DIAMOND, 40));
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 5, 300L);
        settle();
        assertEquals(RuleStatus.RUNNING, rule(rule).status());

        // A seller asking less than the rule's limit sells at the resting order's price.
        Placed first = sell(seller, DIAMOND, 3, 8L);
        assertEquals(3, first.filled());
        assertEquals(10L, first.fills().get(0).unitPrice());
        assertEquals(3L, world.held(owner, DIAMOND));
        assertEquals(3, rule(rule).filledToday());
        assertEquals(2, book().buy(rule(rule).orderId()).remaining());

        // The rest of today's quantity fills; what the seller offered beyond it rests on the book.
        Placed second = sell(seller, DIAMOND, 10, 9L);
        assertEquals(2, second.filled());
        assertEquals(8, second.rested());
        settle();
        assertEquals(5, rule(rule).filledToday());
        assertNull(rule(rule).orderId());
        assertEquals(RuleStatus.RUNNING, rule(rule).status());
        assertEquals(50L, rule(rule).done());
        assertEquals(249L, rule(rule).budget());
        assertEquals(5L, world.held(owner, DIAMOND));
        // However often it is looked at today, it buys no more.
        step();
        assertNull(rule(rule).orderId());

        // The next economy-day it buys again, and this time from the order that is waiting at 9:
        // the difference to its limit goes back into the budget, not to the balance.
        world.wall += TradeWorld.DAY;
        step();
        assertEquals(5, rule(rule).filledToday());
        assertEquals(10L, world.held(owner, DIAMOND));
        assertEquals(95L, rule(rule).done());
        assertEquals(249L - 50L - 1L + 5L, rule(rule).budget());
        assertEquals(700L, world.balance(owner));
        assertEquals(3, book().sellsOf(TradeWorld.account(seller)).get(0).lot().count());
    }

    @Test void aBuyRuleStopsWhenItsBudgetCannotBuyAnotherUnit() {
        UUID owner = world.player(1, 100L);
        UUID seller = world.player(2, 100L);
        world.stock(seller, Lot.of(DIAMOND, 40));
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 5, 25L);
        settle();
        // 25 less the listing fee buys two at 10.
        assertEquals(2, book().buy(rule(rule).orderId()).remaining());
        assertEquals(4L, rule(rule).budget());
        world.alerts.clear();

        assertEquals(2, sell(seller, DIAMOND, 2, 10L).filled());
        settle();

        assertEquals(RuleStatus.OUT_OF_BUDGET, rule(rule).status());
        assertEquals(List.of(Map.entry(TradeWorld.account(owner), EconomyAlerts.Kind.RULE_PAUSED)), world.alerts);
        assertNull(rule(rule).orderId());
        // Deleting it pays back the four credits that could not buy anything.
        assertEquals(4L, rules().delete(world.env, TradeWorld.account(owner), rule.id()).refunded());
        assertEquals(79L, world.balance(owner));
    }

    @Test void aBuyRuleBuysNoMoreThanItsInventoryHasRoomFor() {
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        UUID owner = world.player(1, 1_000L);
        world.stock(owner, Lot.of(DIAMOND, 60));
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 20, 500L);
        settle();
        // One stack of 64 with 60 in it: room for four.
        assertEquals(4, book().buy(rule(rule).orderId()).remaining());
        UUID seller = world.player(2, 100L);
        world.stock(seller, Lot.of(DIAMOND, 10));
        assertEquals(4, sell(seller, DIAMOND, 10, 10L).filled());
        world.alerts.clear();
        settle();
        assertEquals(RuleStatus.STORAGE_FULL, rule(rule).status());
        assertEquals(64L, world.held(owner, DIAMOND));
        assertEquals(1, world.alerts.size());
    }

    // --- selling -----------------------------------------------------------------------------

    @Test void aSellRuleListsWhatItsInventoryHoldsUpToTodaysQuantityAndFinishesAtItsTotal() {
        UUID owner = world.player(1, 100L);
        UUID buyer = world.player(2, 1_000L);
        world.stock(owner, Lot.of(DIAMOND, 20));
        TradeRule rule = sellRule(owner, DIAMOND, 6L, 8, 12L);
        settle();
        SellOrder order = book().sell(rule(rule).orderId());
        assertEquals(8, order.lot().count());
        assertEquals(6L, order.unitPrice());
        assertEquals(OrderSource.RULE, order.source());
        // The listing fee came out of the owner's balance; the goods came out of their vault.
        assertEquals(99L, world.balance(owner));
        assertEquals(12L, world.held(owner, DIAMOND));
        assertEquals(0L, world.escrow());

        assertEquals(8, buy(buyer, DIAMOND, 8, 6L).filled());
        settle();
        assertEquals(8L, rule(rule).done());
        assertEquals(47L, world.claim(owner));
        assertEquals(RuleStatus.RUNNING, rule(rule).status());
        assertNull(rule(rule).orderId());

        // The next day it lists what is left of its total, and no more.
        world.wall += TradeWorld.DAY;
        step();
        assertEquals(4, book().sell(rule(rule).orderId()).lot().count());
        world.alerts.clear();
        assertEquals(4, buy(buyer, DIAMOND, 10, 6L).filled());
        settle();
        assertEquals(RuleStatus.FILLED, rule(rule).status());
        assertEquals(12L, rule(rule).done());
        assertEquals(8L, world.held(owner, DIAMOND));
        assertEquals(List.of(Map.entry(TradeWorld.account(owner), EconomyAlerts.Kind.RULE_FILLED)), world.alerts);
        // A filled rule stays filled.
        world.wall += TradeWorld.DAY;
        step();
        assertEquals(RuleStatus.FILLED, rule(rule).status());
        assertNull(rule(rule).orderId());
    }

    @Test void aSellRuleWithNothingToSellSaysSoAndStartsAgainWhenGoodsArrive() {
        UUID owner = world.player(1, 100L);
        TradeRule rule = sellRule(owner, DIAMOND, 6L, 8, 12L);
        settle();
        assertEquals(RuleStatus.NOTHING_TO_SELL, rule(rule).status());
        assertEquals(List.of(Map.entry(TradeWorld.account(owner), EconomyAlerts.Kind.RULE_PAUSED)), world.alerts);
        assertEquals(100L, world.balance(owner));
        world.stock(owner, Lot.of(DIAMOND, 3));
        step();
        assertEquals(RuleStatus.RUNNING, rule(rule).status());
        assertEquals(3, book().sell(rule(rule).orderId()).lot().count());
    }

    @Test void aSellRuleThatCannotPayTheListingFeeKeepsItsGoods() {
        UUID owner = world.player(1, 0L);
        world.stock(owner, Lot.of(DIAMOND, 5));
        TradeRule rule = sellRule(owner, DIAMOND, 6L, 8, 12L);
        settle();
        assertEquals(RuleStatus.OUT_OF_BUDGET, rule(rule).status());
        assertEquals(5L, world.held(owner, DIAMOND));
        assertEquals(0, book().size());
    }

    // --- tags --------------------------------------------------------------------------------

    @Test void aTagBuyRuleSweepsTheCheapestSellOrdersOfItsTagWithoutRestingOnTheBook() {
        UUID owner = world.player(1, 1_000L);
        UUID seller = world.player(2, 100L);
        world.stock(seller, Lot.of(DIAMOND, 20));
        world.stock(seller, Lot.of(GOLD, 20));
        world.stock(seller, Lot.of(IRON, 20));
        sell(seller, DIAMOND, 5, 9L);
        sell(seller, GOLD, 5, 4L);
        sell(seller, IRON, 5, 1L);
        sell(seller, DIAMOND, 5, 12L);
        RuleOutcome added = rules().add(world.env, TradeWorld.account(owner), RuleSide.BUY, true, GEMS, 10L, 8, 200L,
                RulesSection.VAULT, null);
        assertTrue(added.ok());
        settle();
        TradeRule rule = rule(added.rule());
        // The gold at 4 first, then three of the diamonds at 9: eight for the day. Iron is not a gem
        // and the diamonds at 12 are over the limit.
        assertEquals(5L, world.held(owner, GOLD));
        assertEquals(3L, world.held(owner, DIAMOND));
        assertEquals(0L, world.held(owner, IRON));
        assertEquals(8, rule.filledToday());
        assertEquals(47L, rule.done());
        assertEquals(153L, rule.budget());
        assertNull(rule.orderId());
        assertTrue(book().buysOf(TradeWorld.account(owner)).isEmpty());
        // Nothing was posted, so no listing fee was paid: the budget only lost what the goods cost.
        assertEquals(800L, world.balance(owner));
        assertEquals(RuleStatus.RUNNING, rule.status());
        // The seller was paid their own prices less the market fee on each fill.
        assertEquals((20L - 1L) + (27L - 1L), world.claim(seller));

        // The next day only the diamonds at 9 that are left are within its limit.
        world.wall += TradeWorld.DAY;
        step();
        assertEquals(5L, world.held(owner, DIAMOND));
        step();
        assertEquals(RuleStatus.PRICE_NOT_MET, rule(rule).status());
        assertEquals(RuleResult.UNKNOWN_TAG, rules().add(world.env, TradeWorld.account(owner), RuleSide.BUY, true,
                "c:nothing", 10L, 8, 200L, RulesSection.VAULT, null).result());
    }

    @Test void aTagSellRuleListsWhicheverItemOfItsTagTheInventoryHolds() {
        UUID owner = world.player(1, 100L);
        world.stock(owner, Lot.of(IRON, 30));
        world.stock(owner, Lot.of(GOLD, 6));
        RuleOutcome added = rules().add(world.env, TradeWorld.account(owner), RuleSide.SELL, true, GEMS, 7L, 10, 10L,
                RulesSection.VAULT, null);
        settle();
        SellOrder order = book().sell(rule(added.rule()).orderId());
        assertEquals(GOLD, order.item());
        assertEquals(6, order.lot().count());
        assertEquals(30L, world.held(owner, IRON));
    }

    // --- pausing -----------------------------------------------------------------------------

    @Test void pausingARuleTakesItsOrderOffTheBookAndResumingPutsItBack() {
        UUID owner = world.player(1, 1_000L);
        world.stock(owner, Lot.of(IRON, 10));
        TradeRule buying = buyRule(owner, DIAMOND, 10L, 5, 300L);
        TradeRule selling = sellRule(owner, IRON, 3L, 10, 10L);
        settle();
        assertEquals(2, book().size());
        assertEquals(0L, world.held(owner, IRON));
        long escrow = world.escrow();

        assertTrue(rules().setPaused(world.env, TradeWorld.account(owner), selling.id(), true).ok());

        // The sell order's goods are back in the vault; the buy rule is untouched.
        assertEquals(RuleStatus.PAUSED, rule(selling).status());
        assertEquals(10L, world.held(owner, IRON));
        assertEquals(1, book().size());
        settle();
        assertEquals(RuleStatus.PAUSED, rule(selling).status());

        // The switch for all of them: the buy order's escrow is budget again, still in escrow.
        assertEquals(2, rules().setPausedAll(world.env, TradeWorld.account(owner), true));
        assertTrue(rules().pausedAll(TradeWorld.account(owner)));
        assertEquals(0, book().size());
        assertEquals(299L, rule(buying).budget());
        assertEquals(escrow, world.escrow());
        step();
        assertEquals(RuleStatus.PAUSED, rule(buying).status());
        assertEquals(0, book().size());
        // No alert for a pause the owner asked for.
        assertTrue(world.alerts.isEmpty());

        // Switched back on, the rule the owner paused by hand stays paused.
        rules().setPausedAll(world.env, TradeWorld.account(owner), false);
        settle();
        assertEquals(RuleStatus.RUNNING, rule(buying).status());
        assertNotNull(rule(buying).orderId());
        assertEquals(RuleStatus.PAUSED, rule(selling).status());
        assertTrue(rules().setPaused(world.env, TradeWorld.account(owner), selling.id(), false).ok());
        settle();
        assertEquals(10, book().sell(rule(selling).orderId()).lot().count());
        // Somebody else cannot pause it.
        assertEquals(RuleResult.NOT_FOUND, rules().setPaused(world.env, TradeWorld.account(world.player(2, 0L)),
                selling.id(), true).result());
    }

    /** A stand-in for an Auto-Trader block: a small inventory that can be switched unreachable. */
    private static final class Crate implements RuleInventory {
        private final Map<String, Long> held = new TreeMap<>();
        private boolean loaded = true;
        @Override public boolean reachable() { return loaded; }
        @Override public long available(String item) { return held.getOrDefault(item, 0L); }
        @Override public Map<String, Long> goods() { return new TreeMap<>(held); }
        @Override public boolean take(Lot lot) {
            if (available(lot.item()) < lot.count()) return false;
            held.merge(lot.item(), (long) -lot.count(), Long::sum);
            return true;
        }
        @Override public void put(Lot lot) { held.merge(lot.item(), (long) lot.count(), Long::sum); }
        @Override public long room(String item) { return 1_000L; }
    }

    @Test void aRuleBoundToAnInventoryThatCannotBeReachedPausesAndSaysSo() {
        Crate crate = new Crate();
        crate.put(Lot.of(DIAMOND, 9));
        world.env = world.env.withBinder((store, env, owner, binding) -> "block:test".equals(binding) ? crate : null);
        UUID owner = world.player(1, 100L);
        assertEquals(RuleResult.UNKNOWN_BINDING, rules().add(world.env, TradeWorld.account(owner), RuleSide.SELL, false,
                DIAMOND, 6L, 8, 12L, "block:elsewhere", null).result());
        RuleOutcome added = rules().add(world.env, TradeWorld.account(owner), RuleSide.SELL, false, DIAMOND, 6L, 8, 12L,
                "block:test", null);
        assertTrue(added.ok());
        settle();
        // It works from the crate, not from the vault.
        assertEquals(8, book().sell(rule(added.rule()).orderId()).lot().count());
        assertEquals(1L, crate.available(DIAMOND));
        world.alerts.clear();

        crate.loaded = false;
        step();

        // The order is off the book. Its goods cannot go back into the crate, so they are in the
        // owner's vault rather than nowhere.
        assertEquals(RuleStatus.UNREACHABLE, rule(added.rule()).status());
        assertEquals(0, book().size());
        assertEquals(8L, world.held(owner, DIAMOND));
        assertEquals(1L, crate.available(DIAMOND));
        assertEquals(List.of(Map.entry(TradeWorld.account(owner), EconomyAlerts.Kind.RULE_PAUSED)), world.alerts);

        crate.loaded = true;
        step();
        assertEquals(RuleStatus.RUNNING, rule(added.rule()).status());
        assertEquals(1, book().sell(rule(added.rule()).orderId()).lot().count());
    }

    // --- caps --------------------------------------------------------------------------------

    @Test void theDailyValueCapPausesAllOfAnAccountsRulesUntilTheNextDay() {
        world.env = world.env.with(TradeWorld.settings().withRules(8, 100L, 64));
        UUID owner = world.player(1, 5_000L);
        UUID seller = world.player(2, 100L);
        world.stock(seller, Lot.of(DIAMOND, 60));
        world.stock(seller, Lot.of(IRON, 60));
        TradeRule diamonds = buyRule(owner, DIAMOND, 10L, 50, 1_000L);
        TradeRule iron = buyRule(owner, IRON, 10L, 50, 1_000L);
        settle();
        // Each order is sized to what the cap still allows: ten at 10.
        assertEquals(10, book().buy(rule(diamonds).orderId()).remaining());
        assertEquals(10, book().buy(rule(iron).orderId()).remaining());

        // Seven diamonds fill: 70 of the day's 100. Only three of the iron may still fill.
        assertEquals(7, sell(seller, DIAMOND, 7, 10L).filled());
        assertEquals(70L, rules().valueToday(TradeWorld.account(owner), world.now()));
        Placed capped = sell(seller, IRON, 20, 10L);
        assertEquals(3, capped.filled());
        assertEquals(100L, rules().valueToday(TradeWorld.account(owner), world.now()));
        // The cap is reached: nothing more fills, although both orders still rest.
        assertEquals(0, sell(seller, DIAMOND, 3, 10L).filled());
        world.alerts.clear();
        settle();
        assertEquals(RuleStatus.DAILY_CAP, rule(diamonds).status());
        assertEquals(RuleStatus.DAILY_CAP, rule(iron).status());
        assertNull(rule(diamonds).orderId());
        assertFalse(world.alerts.isEmpty());
        assertEquals(7L, world.held(owner, DIAMOND));
        assertEquals(3L, world.held(owner, IRON));

        // The next day both carry on, from the orders that were left waiting — up to the cap again.
        world.wall += TradeWorld.DAY;
        assertEquals(0L, rules().valueToday(TradeWorld.account(owner), world.now()));
        step();
        assertEquals(10L, world.held(owner, DIAMOND));
        assertEquals(10L, world.held(owner, IRON));
        assertEquals(100L, rules().valueToday(TradeWorld.account(owner), world.now()));
        assertEquals(RuleStatus.DAILY_CAP, rule(diamonds).status());
    }

    @Test void rulesAreEvaluatedInTurnAndNeverMoreThanTheBudgetInOneTick() {
        world.env = world.env.with(TradeWorld.settings().withRules(8, 10_000L, 4));
        List<TradeRule> created = new ArrayList<>();
        for (int index = 1; index <= 10; index++)
            created.add(sellRule(world.player(index, 100L), DIAMOND, 6L, 8, 12L));
        assertEquals(10, rules().waiting());

        // Four in the first tick, however often the tick asks; the first four, in the order they joined.
        assertEquals(4, rules().evaluatePending(world.env, 1L));
        assertEquals(0, rules().evaluatePending(world.env, 1L));
        for (int index = 0; index < 10; index++)
            assertEquals(index < 4 ? RuleStatus.NOTHING_TO_SELL : RuleStatus.RUNNING, rule(created.get(index)).status());
        assertEquals(6, rules().waiting());
        // The rest are carried over: four more, then the last two.
        assertEquals(4, rules().evaluatePending(world.env, 2L));
        assertEquals(RuleStatus.NOTHING_TO_SELL, rule(created.get(7)).status());
        assertEquals(RuleStatus.RUNNING, rule(created.get(8)).status());
        assertEquals(2, rules().evaluatePending(world.env, 3L));
        assertEquals(0, rules().waiting());
        assertEquals(0, rules().evaluatePending(world.env, 4L));
        // A book change for the item they trade brings them all back, once.
        UUID seller = world.player(11, 100L);
        world.stock(seller, Lot.of(DIAMOND, 1));
        sell(seller, DIAMOND, 1, 500L);
        assertTrue(rules().hasWork());
        assertEquals(4, rules().evaluatePending(world.env, 5L));
        assertEquals(6, rules().waiting());
    }

    @Test void thePerAccountRuleCapAndMalformedRulesAreRefused() {
        world.env = world.env.with(TradeWorld.settings().withRules(2, 10_000L, 64));
        UUID owner = world.player(1, 1_000L);
        AccountId account = TradeWorld.account(owner);
        sellRule(owner, DIAMOND, 6L, 8, 12L);
        sellRule(owner, IRON, 6L, 8, 12L);
        assertEquals(RuleResult.LIMIT_REACHED, rules().add(world.env, account, RuleSide.SELL, false, GOLD, 6L, 8, 12L,
                RulesSection.VAULT, null).result());
        UUID other = world.player(2, 1_000L);
        AccountId second = TradeWorld.account(other);
        assertEquals(RuleResult.INVALID, rules().add(world.env, second, RuleSide.BUY, false, DIAMOND, 0L, 8, 12L, RulesSection.VAULT, null).result());
        assertEquals(RuleResult.INVALID, rules().add(world.env, second, RuleSide.BUY, false, DIAMOND, 6L, 0, 12L, RulesSection.VAULT, null).result());
        assertEquals(RuleResult.INVALID, rules().add(world.env, second, RuleSide.BUY, false, DIAMOND, 6L, 8, 0L, RulesSection.VAULT, null).result());
        assertEquals(RuleResult.INVALID, rules().add(world.env, second, RuleSide.SELL, false, DIAMOND, 6L, 8,
                RulesSection.MAX_TOTAL_QUANTITY + 1L, RulesSection.VAULT, null).result());
        assertEquals(RuleResult.UNKNOWN_ITEM, rules().add(world.env, second, RuleSide.BUY, false, "nowhere:thing", 6L, 8, 12L,
                RulesSection.VAULT, null).result());
        assertEquals(RuleResult.UNKNOWN_BINDING, rules().add(world.env, second, RuleSide.BUY, false, DIAMOND, 6L, 8, 12L,
                "block:somewhere", null).result());
        assertEquals(1_000L, world.balance(other));
        assertEquals(2, rules().size());
        assertEquals(0, rules().count(second));
    }

    // --- rules feeding each other ------------------------------------------------------------

    /** Everything the two accounts own in credits, wherever it is. */
    private long worth(UUID... players) {
        long total = 0L;
        for (UUID player : players) {
            total += world.balance(player) + world.claim(player);
            for (TradeRule rule : rules().rulesOf(TradeWorld.account(player))) total += rule.budget();
            for (BuyOrder order : book().buysOf(TradeWorld.account(player))) total += order.escrow();
        }
        return total;
    }

    @Test void twoOpposedRulesTradingForManyDaysNeverComeOutAhead() {
        // A price modifier that would halve every buyer's price, paid for by the Reserve — which a
        // rule's order is never given.
        world.env = world.env.with((who, listing, item, base, available) -> Math.max(1L, base / 2L));
        UUID first = world.player(1, 6_000L);
        UUID second = world.player(2, 6_000L);
        world.stock(first, Lot.of(DIAMOND, 200));
        world.stock(second, Lot.of(DIAMOND, 200));
        for (UUID player : List.of(first, second)) {
            buyRule(player, DIAMOND, 10L, 20, 2_000L);
            sellRule(player, DIAMOND, 10L, 20, 100_000L);
        }
        long reserve = world.reserve();
        long before = worth(first, second);
        long traded = 0L;
        for (int day = 0; day < 40; day++) {
            for (int round = 0; round < 4; round++) {
                step();
                // Proceeds are collected, so they can be spent again.
                world.store.market().collectCreditsTotal(first);
                world.store.market().collectCreditsTotal(second);
                long now = worth(first, second);
                assertTrue(now <= before, "day " + day + ": the pair went from " + before + " to " + now);
                before = now;
                assertTrue(world.invariants());
                assertEquals(400L, world.inStore(DIAMOND, 0));
            }
            traded += rules().valueToday(TradeWorld.account(first), world.now());
            world.wall += TradeWorld.DAY;
        }
        // They did trade, every credit they lost is in the Reserve, and the Reserve paid nothing out.
        assertTrue(traded > 1_000L, "the rules never traded");
        assertTrue(before < 12_000L);
        assertEquals(12_000L - before, world.reserve() - reserve);
    }

    // --- erasure, export and the saved form --------------------------------------------------

    @Test void erasingARuleOwnerMidLifeSweepsTheirBudgetAndOrdersAndLeavesEveryoneElseWhole() {
        UUID owner = world.player(1, 1_000L);
        UUID trader = world.player(2, 1_000L);
        world.stock(owner, Lot.of(IRON, 20));
        world.stock(trader, Lot.of(DIAMOND, 20));
        TradeRule buying = buyRule(owner, DIAMOND, 10L, 10, 300L);
        TradeRule selling = sellRule(owner, IRON, 3L, 10, 20L);
        settle();
        // Mid-life: part of each order has filled.
        assertEquals(4, sell(trader, DIAMOND, 4, 10L).filled());
        assertEquals(6, buy(trader, IRON, 6, 3L).filled());
        long traderClaim = world.claim(trader);
        long reserve = world.reserve();
        long heldForOwner = rule(buying).budget() + book().buy(rule(buying).orderId()).escrow() + world.claim(owner)
                + world.balance(owner);

        // The game's erasers, in the order they are registered.
        List<Lot> destroyed = new ArrayList<>(book().forgetPlayer(owner));
        destroyed.addAll(world.store.offers().forgetPlayer(world.env, owner));
        rules().forgetPlayer(owner);
        world.store.market().forgetPlayer(owner);
        destroyed.addAll(world.store.vault().forgetPlayer(owner));
        world.ledger().forgetPlayer(owner);

        assertEquals(0, rules().size());
        assertEquals(0, book().size());
        assertFalse(rules().hasWork() && rules().waiting() > 0);
        assertNull(rules().rule(buying.id()));
        assertNull(rules().rule(selling.id()));
        // Every credit that was the erased player's is in the Reserve; the counterparty's claim is intact.
        assertEquals(reserve + heldForOwner, world.reserve());
        assertEquals(traderClaim, world.claim(trader));
        assertEquals(traderClaim, world.escrow());
        assertEquals(6L, world.held(trader, IRON));
        // Their goods are gone: the iron still on the book, and the vault with what the rule had bought.
        long iron = 0L;
        long diamonds = 0L;
        for (Lot lot : destroyed) {
            if (lot.item().equals(IRON)) iron += lot.count();
            else diamonds += lot.count();
        }
        assertEquals(14L, iron);
        assertEquals(4L, diamonds);
        // And nothing that happens afterwards trips over what is gone.
        step();
        assertEquals(0, sell(trader, DIAMOND, 1, 10L).filled());
        assertEquals(0, rules().exportFor(owner).getAsJsonArray("rules").size());
    }

    @Test void rulesSurviveASaveWithTheirOrdersCountersAndTheirPlaceInTheQueue() {
        UUID owner = world.player(1, 1_000L);
        UUID seller = world.player(2, 100L);
        world.stock(seller, Lot.of(DIAMOND, 20));
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 5, 300L);
        settle();
        assertEquals(2, sell(seller, DIAMOND, 2, 10L).filled());
        TradeRule waiting = sellRule(owner, IRON, 3L, 10, 20L);
        rules().setPausedAll(world.env, TradeWorld.account(world.player(3, 0L)), true);
        assertTrue(rules().waiting() > 0);
        TradeRule before = rule(rule);
        int queued = rules().waiting();

        world.reload();

        assertTrue(world.invariants());
        TradeRule after = rule(rule);
        assertEquals(before, after);
        assertEquals(queued, rules().waiting());
        assertTrue(rules().pausedAll(TradeWorld.account(world.player(3, 0L))));
        assertEquals(20L, rules().valueToday(TradeWorld.account(owner), world.now()));
        // The rule still knows its order: a fill after the reload is counted.
        assertEquals(3, sell(seller, DIAMOND, 3, 10L).filled());
        assertEquals(5, rule(rule).filledToday());
        settle();
        assertNull(rule(rule).orderId());
        assertEquals(RuleStatus.NOTHING_TO_SELL, rule(waiting).status());
    }

    @Test void aRulesOrderCannotBeCancelledByHandAndAnExpiredOneIsPlacedAgainFromTheBudget() {
        UUID owner = world.player(1, 1_000L);
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 5, 300L);
        settle();
        UUID orderId = rule(rule).orderId();
        assertEquals(CancelResult.NOT_CANCELLABLE, book().cancel(world.env, orderId, TradeWorld.account(owner)));
        assertNotNull(book().buy(orderId));

        world.wall += 49L * TradeWorld.HOUR;
        assertEquals(1, book().expire(world.env, world.now()));
        // The order's escrow went back to the budget, not to a claim.
        assertEquals(299L, rule(rule).budget());
        assertEquals(0L, world.claim(owner));
        settle();
        // It is placed again, and pays the listing fee again.
        assertNotEquals(orderId, rule(rule).orderId());
        assertEquals(248L, rule(rule).budget());
        assertEquals(700L, world.balance(owner));
    }

    @Test void theExportListsTheOwnersRulesAndNothingOfAnyoneElses() {
        UUID owner = world.player(1, 1_000L);
        UUID other = world.player(2, 1_000L);
        TradeRule rule = buyRule(owner, DIAMOND, 10L, 5, 300L);
        buyRule(other, IRON, 4L, 5, 100L);
        rules().setPausedAll(world.env, TradeWorld.account(owner), true);

        JsonObject export = rules().exportFor(owner);

        assertTrue(export.get("rules_paused").getAsBoolean());
        assertEquals(1, export.getAsJsonArray("rules").size());
        JsonObject row = export.getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals(rule.id().toString(), row.get("id").getAsString());
        assertEquals("BUY", row.get("side").getAsString());
        assertEquals(DIAMOND, row.get("item").getAsString());
        assertEquals(300L, row.get("budget").getAsLong());
        assertEquals("vault", row.get("binding").getAsString());
        String text = export.toString();
        assertFalse(text.contains(other.toString()));
        assertFalse(text.contains(owner.toString()));
        assertFalse(text.contains(IRON));
    }
}
