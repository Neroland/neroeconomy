package za.co.neroland.neroeconomy.simulation;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.exchange.ExchangeSection;
import za.co.neroland.neroeconomy.goods.GoodsSection;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.vault.VaultSection;

/**
 * What a {@link SimStep} is given: the store's sections, the economy time of the step, and a
 * deterministic random source. One context is made per handler per step and is not kept.
 */
public final class SimContext {
    private final EconomyStore store;
    private final MinecraftServer server;
    private final byte[] secret;
    private final String handler;
    private final long step;
    private final long stepMillis;
    private final long now;
    private final boolean autonomous;

    SimContext(EconomyStore store, MinecraftServer server, byte[] secret, String handler, long step,
            long stepMillis, long now, boolean autonomous) {
        this.store = store;
        this.server = server;
        this.secret = secret;
        this.handler = handler;
        this.step = step;
        this.stepMillis = stepMillis;
        this.now = now;
        this.autonomous = autonomous;
    }

    SimContext forHandler(String name) {
        return new SimContext(store, server, secret, name, step, stepMillis, now, autonomous);
    }

    // --- state -------------------------------------------------------------------------------

    /** The economy store this step runs on. A new section is reached through here. */
    public EconomyStore store() { return store; }
    public EconomySavedData ledger() { return store.ledger(); }
    public MarketSavedData market() { return store.market(); }
    public ObligationSavedData obligations() { return store.obligations(); }
    public VaultSection vault() { return store.vault(); }
    public GoodsSection goods() { return store.goods(); }
    public SimulationSection simulation() { return store.simulation(); }
    public ExchangeSection exchange() { return store.exchange(); }

    /**
     * The running server, for the few things only it can do (an alert, a block lookup). Null in a
     * unit test, so a handler must cope without it, and nothing a handler decides may depend on it.
     */
    public MinecraftServer server() { return server; }

    // --- time --------------------------------------------------------------------------------

    public long step() { return step; }

    /** The length of a step, in milliseconds of economy time. */
    public long stepMillis() { return stepMillis; }

    /** The economy time of this step: {@code step × step length}. Use this, never a clock. */
    public long time() { return SimClock.stepTime(step, stepMillis); }

    /** The economy time of the step before this one. */
    public long previousTime() { return SimClock.stepTime(step - 1L, stepMillis); }

    /**
     * The time to judge a stored deadline against: {@link #time()}, but never later than the
     * moment the step is actually being run. The two only differ when an operator forces steps
     * ahead of the clock, and a forced step must not expire a listing or mint a top-up early.
     */
    public long deadlineTime() { return Math.min(time(), now); }

    /** Whether {@code autonomousEconomy} is on. Handlers that are not housekeeping only run when it is. */
    public boolean autonomous() { return autonomous; }

    // --- randomness --------------------------------------------------------------------------

    /**
     * A random stream that depends only on the world's secret, this handler's name, the step index
     * and {@code label}. Ask for one stream per decision ({@code "price:" + stockId}), so that
     * adding a decision later does not shift the numbers of the existing ones.
     */
    public SimRandom random(String label) { return SimRandom.of(secret, handler, step, label); }
}
