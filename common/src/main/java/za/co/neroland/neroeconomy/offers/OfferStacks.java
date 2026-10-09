package za.co.neroland.neroeconomy.offers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * Offers as the rest of the game reaches them: the edge between the pure {@link OffersSection} and
 * live players, used by the commands now and by the offers screen and the companion app later.
 * Each entry point checks the frozen flag, the shape of the request, the creative-mode guard where
 * goods would leave a live inventory, and the shared request ledger, before the section is touched.
 *
 * <p>The recipient is always an account id the caller resolved through {@code KnownAccounts}: an
 * offer never opens an account for an id nobody has played as.
 */
public final class OfferStacks {
    private OfferStacks() { }

    /**
     * What an offer gives and asks, as a caller states it.
     *
     * @param handCount  how many of the sender's main-hand stack to give; 0 for none. Needs the
     *                   sender in game.
     * @param vaultLots  goods to give from the sender's vault
     * @param credits    credits to give
     * @param askItem    the item id asked in return, or null
     * @param askCount   how many of it; 0 for none
     * @param askCredits credits asked in return
     */
    public record Terms(int handCount, List<Lot> vaultLots, long credits, String askItem, int askCount,
            long askCredits) {
        public Terms {
            vaultLots = vaultLots == null ? List.of() : List.copyOf(vaultLots);
        }
    }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    /**
     * Sends an offer from {@code sender} to {@code recipient}.
     *
     * @param live the sender's player when they are in game; required to give from the hand
     */
    public static OfferOutcome send(MinecraftServer server, UUID sender, ServerPlayer live, UUID recipient, Terms terms,
            UUID requestId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (server == null || sender == null || terms == null || requestId == null || terms.handCount() < 0)
            return OfferOutcome.refused(OfferResult.INVALID);
        if (recipient == null) return OfferOutcome.refused(OfferResult.UNKNOWN_RECIPIENT);
        if (sender.equals(recipient)) return OfferOutcome.refused(OfferResult.SELF);
        EconomyStore store = EconomyStore.get(server);
        return send(store, Trading.env(store), sender, live, recipient, terms, requestId);
    }

    /**
     * {@link #send(MinecraftServer, UUID, ServerPlayer, UUID, Terms, UUID)} against a store already
     * resolved, with the environment its trading rules are given: the same checks in the same order.
     */
    public static OfferOutcome send(EconomyStore store, TradeEnv env, UUID sender, ServerPlayer live, UUID recipient,
            Terms terms, UUID requestId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (store == null || env == null || sender == null || terms == null || requestId == null
                || terms.handCount() < 0) return OfferOutcome.refused(OfferResult.INVALID);
        if (recipient == null) return OfferOutcome.refused(OfferResult.UNKNOWN_RECIPIENT);
        if (sender.equals(recipient)) return OfferOutcome.refused(OfferResult.SELF);
        AccountId account = account(sender);
        List<OffersSection.Given> given = new ArrayList<>();
        if (terms.handCount() > 0) {
            if (live == null) return OfferOutcome.refused(OfferResult.NOT_HOLDING);
            // Goods never enter the economy from a creative-mode or spectator inventory.
            if (!GoodsSource.allowed(live)) return OfferOutcome.refused(OfferResult.CREATIVE);
            ItemStack held = live.getMainHandItem();
            if (held.isEmpty() || held.getCount() < terms.handCount()) return OfferOutcome.refused(OfferResult.NOT_HOLDING);
            ItemStack offered = held.copyWithCount(terms.handCount());
            if (!MarketSavedData.tradeable(offered, live.registryAccess()))
                return OfferOutcome.refused(offered.is(MarketSavedData.UNTRADEABLE) ? OfferResult.UNTRADEABLE
                        : OfferResult.OVERSIZED);
            Lot lot = store.goods().lotOf(offered);
            if (lot == null) return OfferOutcome.refused(OfferResult.NOT_HOLDING);
            given.add(new OffersSection.Given(lot, hand(live, held, offered)));
        }
        GoodsOrigin vault = VaultGoods.origin(store, env, account);
        for (Lot lot : terms.vaultLots()) {
            if (lot == null) return OfferOutcome.refused(OfferResult.INVALID);
            given.add(new OffersSection.Given(lot, vault));
        }
        if (!store.market().rememberRequest(sender, requestId)) return OfferOutcome.refused(OfferResult.STALE_REQUEST);
        OfferOutcome outcome = store.offers().send(env, account, account(recipient), given, terms.credits(),
                terms.askItem(), terms.askCount(), terms.askCredits(), null);
        // Goods may have left a live inventory for the store; get the store to disk promptly.
        if (outcome.ok() && terms.handCount() > 0) NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        return outcome;
    }

    /** The main hand as the place an offer's goods come from. {@code held} is the live stack in the hand. */
    private static GoodsOrigin hand(ServerPlayer sender, ItemStack held, ItemStack offered) {
        return new GoodsOrigin() {
            @Override public boolean holds(Lot lot) {
                return sender.getMainHandItem() == held && held.getCount() >= lot.count()
                        && ItemStack.isSameItemSameComponents(held, offered);
            }
            @Override public boolean take(Lot lot) {
                if (!holds(lot)) return false;
                held.shrink(lot.count());
                return true;
            }
            // Back into the inventory, or at the sender's feet when it no longer fits: never lost.
            @Override public void restore(Lot lot) { VaultStacks.handBack(sender, offered.copyWithCount(lot.count())); }
        };
    }

    /** Accepts an offer as its recipient; works with the recipient away from the game. */
    public static OfferOutcome accept(MinecraftServer server, UUID actor, UUID offerId, UUID requestId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (server == null || actor == null || offerId == null || requestId == null)
            return OfferOutcome.refused(OfferResult.INVALID);
        EconomyStore store = EconomyStore.get(server);
        return accept(store, Trading.env(store), actor, offerId, requestId);
    }

    /** {@link #accept(MinecraftServer, UUID, UUID, UUID)} against a store already resolved. */
    public static OfferOutcome accept(EconomyStore store, TradeEnv env, UUID actor, UUID offerId, UUID requestId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (store == null || env == null || actor == null || offerId == null || requestId == null)
            return OfferOutcome.refused(OfferResult.INVALID);
        if (!store.market().rememberRequest(actor, requestId)) return OfferOutcome.refused(OfferResult.STALE_REQUEST);
        return store.offers().accept(env, offerId, account(actor));
    }

    /** Declines an offer as its recipient. Idempotent: a second call finds nothing. */
    public static OfferOutcome decline(MinecraftServer server, UUID actor, UUID offerId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (server == null || actor == null || offerId == null) return OfferOutcome.refused(OfferResult.NOT_FOUND);
        EconomyStore store = EconomyStore.get(server);
        return decline(store, Trading.env(store), actor, offerId);
    }

    /** {@link #decline(MinecraftServer, UUID, UUID)} against a store already resolved. */
    public static OfferOutcome decline(EconomyStore store, TradeEnv env, UUID actor, UUID offerId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (store == null || env == null || actor == null || offerId == null)
            return OfferOutcome.refused(OfferResult.NOT_FOUND);
        return store.offers().decline(env, offerId, account(actor));
    }

    /** Cancels an offer as its sender. Idempotent: a second call finds nothing. */
    public static OfferOutcome cancel(MinecraftServer server, UUID actor, UUID offerId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (server == null || actor == null || offerId == null) return OfferOutcome.refused(OfferResult.NOT_FOUND);
        EconomyStore store = EconomyStore.get(server);
        return cancel(store, Trading.env(store), actor, offerId);
    }

    /** {@link #cancel(MinecraftServer, UUID, UUID)} against a store already resolved. */
    public static OfferOutcome cancel(EconomyStore store, TradeEnv env, UUID actor, UUID offerId) {
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (store == null || env == null || actor == null || offerId == null)
            return OfferOutcome.refused(OfferResult.NOT_FOUND);
        return store.offers().cancel(env, offerId, account(actor));
    }

    /** Mutes or unmutes offers and trade requests for a player. Moves no value. */
    public static void mute(MinecraftServer server, UUID player, boolean mute) {
        if (server == null || player == null || EconomyHealth.frozen()) return;
        EconomyStore.get(server).offers().setMuted(account(player), mute);
    }

    /** Whether a player has muted offers and trade requests. */
    public static boolean muted(MinecraftServer server, UUID player) {
        return server != null && player != null && EconomyStore.get(server).offers().muted(account(player));
    }
}
