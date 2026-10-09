package za.co.neroland.neroeconomy.house;

import java.util.function.Function;

import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimStep;

/**
 * The simulation handlers of the Merchant Houses' auctions and jobs ({@link HouseDeals}):
 *
 * <ul>
 *   <li>{@code house_deals_settle} — housekeeping, so it runs even with {@code autonomousEconomy}
 *       off: House auctions that ended are settled and jobs past their expiry hand their escrow
 *       back, with no server needed. The server tick does the same on its short cadence.</li>
 *   <li>{@code house_deals} — the Houses start auctions and post jobs, and the server posts its
 *       bounties. Not housekeeping: {@code autonomousEconomy=false} switches it off.</li>
 * </ul>
 *
 * Registered after {@code merchant_houses}, so they see the stock and prices of the same step.
 */
public final class HouseDealsStep {
    public static final String SETTLE = "house_deals_settle";
    public static final String DEALS = "house_deals";

    private HouseDealsStep() { }

    /** @param env what the deals work with in a step; may answer null to skip it */
    public static void register(SimPipeline pipeline, Function<SimContext, HouseDealsEnv> env) {
        if (pipeline == null || env == null) throw new IllegalArgumentException("house deals step");
        pipeline.register(new SimStep() {
            @Override public String name() { return SETTLE; }
            @Override public boolean housekeeping() { return true; }
            @Override public void run(SimContext context, long step) { HouseDeals.settle(context, env.apply(context)); }
        });
        pipeline.register(new SimStep() {
            @Override public String name() { return DEALS; }
            @Override public void run(SimContext context, long step) { HouseDeals.step(context, env.apply(context)); }
        });
    }
}
