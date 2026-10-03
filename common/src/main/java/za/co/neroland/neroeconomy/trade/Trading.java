package za.co.neroland.neroeconomy.trade;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.MarketListingView;
import za.co.neroland.neroeconomy.api.PricingEngine;
import za.co.neroland.neroeconomy.direct.DirectTrades;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.rules.RuleBinder;

/**
 * The running game's side of the trading rules: the {@link TradeEnv} the order book, offers and
 * auto-trade rules are given in game, built from the configuration and the game's registries, and
 * the calls the server tick and the erasure hook make.
 */
public final class Trading {
    private static volatile SystemDelivery systemDelivery;
    private static volatile RuleBinder ruleBinder;

    private Trading() { }

    /** The environment for one operation on {@code server}'s economy. Cheap; build one per operation. */
    public static TradeEnv env(MinecraftServer server) { return env(EconomyStore.get(server)); }

    /** The environment for one operation on {@code store}. */
    public static TradeEnv env(EconomyStore store) {
        return new TradeEnv(TradeSettings.fromConfig(), store.goods().limits(), PRICING, CATALOG, ALERTS, systemDelivery,
                ruleBinder);
    }

    /**
     * Plugs in whoever knows the inventories a rule can be bound to besides the vault (the
     * Auto-Trader block). Until this is called a rule can only work from its owner's vault.
     * Null unplugs it.
     */
    public static void ruleBinder(RuleBinder binder) { ruleBinder = binder; }

    /**
     * Plugs in whoever keeps the stock of system accounts (the Merchant Houses). Until this is
     * called the order book creates no order for a system account. Null unplugs it.
     */
    public static void systemDelivery(SystemDelivery delivery) { systemDelivery = delivery; }

    // --- the buyer's price -------------------------------------------------------------------

    /** One order as the pricing engine sees a listing: the item, its tags and the seller's price. */
    private record PricedOrder(UUID listingId, Identifier itemId, Set<Identifier> tags, long baseUnitPrice,
            int availableQuantity) implements MarketListingView { }

    /** Shocks and registered price modifiers, exactly as a shop or a direct purchase is priced. */
    private static final BuyerPricing PRICING = (buyer, listing, item, baseUnitPrice, available) -> {
        UUID player = Accounts.player(buyer);
        Identifier id = Identifier.tryParse(item);
        if (player == null || id == null || baseUnitPrice < 1L) return baseUnitPrice;
        return PricingEngine.quote(new PricedOrder(listing, id, tags(id), baseUnitPrice, available), player).unitPrice();
    };

    private static Set<Identifier> tags(Identifier item) {
        try {
            return BuiltInRegistries.ITEM.get(item).map(holder -> holder.tags().map(TagKey::location)
                    .collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
        } catch (RuntimeException unbound) {
            return Set.of();
        }
    }

    // --- the item catalogue ------------------------------------------------------------------

    private static final ItemCatalog CATALOG = new ItemCatalog() {
        @Override public boolean known(String item) {
            Identifier id = item == null ? null : Identifier.tryParse(item);
            Item value = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            return value != null && value != Items.AIR;
        }

        @Override public boolean tradeable(String item) {
            return known(item) && !MarketSavedData.untradeable(Identifier.parse(item));
        }

        @Override public Set<String> tagsOf(String item) {
            Identifier id = item == null ? null : Identifier.tryParse(item);
            if (id == null) return Set.of();
            try {
                return BuiltInRegistries.ITEM.get(id).map(holder -> holder.tags().map(tag -> tag.location().toString())
                        .collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
            } catch (RuntimeException unbound) {
                return Set.of();
            }
        }

        @Override public boolean tagKnown(String tag) {
            Identifier id = tag == null ? null : Identifier.tryParse(tag);
            return id != null && BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id))
                    .map(named -> named.size() > 0).orElse(false);
        }

        @Override public boolean inTag(String item, String tag) {
            Identifier id = item == null ? null : Identifier.tryParse(item);
            Identifier tagId = tag == null ? null : Identifier.tryParse(tag);
            if (id == null || tagId == null) return false;
            Holder<Item> holder = BuiltInRegistries.ITEM.get(id).orElse(null);
            return holder != null && holder.is(TagKey.create(Registries.ITEM, tagId));
        }
    };

    // --- alerts ------------------------------------------------------------------------------

    /** Fixed catalogue sentences only; a system account has no phone to alert. */
    private static final AlertSink ALERTS = (account, kind) -> {
        UUID player = Accounts.player(account);
        MinecraftServer server = NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull();
        if (player == null || server == null) return;
        try {
            EconomyAlerts.raise(server, player, kind, LinkAlert.Severity.INFO);
        } catch (RuntimeException | LinkageError failure) {
            // An alert is a courtesy; it never undoes or blocks the trade that raised it.
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] An alert could not be raised ({}).",
                    failure.getClass().getName());
        }
    };

    // --- lifecycle ---------------------------------------------------------------------------

    /**
     * The short-cadence expiry sweep: orders past their expiry leave the book and unanswered
     * offers go back to their senders. The simulation's housekeeping step runs the same sweep,
     * which settles what fell due while the server was stopped.
     */
    public static void expire(MinecraftServer server, long now) {
        if (server == null || EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(server);
        TradeEnv env = env(store);
        store.book().expire(env, now);
        store.offers().expire(env, now);
    }

    /**
     * The server tick: rules waiting to be looked at — because a simulation step came round, or
     * the book changed for an item they trade — are evaluated in turn, at most
     * {@code ruleEvaluationsPerTick} of them in this tick. Costs one check when nothing waits.
     */
    public static void tick(MinecraftServer server) {
        if (server == null || EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(server);
        if (!store.rules().hasWork()) return;
        store.rules().evaluatePending(env(store), server.getTickCount());
    }

    /**
     * Erasure entry point for the trading sections — direct trades, the order book, offers and
     * auto-trade rules; tolerates an unknown player. The erased player's goods on the book, in
     * offers they sent and on the table of an open trade
     * are destroyed, their escrow moves to the Reserve, the prototypes only they referred to are
     * dropped, and the purge is pushed into Core's recovery backup in the same request.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore store = EconomyStore.get(server);
        // A direct trade they have open ends first, so the other player gets their goods back;
        // what the erased player had on the table is destroyed with the rest of their goods.
        DirectTrades.eraseFor(server, player);
        store.book().forgetPlayer(player);
        // Offers the player sent go with them; offers sent to them go back to their senders.
        store.offers().forgetPlayer(env(store), player);
        // Their rules go, and what is left of their budgets with them.
        store.rules().forgetPlayer(player);
        // Their Auto-Trader blocks stay where they are, with no owner; the rules they ran are gone.
        store.autoTraders().forgetPlayer(player);
        // A prototype can carry a name or other data; it must not outlive the last lot that used it.
        store.goods().sweep();
        EconomyStore.afterErasure(server, player);
    }
}
