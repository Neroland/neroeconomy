package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleResult;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RuleStatus;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;

/**
 * The fuzzer's auto-trade operations, against the real rules section. A rule's budget is the
 * store's own record of escrow and the goods it trades stay in the store, so the world's checks
 * cover both without a model. Rules act when they are evaluated — by {@link Tick} here and by the
 * simulation step — through the same order book the other operations trade on.
 */
final class RuleOperations {
    private RuleOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new Add(), new Tick(), new Delete(), new Pause(), new PauseAll(), new Raise());
    }

    private static TradeRule pick(FuzzWorld world, AccountId owner) {
        List<TradeRule> own = world.store.rules().rulesOf(owner);
        return own.isEmpty() ? null : own.get(world.random.nextInt(own.size()));
    }

    /** A player sets up a rule: to buy or to sell, an item or a tag. */
    static final class Add implements FuzzOperation {
        @Override public String name() { return "rule_add"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            RuleSide side = world.random.nextBoolean() ? RuleSide.BUY : RuleSide.SELL;
            boolean tag = world.random.nextInt(3) == 0;
            String target = tag ? (world.random.nextBoolean() ? FuzzWorld.TAG_GEMS : FuzzWorld.TAG_BULK)
                    : FuzzWorld.ITEMS[world.random.nextInt(FuzzWorld.ITEMS.length)];
            long limit = world.random.nextInt(25) == 0 ? 0L : BookOperations.price(world);
            int perDay = world.random.nextInt(25);
            long total = side == RuleSide.BUY ? world.random.nextInt(500) : world.random.nextInt(120);
            long before = world.ledger().getBalance(player, NeroEconomyCommon.CREDITS);
            RuleOutcome added = world.store.rules().add(world.env, FuzzWorld.account(player), side, tag, target, limit,
                    perDay, total, RulesSection.VAULT, BookOperations.id(world));
            if (!added.ok()) {
                assertEquals(before, world.ledger().getBalance(player, NeroEconomyCommon.CREDITS));
                return 0L;
            }
            // A buy rule's whole budget left the balance for escrow; a sell rule moved nothing.
            assertEquals(before - added.escrowed(), world.ledger().getBalance(player, NeroEconomyCommon.CREDITS));
            assertEquals(side == RuleSide.BUY ? total : 0L, added.escrowed());
            return 1L + added.escrowed();
        }
    }

    /** The server tick's rule evaluation: whatever is waiting is looked at, within the tick's budget. */
    static final class Tick implements FuzzOperation {
        @Override public String name() { return "rule_tick"; }
        @Override public int weight() { return 90; }
        @Override public long run(FuzzWorld world) {
            RulesSection rules = world.store.rules();
            // One time in six every rule joins the queue, as a simulation step makes them.
            if (world.random.nextInt(6) == 0) rules.enqueueAll();
            // Each call is a tick of its own; the simulation's steps count theirs upwards from zero.
            long tick = -(++world.ruleTicks);
            long now = world.ledger().now();
            long before = 0L;
            for (UUID player : world.players) before += rules.valueToday(FuzzWorld.account(player), now);
            int evaluated = rules.evaluatePending(world.env, tick);
            // What the rules filled by acting, as against by being filled while they rested.
            long after = 0L;
            for (UUID player : world.players) after += rules.valueToday(FuzzWorld.account(player), now);
            assertTrue(after >= before);
            world.ruleTraded += after - before;
            assertTrue(evaluated <= world.env.settings().ruleEvaluationsPerTick());
            // Asked again in the same tick, only what is left of the budget is used.
            int again = rules.evaluatePending(world.env, tick);
            assertTrue(evaluated + again <= world.env.settings().ruleEvaluationsPerTick());
            return evaluated * 31L + again;
        }
    }

    /** A player deletes a rule: its order leaves the book and its budget comes back. */
    static final class Delete implements FuzzOperation {
        @Override public String name() { return "rule_delete"; }
        @Override public int weight() { return 12; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            AccountId account = FuzzWorld.account(player);
            TradeRule rule = pick(world, account);
            if (rule == null) return 0L;
            long before = world.ledger().getBalance(player, NeroEconomyCommon.CREDITS);
            RuleOutcome deleted = world.store.rules().delete(world.env, account, rule.id());
            if (!deleted.ok()) return 0L;
            if (!deleted.refundedToClaim())
                assertEquals(before + deleted.refunded(), world.ledger().getBalance(player, NeroEconomyCommon.CREDITS));
            assertEquals(RuleResult.NOT_FOUND, world.store.rules().delete(world.env, account, rule.id()).result());
            return 1L + deleted.refunded();
        }
    }

    /** A player pauses or resumes a rule — or tries to pause somebody else's. */
    static final class Pause implements FuzzOperation {
        @Override public String name() { return "rule_pause"; }
        @Override public int weight() { return 12; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            TradeRule rule = pick(world, account);
            if (rule == null) return 0L;
            AccountId stranger = FuzzWorld.account(world.player());
            if (!stranger.equals(account)) assertEquals(RuleResult.NOT_FOUND,
                    world.store.rules().setPaused(world.env, stranger, rule.id(), true).result());
            boolean pause = !rule.paused();
            RuleOutcome changed = world.store.rules().setPaused(world.env, account, rule.id(), pause);
            assertTrue(changed.ok());
            if (pause) {
                assertEquals(RuleStatus.PAUSED, changed.rule().status());
                assertTrue(changed.rule().orderId() == null);
            }
            return pause ? 2L : 1L;
        }
    }

    /** A player flips their "pause all my rules" switch. */
    static final class PauseAll implements FuzzOperation {
        @Override public String name() { return "rule_pause_all"; }
        @Override public int weight() { return 6; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            boolean pause = !world.store.rules().pausedAll(account);
            int touched = world.store.rules().setPausedAll(world.env, account, pause);
            assertEquals(pause, world.store.rules().pausedAll(account));
            if (pause) for (TradeRule rule : world.store.rules().rulesOf(account)) assertTrue(rule.orderId() == null);
            return 1L + touched * 3L + (pause ? 1L : 0L);
        }
    }

    /** A player raises a rule's total: more budget for a buy rule, more quantity for a sell rule. */
    static final class Raise implements FuzzOperation {
        @Override public String name() { return "rule_raise"; }
        @Override public int weight() { return 8; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            AccountId account = FuzzWorld.account(player);
            TradeRule rule = pick(world, account);
            if (rule == null) return 0L;
            long before = world.ledger().getBalance(player, NeroEconomyCommon.CREDITS);
            RuleOutcome raised = world.store.rules().raise(world.env, account, rule.id(), world.random.nextInt(200));
            if (!raised.ok()) {
                assertEquals(before, world.ledger().getBalance(player, NeroEconomyCommon.CREDITS));
                return 0L;
            }
            assertEquals(before - raised.escrowed(), world.ledger().getBalance(player, NeroEconomyCommon.CREDITS));
            return 1L + raised.escrowed();
        }
    }
}
