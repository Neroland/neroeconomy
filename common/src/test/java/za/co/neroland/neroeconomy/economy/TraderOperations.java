package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleResult;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RuleStatus;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trader.FakeSlots;
import za.co.neroland.neroeconomy.trader.TraderInventory;
import za.co.neroland.neroeconomy.trader.TraderRules;

/**
 * The fuzzer's Auto-Trader operations: a rule bound to a block's slots through the real binder
 * ({@link TraderRules}), with the block played by a plain array ({@link FakeSlots}). Blocks are
 * placed, given a rule, fed and emptied as a hopper or their owner would, unloaded and loaded,
 * broken, and swapped out by a command. The rules act when they are evaluated — by the fuzzer's
 * rule tick and by the simulation step — through the same order book everything else trades on.
 *
 * <p>The slots are in the world, not in the store, so their contents are counted where they lie
 * ({@link FuzzWorld#check}); an operation here says when goods cross between a hand and a slot,
 * and nothing else. Whatever a rule moves between the slots, the book and a vault must then add
 * up by itself.
 */
final class TraderOperations {
    private TraderOperations() { }

    /** The positions an Auto-Trader can stand at; few, so that they are placed, broken and placed again. */
    private static final String[] LOCATIONS = {"fuzz:world|1", "fuzz:world|2", "fuzz:world|3", "fuzz:world|4",
            "fuzz:nether|1", "fuzz:nether|2"};
    /** Enough ticks for every rule waiting to have its turn. */
    private static final int SETTLE_TICKS = 64;

    static List<FuzzOperation> all() {
        return List.of(new Place(), new Apply(), new Load(), new Insert(), new Extract(), new Remove());
    }

    /** A block that stands somewhere, or null when none does. */
    private static String standing(FuzzWorld world) {
        if (world.traders.isEmpty()) return null;
        List<String> locations = new ArrayList<>(world.traders.keySet());
        return locations.get(world.random.nextInt(locations.size()));
    }

    private static List<TradeRule> bound(FuzzWorld world, String location) {
        return world.store.rules().boundTo(TraderRules.binding(location));
    }

    /** What the block does when its slots change, or its chunk loads or unloads: its rule is to look again. */
    private static void wake(FuzzWorld world, String location) {
        for (TradeRule rule : bound(world, location)) world.store.rules().enqueue(rule.id());
    }

    /** Ticks pass until no rule is waiting. @return whether every rule has had its turn */
    private static boolean settle(FuzzWorld world) {
        RulesSection rules = world.store.rules();
        for (int tick = 0; tick < SETTLE_TICKS && rules.hasWork(); tick++) rules.evaluatePending(world.env, -(++world.ruleTicks));
        return !rules.hasWork();
    }

    private static long balance(FuzzWorld world, UUID player) {
        return world.ledger().getBalance(player, NeroEconomyCommon.CREDITS);
    }

    /** A player places an Auto-Trader: it is theirs, loaded, empty and without a rule. */
    static final class Place implements FuzzOperation {
        @Override public String name() { return "trader_place"; }
        @Override public int weight() { return 8; }
        @Override public long run(FuzzWorld world) {
            String location = LOCATIONS[world.random.nextInt(LOCATIONS.length)];
            if (world.traders.containsKey(location)) return 0L;
            UUID player = world.player();
            // Nothing is recorded where no block stands: every removal settled its records.
            assertNull(world.store.autoTraders().owner(location));
            FakeSlots slots = new FakeSlots();
            slots.limits = item -> FuzzWorld.LIMIT.limit(item, Lot.PRISTINE);
            world.traders.put(location, slots);
            assertTrue(world.store.autoTraders().claim(location, player));
            return 1L;
        }
    }

    /** The owner sets the block's rule, replacing the one it has — or somebody else tries to. */
    static final class Apply implements FuzzOperation {
        @Override public String name() { return "trader_apply"; }
        @Override public int weight() { return 25; }
        @Override public long run(FuzzWorld world) {
            String location = standing(world);
            if (location == null) return 0L;
            UUID owner = world.store.autoTraders().owner(location);
            RuleSide side = world.random.nextBoolean() ? RuleSide.BUY : RuleSide.SELL;
            boolean tag = world.random.nextInt(4) == 0;
            String target = tag ? (world.random.nextBoolean() ? FuzzWorld.TAG_GEMS : FuzzWorld.TAG_BULK)
                    : FuzzWorld.ITEMS[world.random.nextInt(FuzzWorld.ITEMS.length)];
            long limit = world.random.nextInt(25) == 0 ? 0L : BookOperations.price(world);
            int perDay = world.random.nextInt(25);
            long total = side == RuleSide.BUY ? world.random.nextInt(500) : world.random.nextInt(120);
            UUID stranger = world.player();
            if (owner == null || world.random.nextInt(8) == 0) {
                // A block with no owner, or somebody else's, backs no rule of this player's.
                if (stranger.equals(owner)) return 0L;
                long before = balance(world, stranger);
                assertEquals(RuleResult.UNKNOWN_BINDING, TraderRules.apply(world.store, world.env, FuzzWorld.account(stranger),
                        location, RuleSide.BUY, false, FuzzWorld.ITEMS[0], 5L, 5, 50L).result());
                assertEquals(before, balance(world, stranger));
                return 0L;
            }
            AccountId account = FuzzWorld.account(owner);
            RuleOutcome applied = TraderRules.apply(world.store, world.env, account, location, side, tag, target, limit,
                    perDay, total);
            // A block never has two rules, whatever became of the new one.
            assertTrue(bound(world, location).size() <= 1);
            if (!applied.ok()) return 0L;
            assertEquals(side == RuleSide.BUY ? total : 0L, applied.escrowed());
            assertEquals(applied.rule().id(), bound(world, location).get(0).id());
            return 1L + applied.escrowed();
        }
    }

    /**
     * The block's chunk unloads, or loads again. Unloaded, its rule must take its order off the
     * book and say that it cannot reach its inventory; the slots are not touched meanwhile.
     */
    static final class Load implements FuzzOperation {
        @Override public String name() { return "trader_load"; }
        @Override public int weight() { return 10; }
        @Override public long run(FuzzWorld world) {
            String location = standing(world);
            if (location == null) return 0L;
            FakeSlots slots = world.traders.get(location);
            slots.loaded = !slots.loaded;
            wake(world, location);
            if (slots.loaded) return 2L;
            int writes = slots.writes;
            if (settle(world)) for (TradeRule rule : bound(world, location)) {
                assertNull(rule.orderId(), "a rule kept its order on the book while its block was unloaded");
                assertTrue(rule.status() == RuleStatus.UNREACHABLE || rule.status() == RuleStatus.PAUSED, rule.status().name());
            }
            assertEquals(writes, slots.writes);
            return 1L;
        }
    }

    /** A hopper, a duct or the owner puts a stack into an empty input slot. */
    static final class Insert implements FuzzOperation {
        @Override public String name() { return "trader_insert"; }
        @Override public int weight() { return 110; }
        @Override public long run(FuzzWorld world) {
            String location = standing(world);
            if (location == null) return 0L;
            FakeSlots slots = world.traders.get(location);
            // Nothing reaches into a chunk that is not loaded.
            if (!slots.loaded) return 0L;
            Lot wanted = world.lot();
            // As often as not it is fed what its rule sells, as a farm feeding it would.
            List<TradeRule> rules = bound(world, location);
            if (!rules.isEmpty() && !rules.get(0).buys() && !rules.get(0).tag() && world.random.nextBoolean())
                wanted = Lot.of(rules.get(0).target(), wanted.count());
            int count = (int) Math.min(Math.min(wanted.count(), FuzzWorld.LIMIT.limit(wanted.item(), wanted.payload())),
                    world.outside(wanted.key()));
            if (count <= 0) return 0L;
            for (int slot = 0; slot < TraderInventory.INPUTS; slot++) {
                if (!slots.empty(slot)) continue;
                slots.held[slot] = wanted.withCount(count);
                // A stack that is not unmodified takes up the slot and is never listed.
                if (!wanted.pristine()) slots.modified.add(slot);
                world.intoSlots(wanted.key(), count);
                wake(world, location);
                return count;
            }
            return 0L;
        }
    }

    /** A hopper empties an output slot — or the owner takes a stack out of any slot by hand. */
    static final class Extract implements FuzzOperation {
        @Override public String name() { return "trader_extract"; }
        @Override public int weight() { return 40; }
        @Override public long run(FuzzWorld world) {
            String location = standing(world);
            if (location == null) return 0L;
            FakeSlots slots = world.traders.get(location);
            if (!slots.loaded) return 0L;
            boolean byHand = world.random.nextInt(3) == 0;
            int from = byHand ? 0 : TraderInventory.INPUTS;
            int start = world.random.nextInt(TraderInventory.SLOTS - from);
            for (int index = 0; index < TraderInventory.SLOTS - from; index++) {
                int slot = from + (start + index) % (TraderInventory.SLOTS - from);
                Lot taken = slots.held[slot];
                if (taken == null) continue;
                slots.held[slot] = null;
                slots.modified.remove(slot);
                world.outOfSlots(taken.key(), taken.count());
                wake(world, location);
                return taken.count();
            }
            return 0L;
        }
    }

    /**
     * The block goes. Broken — by its owner, an operator, an explosion — its rule is deleted with
     * the block already out of reach, and what its slots hold drops like a chest's contents.
     * Swapped out by a command, the slots' contents are gone as a chest's would be, nothing is
     * reported, and the sweep settles the records by position. Either way the rule is gone, its
     * budget is back with its owner and what it had listed is in the owner's vault.
     */
    static final class Remove implements FuzzOperation {
        @Override public String name() { return "trader_remove"; }
        @Override public int weight() { return 3; }
        @Override public long run(FuzzWorld world) {
            String location = standing(world);
            if (location == null) return 0L;
            FakeSlots slots = world.traders.get(location);
            boolean silently = world.random.nextInt(4) == 0;
            UUID owner = world.store.autoTraders().owner(location);
            List<TradeRule> rules = bound(world, location);
            TradeRule rule = rules.isEmpty() ? null : rules.get(0);
            // What the rule holds for its owner: its budget, the escrow of its buy order, the goods of its sell order.
            long credits = 0L;
            Lot listed = null;
            if (rule != null) {
                credits = rule.budget();
                BuyOrder bid = world.store.book().buy(rule.orderId());
                if (bid != null) credits += bid.escrow();
                if (world.store.book().sell(rule.orderId()) != null) listed = world.store.book().sell(rule.orderId()).lot();
            }
            UUID holder = rule == null ? null : Accounts.player(rule.owner());
            long held = holder == null ? 0L : balance(world, holder) + world.store.market().creditClaimTotal(holder);
            long inVault = listed == null ? 0L : world.vault().total(rule.owner(), listed.item(), listed.payload());
            int writes = slots.writes;

            long dropped = 0L;
            if (silently) {
                // The block and everything in it is simply no longer there.
                world.traders.remove(location);
                for (Lot lot : slots.held) if (lot != null) world.destroyedInSlots(lot.key(), lot.count());
            } else {
                // Being torn down: out of reach first, then the records, then the drop.
                slots.loaded = false;
            }
            boolean recorded = TraderRules.release(world.store, world.env, location);
            assertEquals(owner != null || rule != null, recorded);
            if (!silently) {
                world.traders.remove(location);
                for (Lot lot : slots.held) if (lot != null) {
                    world.outOfSlots(lot.key(), lot.count());
                    dropped += lot.count();
                }
            }
            world.writesOfRemovedBlocks += slots.writes;

            // The rule never wrote the slots of a block that was going.
            assertEquals(writes, slots.writes);
            assertNull(world.store.autoTraders().owner(location));
            List<TradeRule> left = bound(world, location);
            // Every removal path may report it: the second report finds nothing.
            if (left.isEmpty()) assertFalse(TraderRules.release(world.store, world.env, location));
            if (rule != null && left.isEmpty()) {
                // Gone, with nothing of it left on the book: the credits are back with the owner
                // (or waiting to be collected) and the goods it had listed are in their vault.
                assertNull(world.store.rules().rule(rule.id()));
                assertNull(world.store.book().buy(rule.orderId()));
                assertNull(world.store.book().sell(rule.orderId()));
                assertEquals(held + credits, balance(world, holder) + world.store.market().creditClaimTotal(holder));
                if (listed != null)
                    assertEquals(inVault + listed.count(), world.vault().total(rule.owner(), listed.item(), listed.payload()));
            } else for (TradeRule kept : left) {
                // Only a rule whose budget could not be paid back just now stays, switched off, with no order.
                assertTrue(kept.paused());
                assertNull(kept.orderId());
            }
            return 1L + dropped + credits * 3L + (listed == null ? 0L : listed.count() * 7L) + (silently ? 1L : 0L);
        }
    }

    /** Erasure: the erased player's blocks stay where they are, ownerless; their rules are already gone. */
    static void erased(FuzzWorld world, UUID player) {
        List<String> orphaned = world.store.autoTraders().forgetPlayer(player);
        for (String location : orphaned) {
            assertNull(world.store.autoTraders().owner(location));
            assertTrue(bound(world, location).isEmpty());
            assertTrue(world.traders.containsKey(location));
        }
        assertEquals(0, world.store.autoTraders().count(player));
    }
}
