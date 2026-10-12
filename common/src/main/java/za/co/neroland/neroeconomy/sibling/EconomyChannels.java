package za.co.neroland.neroeconomy.sibling;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.event.ThresholdEvents;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.MarketShocks;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.house.HouseSection;
import za.co.neroland.neroeconomy.platform.Services;

/**
 * The class-free channels other mods reach through Core alone (no compile dependency on this mod):
 *
 * <ul>
 *   <li><b>Out, as Core threshold crossings</b> ({@link ThresholdEvents}), every scope a world, a
 *       sector, a commodity or an item, never a person: {@code neroeconomy:reserve},
 *       {@code neroeconomy:house_stock}, {@code neroeconomy:market_shock} and
 *       {@code neroeconomy:sector_index}.</li>
 *   <li><b>Out, as Core progression gates</b>: the per-player milestones of {@link MilestoneSection},
 *       opened within a second of being earned whether the player is online or not (Core 1.15.0).</li>
 *   <li><b>In</b>: {@code <modid>:market_shock_request} crossings from the mods in
 *       {@code shockSources} ({@link ShockRequests}).</li>
 * </ul>
 * A listener of another mod that throws is caught here; Core's own bus does not catch.
 */
public final class EconomyChannels {
    public static final Identifier RESERVE = id("reserve");
    public static final Identifier HOUSE_STOCK = id("house_stock");
    public static final Identifier MARKET_SHOCK = id("market_shock");
    public static final Identifier SECTOR_INDEX = id("sector_index");
    /** How often, in ticks, waiting gates are opened. */
    private static final int GATE_TICKS = 20;

    private static final ThresholdWatch RESERVE_WATCH = new ThresholdWatch();
    private static final ThresholdWatch STOCK_WATCH = new ThresholdWatch();
    private static final ThresholdWatch SECTOR_WATCH = new ThresholdWatch();
    private static int gateCountdown;
    private static boolean initialised;
    private static volatile String sourcesText;
    private static volatile Set<String> sources = Set.of();

    private EconomyChannels() { }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, path); }

    /** Common init: listens for shock requests and counts sales and purchases toward milestones. */
    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        ThresholdEvents.onCrossing(EconomyChannels::onCrossing);
        EconomyEventBus.SOLD.add(event -> {
            EconomyStore store = liveStore();
            if (store != null) store.milestones().countSale(event.seller());
        });
        EconomyEventBus.BOUGHT.add(event -> {
            EconomyStore store = liveStore();
            if (store != null) store.milestones().earn(event.buyer(), MilestoneSection.Milestone.FIRST_PURCHASE);
        });
    }

    /** The store of the running server, or null while none runs or the economy is frozen. */
    private static EconomyStore liveStore() {
        MinecraftServer server = NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull();
        return server == null || EconomyHealth.frozen() ? null : EconomyStore.get(server);
    }

    /** Records a milestone for a player of the running server; does nothing without one. */
    public static void earn(MinecraftServer server, UUID player, MilestoneSection.Milestone milestone) {
        if (server == null || player == null || EconomyHealth.frozen()) return;
        EconomyStore.get(server).milestones().earn(player, milestone);
    }

    // --- per tick ----------------------------------------------------------------------------

    /** Every tick: once a second, opens the waiting gates, for offline players too (Core 1.15.0). */
    public static void tick(MinecraftServer server) {
        if (server == null || --gateCountdown > 0) return;
        gateCountdown = GATE_TICKS;
        if (EconomyHealth.frozen()) return;
        MilestoneSection milestones = EconomyStore.get(server).milestones();
        if (!milestones.hasPending()) return;
        for (UUID player : milestones.pendingPlayers()) {
            for (MilestoneSection.Milestone milestone : milestones.pending(player)) {
                try {
                    ProgressionGates.open(server, player, id(milestone.id()));
                } catch (RuntimeException | LinkageError failure) {
                    NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A milestone gate could not be opened ({}).",
                            failure.getClass().getName());
                }
                // Opened or not, it is not retried every second: an operator can open the gate by hand.
                milestones.opened(player, milestone);
            }
        }
    }

    /** On the market-sweep cadence: the Reserve's level and the Houses' stock. */
    public static void sweep(MinecraftServer server) {
        if (server == null || EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(server);
        long low = EconomyConfig.RESERVE_ALERT_LOW.get();
        long balance = store.ledger().balance(SystemAccountId.RESERVE);
        Boolean reserve = RESERVE_WATCH.observe("world", balance >= low);
        if (reserve != null) publish(RESERVE, "world", balance, low, reserve);
        for (HouseSection.Position position : store.houses().positions()) {
            Boolean stocked = STOCK_WATCH.observe(position.key(), position.held() > 0L);
            if (stocked != null) publish(HOUSE_STOCK, position.key(), position.held(), 0L, stocked);
        }
        if (za.co.neroland.neroeconomy.api.ExchangeApi.enabled()) sectors(server);
    }

    /**
     * Each configured band above and below a sector's day open: rising when the index goes above the
     * upper level (or back above the lower one), falling when it goes back below the upper level (or
     * below the lower one). A new day's open moves the levels with it.
     */
    private static void sectors(MinecraftServer server) {
        java.util.List<Integer> bands = Sectors.parseBands(EconomyConfig.SECTOR_BANDS.get());
        for (za.co.neroland.neroeconomy.api.ExchangeApi.SectorQuote quote
                : za.co.neroland.neroeconomy.api.ExchangeApi.sectors(server)) {
            for (int band : bands) {
                long upper = Sectors.bandLevel(quote.dayOpenCents(), band, true);
                long lower = Sectors.bandLevel(quote.dayOpenCents(), band, false);
                Boolean up = SECTOR_WATCH.observe(quote.sector() + "/+" + band, quote.indexCents() >= upper);
                if (up != null) publish(SECTOR_INDEX, quote.sector(), quote.indexCents(), upper, up);
                Boolean down = SECTOR_WATCH.observe(quote.sector() + "/-" + band, quote.indexCents() > lower);
                if (down != null) publish(SECTOR_INDEX, quote.sector(), quote.indexCents(), lower, down);
            }
        }
    }

    /** Server stop: what was watched belongs to the world that stopped. */
    public static void clear() {
        RESERVE_WATCH.clear();
        STOCK_WATCH.clear();
        SECTOR_WATCH.clear();
        gateCountdown = 0;
    }

    // --- shocks ------------------------------------------------------------------------------

    /** A shock started on {@code key} (an item id or {@code #tag}) at {@code multiplier}. */
    public static void shockStarted(String key, double multiplier) {
        publish(MARKET_SHOCK, key, Math.round(multiplier * 1000.0D), 1000L, true);
    }

    /** A shock on {@code key} ended. */
    public static void shockEnded(String key) {
        publish(MARKET_SHOCK, key, 1000L, 1000L, false);
    }

    /** A request from another mod on {@code <modid>:market_shock_request}. */
    static void onCrossing(ThresholdEvents.ThresholdCrossing crossing) {
        if (crossing == null || crossing.channel() == null
                || !ShockRequests.PATH.equals(crossing.channel().getPath())) return;
        String text = EconomyConfig.SHOCK_SOURCES.get();
        if (!java.util.Objects.equals(text, sourcesText)) {
            sources = Set.copyOf(ShockRequests.parseSources(text));
            sourcesText = text;
        }
        ShockRequests.Decision decision = ShockRequests.decode(crossing.channel().getNamespace(),
                crossing.channel().getPath(), crossing.scope(), crossing.value(), crossing.threshold(),
                crossing.rising(), sources, modId -> Services.PLATFORM.isModLoaded(modId),
                EconomyConfig.SHOCK_MAX_MINUTES.get(), MarketShocks.activeCount(), EconomyConfig.MAX_ACTIVE_SHOCKS.get(),
                crossing.scope() != null && MarketShocks.active(crossing.scope()));
        try {
            switch (decision.kind()) {
                case APPLY -> MarketShocks.applyShock(decision.key(), decision.multiplier(),
                        Duration.ofMinutes(decision.minutes()));
                case CLEAR -> MarketShocks.clearShock(decision.key());
                case IGNORE -> NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A shock request was ignored ({}).",
                        decision.why());
            }
        } catch (IllegalArgumentException malformed) {
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A shock request was ignored (malformed).");
        }
    }

    // --- publishing --------------------------------------------------------------------------

    static void publish(Identifier channel, String scope, long value, long threshold, boolean rising) {
        try {
            ThresholdEvents.fire(NeroEconomyCommon.MOD_ID,
                    new ThresholdEvents.ThresholdCrossing(channel, scope, value, threshold, rising));
        } catch (RuntimeException | LinkageError failure) {
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A threshold listener threw ({}).", failure.getClass().getName());
        }
    }
}
