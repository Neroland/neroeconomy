package za.co.neroland.neroeconomy.trade;

import java.util.function.Function;

import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimStep;

/**
 * The simulation handlers of the trading rules. They are registered after the housekeeping
 * handlers and, like them, run on every step whether or not the autonomous economy is switched
 * on: what they do is carry out what players themselves set up — an order's expiry, a player's own
 * rule — never act in the market on the server's behalf.
 *
 * <ul>
 *   <li>{@code trade_expiry} — orders past their expiry leave the book (goods back to the vault,
 *       escrow back as a credit claim) and unanswered offers go back to their senders. The server
 *       tick runs the same sweep on a short cadence; doing it here as well settles what fell due
 *       while the server was paused or shut down.</li>
 *   <li>{@code auto_trade} — every auto-trade rule joins the queue to be looked at again, and the
 *       queue is worked through within the step's own evaluation budget. A step runs at its own
 *       economy time (see {@code SimRunner}), so a step replayed after a pause trades as it would
 *       have on time, within that day's caps: a vault-backed rule keeps working while the server is
 *       empty or paused.</li>
 * </ul>
 *
 * <p>A handler is handed the {@link TradeEnv} to work with by whoever registers it: the game
 * builds one from its configuration and registries, a test passes its own.
 */
public final class TradingSteps {
    private TradingSteps() { }

    /** Registers the trading handlers, in their fixed order. Call after the housekeeping handlers. */
    public static void register(SimPipeline pipeline, Function<SimContext, TradeEnv> env) {
        if (pipeline == null || env == null) throw new IllegalArgumentException("trading steps");
        pipeline.register(new Expiry(env));
        pipeline.register(new AutoTrade(env));
    }

    private abstract static class Handler implements SimStep {
        private final String name;
        final Function<SimContext, TradeEnv> env;

        Handler(String name, Function<SimContext, TradeEnv> env) {
            this.name = name;
            this.env = env;
        }

        @Override public final String name() { return name; }
        @Override public final boolean housekeeping() { return true; }
    }

    private static final class AutoTrade extends Handler {
        AutoTrade(Function<SimContext, TradeEnv> env) { super("auto_trade", env); }

        @Override public void run(SimContext context, long step) {
            RulesSection rules = context.store().rules();
            if (rules.size() == 0) return;
            rules.purgeDayValues(context.store().ledger().now());
            rules.enqueueAll();
            // Each step has an evaluation budget of its own, so two steps replayed in one tick do as
            // much as two steps on time. Negative ids never meet the server tick's own (its tick count).
            rules.evaluatePending(env.apply(context), -1L - step);
        }
    }

    private static final class Expiry extends Handler {
        Expiry(Function<SimContext, TradeEnv> env) { super("trade_expiry", env); }

        @Override public void run(SimContext context, long step) {
            TradeEnv trade = env.apply(context);
            context.store().book().expire(trade, context.deadlineTime());
            context.store().offers().expire(trade, context.deadlineTime());
        }
    }
}
