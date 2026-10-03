package za.co.neroland.neroeconomy.trade;

import java.util.function.Function;

import net.minecraft.server.MinecraftServer;

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
 *       queue is worked through within the tick's budget. Rules trade in real economy time: a
 *       step replayed after a pause does not trade for the time that was missed.</li>
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
            // In game the budget is shared with the server tick's own drain: one count per tick.
            MinecraftServer server = context.server();
            rules.evaluatePending(env.apply(context), server != null ? server.getTickCount() : step);
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
