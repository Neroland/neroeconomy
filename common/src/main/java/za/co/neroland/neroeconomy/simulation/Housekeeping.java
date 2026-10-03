package za.co.neroland.neroeconomy.simulation;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.api.MarketShocks;
import za.co.neroland.neroeconomy.data.LedgerExportFiles;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.shop.ShopSweep;

/**
 * The simulation handlers that keep the books: they run on every step whether or not the
 * autonomous economy is switched on, and they are always the first handlers of the pipeline.
 *
 * <ul>
 *   <li>{@link #RESERVE} — the Reserve's daily top-up, once per economy day.</li>
 *   <li>{@link #DAY} — the day roll-over: the retention sweep of the audit trail, the per-day
 *       counters that are only kept for their own day, and aged export files.</li>
 *   <li>{@link #EXPIRY} — listings, buy offers, jobs and auctions past their deadline, shops whose
 *       block has gone, and market shocks that have run out.</li>
 * </ul>
 *
 * <p>Expiry also still runs on the server tick's short cadence, because a deadline is honoured
 * within seconds, not within a step. Doing it here as well means a server that was paused or shut
 * down settles what fell due in the meantime as part of the catch-up.
 */
public final class Housekeeping {
    private Housekeeping() { }

    private abstract static class Handler implements SimStep {
        private final String name;
        Handler(String name) { this.name = name; }
        @Override public final String name() { return name; }
        @Override public final boolean housekeeping() { return true; }
    }

    public static final SimStep RESERVE = new Handler("reserve_top_up") {
        @Override public void run(SimContext context, long step) {
            context.ledger().topUpReserve(EconomySavedData.economyDay(context.deadlineTime()));
        }
    };

    public static final SimStep DAY = new Handler("day_rollover") {
        @Override public void run(SimContext context, long step) {
            long time = context.deadlineTime();
            long previous = Math.min(context.previousTime(), time);
            if (EconomySavedData.economyDay(time) == EconomySavedData.economyDay(previous)) return;
            context.ledger().purgeExpired(time);
            MinecraftServer server = context.server();
            // Export files are aged by their file-system timestamp, so that sweep is on the wall clock.
            if (server != null) LedgerExportFiles.purgeExpired(server, System.currentTimeMillis());
        }
    };

    public static final SimStep EXPIRY = new Handler("expiry") {
        @Override public void run(SimContext context, long step) {
            long time = context.deadlineTime();
            context.market().expire(time);
            MinecraftServer server = context.server();
            if (server == null) return;
            context.obligations().tick(server, time);
            ShopSweep.run(server);
            MarketShocks.purgeExpired();
        }
    };

    /** Registers the housekeeping handlers, in their fixed order. Call before any other handler. */
    public static void register(SimPipeline pipeline) {
        pipeline.register(RESERVE);
        pipeline.register(DAY);
        pipeline.register(EXPIRY);
    }
}
