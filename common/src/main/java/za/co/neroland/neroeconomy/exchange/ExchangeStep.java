package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.api.MarketShocks;
import za.co.neroland.neroeconomy.book.ItemVolume;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimStep;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.Trading;

/**
 * The simulation handler {@code exchange_prices}: one update of every listed company's price per
 * simulation step.
 *
 * <p>It is <em>not</em> housekeeping, so {@code autonomousEconomy=false} switches it off along
 * with the Merchant Houses, and it also obeys {@code exchangeEnabled}. While it is off, prices
 * stand still; when it comes back, it carries on from where they were.
 *
 * <p>What a step reads:
 * <ul>
 *   <li>the state in the store's exchange section;</li>
 *   <li>one random stream per company, {@code context.random("price:" + id)} — keyed by the
 *       world's secret, this handler's name and the step index, so a step replayed after a pause
 *       gives exactly the price an uninterrupted run would have given;</li>
 *   <li>the volume traded on the order book since the step before, which it takes and resets;</li>
 *   <li>any active market shock for the company or its linked commodities.</li>
 * </ul>
 * It reads no clock, no game time and nothing a player does on the exchange itself.
 */
public final class ExchangeStep implements SimStep {
    public static final String NAME = "exchange_prices";
    /** The label of a company's random stream: this, then its id. */
    public static final String PRICE_STREAM = "price:";

    /** What the handler needs from outside the store. The game supplies {@link #GAME}; a test its own. */
    public interface Wiring {
        /** The numbers in effect for this step. */
        ExchangeSettings settings();

        /** The listed companies. */
        StockRegistry registry(SimContext context);

        /** Who knows which item is in which tag. */
        ItemCatalog items(SimContext context);

        /** The active market-shock multiplier for these ids at {@code now}; 1 for none. */
        double shock(List<String> keys, long now);
    }

    /** The running game: configuration, data packs, registries and {@link MarketShocks}. */
    public static final Wiring GAME = new Wiring() {
        @Override public ExchangeSettings settings() { return ExchangeSettings.fromConfig(); }

        @Override public StockRegistry registry(SimContext context) {
            MinecraftServer server = context.server();
            return server == null ? StockRegistry.UNAVAILABLE : StockData.forServer(server);
        }

        @Override public ItemCatalog items(SimContext context) { return Trading.env(context.store()).items(); }

        @Override public double shock(List<String> keys, long now) { return MarketShocks.multiplier(keys, now); }
    };

    private final Wiring wiring;

    public ExchangeStep(Wiring wiring) {
        if (wiring == null) throw new IllegalArgumentException("exchange step");
        this.wiring = wiring;
    }

    @Override public String name() { return NAME; }

    @Override public void run(SimContext context, long step) {
        ExchangeSettings settings = wiring.settings();
        // Taken every step, whether or not prices move, so the tally always covers exactly one step.
        Map<String, ItemVolume> traded = context.store().book()
                .drainTradedVolume(settings.volumePairCap(), settings.volumeAccountCap());
        if (!settings.enabled()) return;
        StockRegistry registry = wiring.registry(context);
        if (registry == null || !registry.available()) return;
        ItemCatalog items = wiring.items(context);
        // A shock's window is judged against the real time when a step is forced ahead of the clock.
        long now = context.deadlineTime();
        context.store().exchange().step(registry, settings, step, context.stepMillis(), new ExchangeSection.Inputs() {
            @Override public double gaussian(String stockId) {
                return PriceModel.gaussian(context.random(PRICE_STREAM + stockId));
            }

            @Override public long volume(StockDefinition definition) {
                if (traded.isEmpty() || definition.linked().isEmpty()) return 0L;
                long total = 0L;
                for (Map.Entry<String, ItemVolume> item : traded.entrySet())
                    if (definition.linksItem(item.getKey(), items)) {
                        total += item.getValue().value();
                        if (total < 0L) return Long.MAX_VALUE;
                    }
                return total;
            }

            @Override public double shock(StockDefinition definition) {
                List<String> keys = new ArrayList<>(definition.linked().size() + 1);
                keys.add(definition.id());
                keys.addAll(definition.linked());
                return wiring.shock(keys, now);
            }
        });
    }
}
