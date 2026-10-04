package za.co.neroland.neroeconomy.exchange;

import java.util.function.Function;

import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimStep;

/**
 * The simulation handlers of the exchange's trading rules, registered after
 * {@link ExchangeStep} ({@code exchange_prices}):
 *
 * <ul>
 *   <li>{@code exchange_dividends} — once per economy-day, the first step of the day: every
 *       holder of a company that pays a dividend is paid for the units they held through the
 *       whole day before. Like the price step it is <em>not</em> housekeeping, so
 *       {@code autonomousEconomy=false} switches it off, and it obeys {@code exchangeEnabled}.</li>
 *   <li>{@code exchange_settlement} — every step: Savings Bonds that have matured are paid out,
 *       the holders of a delisted company are paid out at its last bid, and once a day old lots
 *       are merged and idle practice accounts dropped. This is housekeeping: it only carries out
 *       what players themselves set up, so it runs whatever the switches say, with the owner
 *       offline and the server empty, and settles what fell due during a pause as part of the
 *       catch-up.</li>
 * </ul>
 *
 * <p>Both take the day and the time from the step — never later than the moment the step is
 * actually run — so a replayed step does exactly what a live one would have done, and a step
 * forced ahead of the clock pays nothing early.
 */
public final class ExchangeSteps {
    public static final String DIVIDENDS = "exchange_dividends";
    public static final String SETTLEMENT = "exchange_settlement";

    private ExchangeSteps() { }

    /** Registers both handlers, in their fixed order. Call after {@link ExchangeStep}. */
    public static void register(SimPipeline pipeline, Function<SimContext, ExchangeEnv> env) {
        if (pipeline == null || env == null) throw new IllegalArgumentException("exchange steps");
        pipeline.register(new Dividends(env));
        pipeline.register(new Settlement(env));
    }

    private static long day(long time) { return Math.floorDiv(time, PriceModel.DAY_MILLIS); }

    private static final class Dividends implements SimStep {
        private final Function<SimContext, ExchangeEnv> env;

        Dividends(Function<SimContext, ExchangeEnv> env) { this.env = env; }

        @Override public String name() { return DIVIDENDS; }

        @Override public void run(SimContext context, long step) {
            ExchangeSection exchange = context.exchange();
            long day = day(context.deadlineTime());
            // Cheap on every step but the first of a day: one comparison.
            DividendRun last = exchange.dividends();
            if (last.happened() && day <= last.day()) return;
            exchange.payDividends(env.apply(context), day);
        }
    }

    private static final class Settlement implements SimStep {
        private final Function<SimContext, ExchangeEnv> env;

        Settlement(Function<SimContext, ExchangeEnv> env) { this.env = env; }

        @Override public String name() { return SETTLEMENT; }
        @Override public boolean housekeeping() { return true; }

        @Override public void run(SimContext context, long step) {
            ExchangeSection exchange = context.exchange();
            if (exchange.isEmpty()) return;
            ExchangeEnv trading = env.apply(context);
            long time = context.deadlineTime();
            exchange.matureBonds(trading, time);
            exchange.payOutDelisted(trading);
            if (day(time) != day(Math.min(context.previousTime(), time))) exchange.settle(trading);
        }
    }
}
