package za.co.neroland.neroeconomy.trader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleResult;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RuleStatus;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * One rule bound to an Auto-Trader, against a real store and a plain array of slots: what a sell
 * rule lists and a buy rule delivers, what happens when the block's chunk unloads and loads again,
 * when the output slots are full, when the input slots run dry, when the block is removed while
 * an order rests, and when its owner is erased. Every test ends with both ledger invariants
 * holding.
 */
class TraderRulesTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private static final String IRON = TradeWorld.IRON;
    private static final String HERE = "minecraft:overworld|1";
    private static final String THERE = "minecraft:the_nether|2";

    private final TradeWorld world = new TradeWorld();
    /** The Auto-Trader blocks the world has, by location: what the game's chunks are to the binder. */
    private final Map<String, FakeSlots> blocks = new HashMap<>();
    /** Owners who are in creative or spectator mode right now: what the game's players are to the binder. */
    private final Set<UUID> noSource = new HashSet<>();
    private long tick;

    TraderRulesTest() {
        world.env = world.env.withBinder(TraderRules.binder(blocks::get, owner -> !noSource.contains(owner)));
    }

    @AfterEach void done() {
        assertTrue(world.invariants(), "the ledger invariants must hold at the end of every test");
        world.close();
    }

    private RulesSection rules() { return world.store.rules(); }
    private BookSection book() { return world.store.book(); }

    /** A player places an Auto-Trader: the block exists, loaded, and they own it. */
    private FakeSlots place(String location, UUID owner) {
        FakeSlots slots = new FakeSlots();
        blocks.put(location, slots);
        assertTrue(world.store.autoTraders().claim(location, owner));
        return slots;
    }

    /** Ticks until no rule is waiting any more. */
    private void settle() {
        for (int round = 0; round < 50 && rules().hasWork(); round++) rules().evaluatePending(world.env, ++tick);
        assertFalse(rules().hasWork(), "rules kept queueing themselves");
    }

    /** What the block does when its slots change, or its chunk loads or unloads: its rule looks again. */
    private void wake(String location) {
        for (TradeRule rule : rules().boundTo(TraderRules.binding(location))) rules().enqueue(rule.id());
        settle();
    }

    private TradeRule apply(UUID owner, String location, RuleSide side, String item, long limit, int perDay, long total) {
        RuleOutcome applied = TraderRules.apply(world.store, world.env, TradeWorld.account(owner), location, side, false,
                item, limit, perDay, total);
        assertTrue(applied.ok(), applied.result().name());
        settle();
        return rule(location);
    }

    /** The rule the block at a location has right now, or null. */
    private TradeRule rule(String location) {
        List<TradeRule> bound = rules().boundTo(TraderRules.binding(location));
        assertTrue(bound.size() <= 1, "a block has at most one rule");
        return bound.isEmpty() ? null : bound.get(0);
    }

    private Placed sell(UUID seller, String item, int count, long price) {
        AccountId account = TradeWorld.account(seller);
        world.stock(seller, Lot.of(item, count));
        return book().placeSell(world.env, account, Lot.of(item, count), price, OrderSource.VAULT,
                VaultGoods.origin(world.store, world.env, account), null);
    }

    private Placed buy(UUID buyer, String item, int count, long limit) {
        AccountId account = TradeWorld.account(buyer);
        return book().placeBuy(world.env, account, item, count, limit, OrderSource.VAULT, false,
                VaultGoods.receiver(world.store, world.env, account), null);
    }

    // --- selling from the input slots --------------------------------------------------------

    @Test void aSellRuleTakesNothingFromTheSlotsWhileItsOwnerIsInCreativeAndListsAgainOnceTheyAreNot() {
        UUID alice = world.player(1, 100L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 40);
        noSource.add(alice);
        TradeRule rule = apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);
        assertNull(rule.orderId(), "nothing reaches the market from a creative owner's Auto-Trader");
        assertEquals(40L, slots.inputs(DIAMOND));
        assertEquals(0L, world.inStore(DIAMOND, Lot.PRISTINE));
        noSource.remove(alice);
        wake(HERE);
        assertNotNull(rule(HERE).orderId(), "listed once the owner is a goods source again");
        assertEquals(0L, slots.inputs(DIAMOND));
    }

    @Test void aBuyRuleStillDeliversIntoTheSlotsWhileItsOwnerIsInCreative() {
        UUID alice = world.player(1, 1_000L);
        UUID bob = world.player(2, 100L);
        FakeSlots slots = place(HERE, alice);
        noSource.add(alice);
        apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 64, 100L);
        assertTrue(sell(bob, DIAMOND, 5, 10L).ok());
        settle();
        assertEquals(5L, slots.outputs(DIAMOND));
    }

    @Test void aSellRuleListsTheUnmodifiedGoodsInTheInputSlotsAndNothingElse() {
        UUID alice = world.player(1, 100L);
        UUID bob = world.player(2, 1_000L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 40).input(5, IRON, 7);
        // Diamonds in an output slot, and an enchanted one in an input slot: neither is for sale.
        slots.held[12] = Lot.of(DIAMOND, 9);
        slots.held[3] = Lot.of(DIAMOND, 1);
        slots.modified.add(3);

        TradeRule rule = apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);

        SellOrder order = book().sell(rule.orderId());
        assertEquals(Lot.of(DIAMOND, 40), order.lot());
        assertEquals(OrderSource.RULE, order.source());
        assertEquals(TraderRules.binding(HERE), rule.binding());
        // The goods left the slots for the book; the listing fee left the balance; nothing was escrowed.
        assertEquals(0L, slots.inputs(DIAMOND));
        assertEquals(40L, world.inStore(DIAMOND, Lot.PRISTINE));
        assertEquals(7L, slots.inputs(IRON));
        assertEquals(Lot.of(DIAMOND, 9), slots.held[12]);
        assertTrue(slots.modified.contains(3));
        assertEquals(99L, world.balance(alice));
        assertEquals(0L, world.escrow());
        assertEquals(0L, world.held(alice, DIAMOND));

        // A buyer takes fifteen: the proceeds wait for the owner, less the market fee.
        assertEquals(15, buy(bob, DIAMOND, 15, 10L).filled());
        settle();
        assertEquals(15L, rule(HERE).done());
        assertEquals(147L, world.claim(alice));
        assertEquals(15L, world.held(bob, DIAMOND));
        assertEquals(25, book().sell(rule(HERE).orderId()).lot().count());
    }

    @Test void whenTheInputSlotsRunDryTheRuleHasNothingToSellAndStartsAgainWhenGoodsArrive() {
        UUID alice = world.player(1, 100L);
        UUID bob = world.player(2, 1_000L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 5);
        apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);
        world.alerts.clear();

        assertEquals(5, buy(bob, DIAMOND, 5, 10L).filled());
        settle();

        assertEquals(RuleStatus.NOTHING_TO_SELL, rule(HERE).status());
        assertNull(rule(HERE).orderId());
        assertEquals(5L, rule(HERE).done());
        assertEquals(0, book().size());
        assertEquals(List.of(Map.entry(TradeWorld.account(alice), EconomyAlerts.Kind.RULE_PAUSED)), world.alerts);
        // Goods that were put in and taken out again before the rule looked were never listed.
        int writes = slots.writes;
        slots.input(0, DIAMOND, 3);
        slots.held[0] = null;
        wake(HERE);
        assertEquals(RuleStatus.NOTHING_TO_SELL, rule(HERE).status());
        assertEquals(writes, slots.writes);
        // A hopper delivers more: the rule lists it at its next look.
        slots.input(4, DIAMOND, 12);
        wake(HERE);
        assertEquals(RuleStatus.RUNNING, rule(HERE).status());
        assertEquals(12, book().sell(rule(HERE).orderId()).lot().count());
        assertEquals(0L, slots.inputs(DIAMOND));
    }

    // --- buying into the output slots --------------------------------------------------------

    @Test void aBuyRuleDeliversIntoTheOutputSlotsAndStopsWhenTheyAreFull() {
        UUID alice = world.player(1, 1_000L);
        UUID carol = world.player(3, 100L);
        FakeSlots slots = place(HERE, alice);
        for (int slot = TraderInventory.INPUTS; slot < TraderInventory.SLOTS - 1; slot++) slots.held[slot] = Lot.of(IRON, 64);
        slots.held[17] = Lot.of(DIAMOND, 60);

        TradeRule rule = apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 20, 500L);

        // Eight output slots hold something else and the ninth has room for four: it asks for four.
        assertEquals(4, book().buy(rule.orderId()).remaining());
        assertEquals(500L, world.balance(alice));
        assertEquals(499L, world.escrow());

        assertEquals(4, sell(carol, DIAMOND, 10, 10L).filled());
        // The fill that completes the order is delivered to the block like any other, not to the vault.
        assertEquals(Lot.of(DIAMOND, 64), slots.held[17]);
        assertEquals(0L, world.held(alice, DIAMOND));
        world.alerts.clear();
        settle();

        assertEquals(Lot.of(DIAMOND, 64), slots.held[17]);
        assertEquals(0L, slots.inputs(DIAMOND));
        assertEquals(0L, world.held(alice, DIAMOND));
        assertEquals(RuleStatus.STORAGE_FULL, rule(HERE).status());
        assertNull(rule(HERE).orderId());
        assertEquals(List.of(Map.entry(TradeWorld.account(alice), EconomyAlerts.Kind.RULE_PAUSED)), world.alerts);
        // What is left of the budget is still in escrow; the only other credits there are the
        // seller's proceeds, waiting to be collected.
        assertEquals(459L, rule(HERE).budget());
        assertEquals(39L, world.claim(carol));
        assertEquals(459L + 39L, world.escrow());

        // A hopper empties that slot: the rule buys again, and takes what the seller still offers.
        slots.held[17] = null;
        wake(HERE);
        assertEquals(6L, slots.outputs(DIAMOND));
        assertEquals(RuleStatus.RUNNING, rule(HERE).status());
        assertEquals(10, book().buy(rule(HERE).orderId()).remaining());
    }

    @Test void theFillThatCompletesABuyRulesOrderIsDeliveredToTheBlockWhicheverWayItIsFilled() {
        UUID alice = world.player(1, 1_000L);
        UUID carol = world.player(3, 100L);
        FakeSlots slots = place(HERE, alice);
        TradeRule rule = apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 6, 500L);
        assertEquals(6, book().buy(rule.orderId()).remaining());
        world.stock(carol, Lot.of(DIAMOND, 6));
        AccountId seller = TradeWorld.account(carol);

        // Somebody fills the order by hand at a terminal, in two parts; the second completes it.
        assertTrue(book().sellTo(world.env, seller, rule.orderId(), Lot.of(DIAMOND, 2),
                VaultGoods.origin(world.store, world.env, seller)).ok());
        assertEquals(2L, slots.outputs(DIAMOND));
        assertTrue(book().sellTo(world.env, seller, rule.orderId(), Lot.of(DIAMOND, 4),
                VaultGoods.origin(world.store, world.env, seller)).ok());

        assertEquals(6L, slots.outputs(DIAMOND));
        assertEquals(0L, world.held(alice, DIAMOND));
        assertEquals(0L, world.held(carol, DIAMOND));
        settle();
        // Today's quantity is done: it carries on tomorrow, with nothing on the book meanwhile.
        assertEquals(RuleStatus.RUNNING, rule(HERE).status());
        assertNull(rule(HERE).orderId());
        assertEquals(6, rule(HERE).filledToday());
    }

    // --- the chunk unloading and loading -----------------------------------------------------

    @Test void whenTheChunkUnloadsASellRuleTakesItsOrderOffTheBookAndSaysSo() {
        UUID alice = world.player(1, 100L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 40);
        apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);
        assertEquals(1, book().size());
        int writes = slots.writes;
        world.alerts.clear();

        slots.loaded = false;
        wake(HERE);

        assertEquals(RuleStatus.UNREACHABLE, rule(HERE).status());
        assertNull(rule(HERE).orderId());
        assertEquals(0, book().size());
        // The goods it had listed cannot go back into a block nobody may touch: they are in the
        // owner's vault, and the slots were not written.
        assertEquals(40L, world.held(alice, DIAMOND));
        assertEquals(writes, slots.writes);
        assertEquals(List.of(Map.entry(TradeWorld.account(alice), EconomyAlerts.Kind.RULE_PAUSED)), world.alerts);
        // Looked at again while still unloaded, nothing changes and nothing more is said.
        rules().enqueueAll();
        settle();
        assertEquals(RuleStatus.UNREACHABLE, rule(HERE).status());
        assertEquals(1, world.alerts.size());

        // The chunk loads again. Its inputs are empty until something arrives.
        slots.loaded = true;
        wake(HERE);
        assertEquals(RuleStatus.NOTHING_TO_SELL, rule(HERE).status());
        slots.input(2, DIAMOND, 10);
        wake(HERE);
        assertEquals(RuleStatus.RUNNING, rule(HERE).status());
        assertEquals(10, book().sell(rule(HERE).orderId()).lot().count());
    }

    @Test void whenTheChunkUnloadsABuyRuleTakesItsOrderOffTheBookAndKeepsItsBudgetInEscrow() {
        UUID alice = world.player(1, 1_000L);
        FakeSlots slots = place(HERE, alice);
        TradeRule rule = apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 20, 500L);
        assertEquals(20, book().buy(rule.orderId()).remaining());
        assertEquals(299L, rule.budget());

        slots.loaded = false;
        wake(HERE);

        assertEquals(RuleStatus.UNREACHABLE, rule(HERE).status());
        assertNull(rule(HERE).orderId());
        assertEquals(0, book().size());
        // The order's escrow is budget again; only the listing fee is gone.
        assertEquals(499L, rule(HERE).budget());
        assertEquals(499L, world.escrow());
        assertEquals(500L, world.balance(alice));

        slots.loaded = true;
        wake(HERE);
        assertEquals(RuleStatus.RUNNING, rule(HERE).status());
        assertEquals(20, book().buy(rule(HERE).orderId()).remaining());
        assertEquals(298L, rule(HERE).budget());
        assertEquals(0, slots.writes);
    }

    @Test void aPurchaseThatArrivesWhileTheBlockIsUnloadedGoesToTheOwnersVault() {
        UUID alice = world.player(1, 1_000L);
        UUID carol = world.player(3, 100L);
        FakeSlots slots = place(HERE, alice);
        apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 20, 500L);

        // The chunk has stopped ticking and the rule has not been looked at since: its order still
        // rests, and somebody sells into it.
        slots.loaded = false;
        assertEquals(5, sell(carol, DIAMOND, 5, 10L).filled());

        assertEquals(5L, world.held(alice, DIAMOND));
        assertEquals(0, slots.writes);
        settle();
        assertEquals(RuleStatus.UNREACHABLE, rule(HERE).status());
        assertEquals(0, book().size());
        // The rest of the order's escrow is budget again; the seller's proceeds wait to be collected.
        assertEquals(449L, rule(HERE).budget());
        assertEquals(449L + world.claim(carol), world.escrow());
    }

    // --- removal -----------------------------------------------------------------------------

    @Test void removingTheBlockWhileASellOrderRestsDeletesTheRuleAndVaultsWhatItHadListed() {
        UUID alice = world.player(1, 100L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 40).input(5, IRON, 7);
        slots.held[10] = Lot.of(IRON, 3);
        apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);
        assertEquals(1, book().size());
        int writes = slots.writes;

        // The block is being torn down: nothing in it can be reached any more.
        slots.loaded = false;
        assertTrue(TraderRules.release(world.store, world.env, HERE));

        assertNull(rule(HERE));
        assertEquals(0, rules().size());
        assertEquals(0, book().size());
        assertEquals(40L, world.held(alice, DIAMOND));
        assertNull(world.store.autoTraders().owner(HERE));
        // What the slots hold was never the store's: it is left for the block to drop, like a chest's.
        assertEquals(writes, slots.writes);
        assertEquals(7L, slots.inputs(IRON));
        assertEquals(3L, slots.outputs(IRON));
        assertEquals(0L, world.held(alice, IRON));
        // Every removal path may report it; the second report finds nothing.
        assertFalse(TraderRules.release(world.store, world.env, HERE));
        assertFalse(TraderRules.release(world.store, world.env, THERE));
        assertFalse(TraderRules.release(world.store, world.env, null));
    }

    @Test void removingTheBlockWhileABuyOrderRestsDeletesTheRuleAndRefundsItsBudget() {
        UUID alice = world.player(1, 1_000L);
        FakeSlots slots = place(HERE, alice);
        apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 20, 500L);
        assertEquals(1, book().size());
        assertEquals(500L, world.balance(alice));

        slots.loaded = false;
        assertTrue(TraderRules.release(world.store, world.env, HERE));

        // The whole budget is back, less the one listing fee its order paid.
        assertEquals(999L, world.balance(alice));
        assertEquals(0L, world.escrow());
        assertEquals(0L, rules().escrowObligations());
        assertEquals(0, rules().size());
        assertEquals(0, book().size());
        assertNull(world.store.autoTraders().owner(HERE));
        assertEquals(0, slots.writes);
    }

    @Test void aBlockThatIsGoneWithoutSayingSoIsReleasedByPosition() {
        UUID alice = world.player(1, 100L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 40);
        apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);

        // A command swapped the block out: nothing is at that position any more, and the sweep
        // settles the records without it.
        blocks.remove(HERE);
        assertTrue(TraderRules.release(world.store, world.env, HERE));

        assertEquals(0, rules().size());
        assertEquals(0, book().size());
        assertEquals(40L, world.held(alice, DIAMOND));
        assertNull(world.store.autoTraders().owner(HERE));
    }

    // --- whose block it is -------------------------------------------------------------------

    @Test void onlyTheOwnerOfABlockCanBindARuleToIt() {
        UUID alice = world.player(1, 1_000L);
        UUID bob = world.player(2, 1_000L);
        place(HERE, alice);

        assertEquals(RuleResult.UNKNOWN_BINDING, TraderRules.apply(world.store, world.env, TradeWorld.account(bob), HERE,
                RuleSide.BUY, false, DIAMOND, 10L, 20, 500L).result());
        // A position nobody owns a block at backs nothing either.
        assertEquals(RuleResult.UNKNOWN_BINDING, TraderRules.apply(world.store, world.env, TradeWorld.account(alice), THERE,
                RuleSide.BUY, false, DIAMOND, 10L, 20, 500L).result());
        assertEquals(1_000L, world.balance(bob));
        assertEquals(1_000L, world.balance(alice));
        assertEquals(0, rules().size());
        assertThrows(IllegalArgumentException.class, () -> TraderRules.binder(null));
        // A binding that is not an Auto-Trader's is not this binder's to know.
        assertNull(world.env.ruleBinder().resolve(world.store, world.env, TradeWorld.account(alice), "chest:" + HERE));
        assertNull(world.env.ruleBinder().resolve(world.store, world.env, TradeWorld.account(alice), null));
        assertNotNull(world.env.ruleBinder().resolve(world.store, world.env, TradeWorld.account(alice),
                TraderRules.binding(HERE)));
    }

    @Test void aRuleLeftBehindAtAPositionSomebodyElseNowOwnsCanReachNothingOfTheirs() {
        UUID alice = world.player(1, 100L);
        UUID bob = world.player(2, 100L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 40);
        apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);

        // A new block at the same position, placed by somebody else, with their goods in it.
        FakeSlots theirs = place(HERE, bob);
        theirs.input(0, DIAMOND, 30);
        wake(HERE);

        // The old rule reaches nothing: its order is off the book and its goods are its owner's.
        assertEquals(RuleStatus.UNREACHABLE, rule(HERE).status());
        assertEquals(0, book().size());
        assertEquals(40L, world.held(alice, DIAMOND));
        assertEquals(30L, theirs.inputs(DIAMOND));
        assertEquals(0, theirs.writes);
    }

    @Test void applyingANewRuleReturnsTheOldOneFirst() {
        UUID alice = world.player(1, 1_000L);
        FakeSlots slots = place(HERE, alice);
        apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 20, 600L);
        assertEquals(400L, world.balance(alice));

        // The old budget pays for the new one: 900 could not be found beside the 600 in escrow.
        TradeRule replaced = apply(alice, HERE, RuleSide.BUY, DIAMOND, 10L, 20, 900L);
        assertEquals(1, rules().count(TradeWorld.account(alice)));
        assertEquals(900L, replaced.total());
        assertEquals(99L, world.balance(alice));
        assertEquals(899L, world.escrow());

        // A rule that cannot be created leaves the block with none, and the old one paid back in full.
        assertEquals(RuleResult.INSUFFICIENT_FUNDS, TraderRules.apply(world.store, world.env, TradeWorld.account(alice), HERE,
                RuleSide.BUY, false, DIAMOND, 10L, 20, 5_000L).result());
        assertNull(rule(HERE));
        assertEquals(998L, world.balance(alice));
        assertEquals(0L, world.escrow());
        assertEquals(0, book().size());

        // From selling to buying: what the sell rule had listed comes back into the input slots.
        slots.input(0, DIAMOND, 40);
        apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);
        assertEquals(0L, slots.inputs(DIAMOND));
        TradeRule buying = apply(alice, HERE, RuleSide.BUY, IRON, 5L, 10, 100L);
        assertTrue(buying.buys());
        assertEquals(40L, slots.inputs(DIAMOND));
        assertEquals(0L, world.held(alice, DIAMOND));
        assertEquals(1, rules().count(TradeWorld.account(alice)));
    }

    // --- erasure and the saved form ----------------------------------------------------------

    @Test void erasingTheOwnerLeavesTheirBlocksOwnerlessWithNoRule() {
        UUID alice = world.player(1, 1_000L);
        UUID bob = world.player(2, 1_000L);
        FakeSlots selling = place(HERE, alice);
        selling.input(0, DIAMOND, 40).input(5, IRON, 7);
        place(THERE, alice);
        FakeSlots bobs = place("minecraft:overworld|9", bob);
        bobs.input(0, IRON, 20);
        apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);
        apply(alice, THERE, RuleSide.BUY, DIAMOND, 10L, 20, 500L);
        apply(bob, "minecraft:overworld|9", RuleSide.SELL, IRON, 5L, 64, 100L);
        long reserve = world.reserve();
        assertEquals(2, world.store.autoTraders().exportFor(alice).size());

        // The order the game erases the trading sections in: the book, the rules, the owner index.
        assertEquals(List.of(Lot.of(DIAMOND, 40)), world.store.book().forgetPlayer(alice));
        rules().forgetPlayer(alice);
        assertEquals(List.of(HERE, THERE), world.store.autoTraders().forgetPlayer(alice));

        assertNull(rule(HERE));
        assertNull(rule(THERE));
        assertNull(world.store.autoTraders().owner(HERE));
        assertNull(world.store.autoTraders().owner(THERE));
        assertEquals(0, world.store.autoTraders().exportFor(alice).size());
        assertEquals(0, rules().count(TradeWorld.account(alice)));
        // Her buy rule's budget and its order's escrow went to the Reserve; nothing is owed to her.
        assertEquals(0L, world.escrow());
        assertEquals(reserve + 499L, world.reserve());
        // The blocks stay, with whatever is in their slots, and back no rule of hers any more.
        assertEquals(7L, selling.inputs(IRON));
        assertNull(world.env.ruleBinder().resolve(world.store, world.env, TradeWorld.account(alice), TraderRules.binding(HERE)));
        // Somebody else's Auto-Trader is untouched.
        assertEquals(bob, world.store.autoTraders().owner("minecraft:overworld|9"));
        assertEquals(RuleStatus.RUNNING, rule("minecraft:overworld|9").status());
        assertEquals(1, book().size());
    }

    @Test void aBoundRuleAndItsOrderSurviveASaveAndALoad() {
        UUID alice = world.player(1, 100L);
        FakeSlots slots = place(HERE, alice);
        slots.input(0, DIAMOND, 40);
        TradeRule before = apply(alice, HERE, RuleSide.SELL, DIAMOND, 10L, 64, 100L);

        world.reload();

        TradeRule after = rule(HERE);
        assertEquals(before, after);
        assertEquals(alice, world.store.autoTraders().owner(HERE));
        assertEquals(Lot.of(DIAMOND, 40), book().sell(after.orderId()).lot());
        // And it still notices its block unloading.
        slots.loaded = false;
        wake(HERE);
        assertEquals(RuleStatus.UNREACHABLE, rule(HERE).status());
        assertEquals(40L, world.held(alice, DIAMOND));
    }
}
