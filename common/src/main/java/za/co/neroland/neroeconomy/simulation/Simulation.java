package za.co.neroland.neroeconomy.simulation;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.platform.Services;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;

/**
 * The running game's simulation: the one pipeline every stage registers its handlers with, the
 * settings read from the configuration, and the calls the server tick and the admin command make.
 *
 * <p>To add to the simulation, register a {@link SimStep} with {@link #PIPELINE} during mod
 * start-up ({@code NeroEconomyCommon.init()}); it then runs on every step, in registration order,
 * after the housekeeping handlers.
 */
public final class Simulation {
    /** The handlers of one step, in running order. The housekeeping handlers are always first. */
    public static final SimPipeline PIPELINE = new SimPipeline();
    /** With {@code debugFastSim} in a development environment, one step per second of real time. */
    private static final long FAST_STEP_MILLIS = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final AtomicBoolean FAST_SIM_WARNED = new AtomicBoolean();

    static { Housekeeping.register(PIPELINE); }

    private Simulation() { }

    /**
     * What an operator is shown.
     *
     * @param stepMillis the length of a step
     * @param lastStep   the last completed step, or {@link SimClock#NEVER}
     * @param behind     steps due and not yet run
     * @param fast       whether {@code debugFastSim} is in effect
     * @param autonomous whether {@code autonomousEconomy} is on
     */
    public record Status(long stepMillis, long lastStep, long behind, boolean fast, boolean autonomous) {
        public boolean catchingUp() { return behind > 1L; }
    }

    /** The settings in effect now; read from the configuration on every call, so a reload applies. */
    public static SimRunner.Settings settings() {
        int minutes = EconomyConfig.SIM_STEP_MINUTES.get();
        long catchUp = Math.max(1L, EconomyConfig.SIM_CATCH_UP_MAX_HOURS.get() * 60L / minutes);
        return new SimRunner.Settings(fast() ? FAST_STEP_MILLIS : minutes * MILLIS_PER_MINUTE, catchUp,
                SimClock.DEFAULT_STEPS_PER_TICK, EconomyConfig.AUTONOMOUS_ECONOMY.get());
    }

    /** {@code debugFastSim} only counts in a development environment; elsewhere it is ignored, with one warning. */
    private static boolean fast() {
        if (!EconomyConfig.DEBUG_FAST_SIM.get()) return false;
        if (Services.PLATFORM.isDevelopmentEnvironment()) return true;
        if (FAST_SIM_WARNED.compareAndSet(false, true))
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] debugFastSim is set but this is not a development "
                    + "environment; it is ignored and the simulation runs at simStepMinutes.");
        return false;
    }

    /** The server tick: runs the steps that are due, a few at most. Does nothing while frozen. */
    public static void tick(MinecraftServer server, long now) {
        if (server == null || EconomyHealth.frozen()) return;
        SimRunner.Progress progress = SimRunner.advance(EconomyStore.get(server), server, PIPELINE, now, settings(),
                Simulation::report);
        if (progress.skipped() > 0L)
            NeroEconomyCommon.LOGGER.info("[NeroEconomy] The simulation was behind by more than simCatchUpMaxHours; "
                    + "{} steps were skipped and the rest are being replayed.", progress.skipped());
    }

    /** An operator's forced steps. @return how many ran; 0 while frozen */
    public static int stepNow(MinecraftServer server, int steps) {
        if (server == null || EconomyHealth.frozen()) return 0;
        return SimRunner.force(EconomyStore.get(server), server, PIPELINE,
                NeroEconomyCommon.CURRENCY_PROVIDER.now(), settings(), steps, Simulation::report);
    }

    public static Status status(MinecraftServer server) {
        SimRunner.Settings settings = settings();
        SimulationSection section = EconomyStore.get(server).simulation();
        long last = SimClock.rebase(section.lastStep(), section.stepMillis(), settings.stepMillis());
        return new Status(settings.stepMillis(), last, SimRunner.behind(section,
                NeroEconomyCommon.CURRENCY_PROVIDER.now(), settings.stepMillis()),
                settings.stepMillis() == FAST_STEP_MILLIS && EconomyConfig.DEBUG_FAST_SIM.get(), settings.autonomous());
    }

    /** Server stop: a new session reports each failing handler once again. */
    public static void reset() {
        PIPELINE.resetReports();
        FAST_SIM_WARNED.set(false);
    }

    /**
     * A handler failed for the first time in this session. The log and the report carry the
     * handler's name and the exception's class only: its message is not ours and could say anything.
     */
    private static void report(String handler, RuntimeException failure) {
        NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Simulation handler '{}' failed ({}); it is skipped for "
                + "this step and the rest of the step carries on.", handler, failure.getClass().getName());
        try {
            NeroEconomyTelemetry.captureHandledException(new IllegalStateException(
                    "Simulation handler failed: " + handler + " (" + failure.getClass().getName() + ")"), "sim_step");
        } catch (RuntimeException | LinkageError ignored) { }
    }
}
