package za.co.neroland.neroeconomy.house;

import java.util.function.Function;

import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimStep;

/**
 * The Merchant Houses' simulation handler, {@code merchant_houses}: one {@link HouseSection#step}
 * per simulation step. It acts in the market on the server's behalf, so it is <em>not</em>
 * housekeeping: {@code autonomousEconomy=false} switches it off. It takes its day from the step —
 * never later than the moment the step is run — so a replayed step does what a live one would have.
 * It uses no randomness.
 */
public final class HouseStep implements SimStep {
    public static final String NAME = "merchant_houses";
    private final Function<SimContext, HouseEnv> env;

    /** @param env what the Houses work with in this step; may answer null to skip it */
    public HouseStep(Function<SimContext, HouseEnv> env) {
        if (env == null) throw new IllegalArgumentException("house step");
        this.env = env;
    }

    @Override public String name() { return NAME; }

    @Override public void run(SimContext context, long step) {
        HouseEnv houses = env.apply(context);
        if (houses == null) return;
        context.store().houses().step(houses, EconomySavedData.economyDay(context.deadlineTime()), context.stepMillis());
    }
}
