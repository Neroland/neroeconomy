package za.co.neroland.neroeconomy.book;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * The order book as the rest of the game reaches it: the edge between the pure {@link BookSection}
 * and live players. Every entry point here is one a player, a command or the companion app calls,
 * and each does the same things in the same order before the book is touched: the frozen check,
 * the shape of the request, the creative-mode guard where goods would leave a live inventory, and
 * the shared request ledger, so a replayed intent acts once.
 *
 * <p>Callers still put the shared intent guard and rate limiter in front (a payload through
 * {@code NeroEconomyNetwork.intent}, a command through {@code IntentGuard.withinBudget}).
 */
public final class BookStacks {
    private BookStacks() { }

    /**
     * What posting from the hand did.
     *
     * @param offered the stack that was offered, as it was held; empty when the request was
     *                refused before the hand was looked at
     */
    public record HandSale(Placed placed, ItemStack offered) { }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    // --- selling -----------------------------------------------------------------------------

    /**
     * Posts {@code quantity} of the seller's main-hand stack as a sell order. Whatever crosses
     * open buy orders sells at once; the rest rests on the book.
     */
    public static HandSale sellFromHand(ServerPlayer seller, int quantity, long unitPrice, UUID requestId) {
        if (EconomyHealth.frozen()) return refusedSale(BookResult.FROZEN);
        if (seller == null || quantity < 1 || unitPrice < 1L || requestId == null) return refusedSale(BookResult.INVALID);
        if (!GoodsSource.allowed(seller)) return refusedSale(BookResult.CREATIVE);
        EconomyStore store = EconomyStore.get(seller.level().getServer());
        if (!store.market().rememberRequest(seller.getUUID(), requestId)) return refusedSale(BookResult.STALE_REQUEST);
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty() || held.getCount() < quantity) return refusedSale(BookResult.NOT_HOLDING);
        ItemStack offered = held.copyWithCount(quantity);
        if (!MarketSavedData.tradeable(offered, seller.registryAccess()))
            return new HandSale(Placed.refused(offered.is(MarketSavedData.UNTRADEABLE) ? BookResult.UNTRADEABLE
                    : BookResult.OVERSIZED), offered);
        Lot lot = store.goods().lotOf(offered);
        if (lot == null) return refusedSale(BookResult.NOT_HOLDING);
        Placed placed = store.book().placeSell(Trading.env(store), account(seller.getUUID()), lot, unitPrice,
                OrderSource.HAND, hand(seller, held, offered), null);
        // Goods left a live inventory for the store; get the store to disk promptly.
        if (placed.ok()) NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        return new HandSale(placed, offered);
    }

    private static HandSale refusedSale(BookResult result) { return new HandSale(Placed.refused(result), ItemStack.EMPTY); }

    /**
     * Posts goods from the seller's vault as a sell order: what a command and the companion app
     * use, so it works with the seller away from the game.
     */
    public static Placed sellFromVault(MinecraftServer server, UUID seller, String item, int payload, int quantity,
            long unitPrice, UUID requestId) {
        if (EconomyHealth.frozen()) return Placed.refused(BookResult.FROZEN);
        if (server == null || seller == null || requestId == null || unitPrice < 1L
                || !Lot.valid(item, quantity, payload)) return Placed.refused(BookResult.INVALID);
        EconomyStore store = EconomyStore.get(server);
        if (!store.market().rememberRequest(seller, requestId)) return Placed.refused(BookResult.STALE_REQUEST);
        TradeEnv env = Trading.env(store);
        AccountId account = account(seller);
        return store.book().placeSell(env, account, new Lot(item, quantity, payload), unitPrice, OrderSource.VAULT,
                VaultGoods.origin(store, env, account), null);
    }

    /**
     * Fills one buy order from the seller's main hand. Only an unmodified stack of the item the
     * order names is accepted.
     */
    public static Traded fillFromHand(ServerPlayer seller, UUID orderId, int quantity, UUID requestId) {
        if (EconomyHealth.frozen()) return Traded.refused(BookResult.FROZEN);
        if (seller == null || orderId == null || quantity < 1 || requestId == null) return Traded.refused(BookResult.INVALID);
        if (!GoodsSource.allowed(seller)) return Traded.refused(BookResult.CREATIVE);
        EconomyStore store = EconomyStore.get(seller.level().getServer());
        if (!store.market().rememberRequest(seller.getUUID(), requestId)) return Traded.refused(BookResult.STALE_REQUEST);
        BuyOrder order = store.book().buy(orderId);
        if (order == null) return Traded.refused(BookResult.NOT_FOUND);
        ItemStack held = seller.getMainHandItem();
        Lot whole = held.isEmpty() ? null : store.goods().peekLot(held);
        if (whole == null || !whole.item().equals(order.item())) return Traded.refused(BookResult.WRONG_ITEM);
        if (held.getCount() < quantity) return Traded.refused(BookResult.NOT_ENOUGH);
        if (!MarketSavedData.pristine(held)) return Traded.refused(BookResult.MODIFIED_ITEM);
        if (held.is(MarketSavedData.UNTRADEABLE)) return Traded.refused(BookResult.UNTRADEABLE);
        ItemStack offered = held.copyWithCount(quantity);
        Traded traded = store.book().sellTo(Trading.env(store), account(seller.getUUID()), orderId,
                Lot.of(order.item(), quantity), hand(seller, held, offered));
        // The goods have left a live inventory for a vault; get that vault to disk promptly.
        if (traded.ok()) NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        return traded;
    }

    /** Fills one buy order with unmodified goods from the seller's vault; works with the seller away. */
    public static Traded fillFromVault(MinecraftServer server, UUID seller, UUID orderId, int quantity, UUID requestId) {
        if (EconomyHealth.frozen()) return Traded.refused(BookResult.FROZEN);
        if (server == null || seller == null || orderId == null || quantity < 1 || requestId == null)
            return Traded.refused(BookResult.INVALID);
        EconomyStore store = EconomyStore.get(server);
        if (!store.market().rememberRequest(seller, requestId)) return Traded.refused(BookResult.STALE_REQUEST);
        BuyOrder order = store.book().buy(orderId);
        if (order == null) return Traded.refused(BookResult.NOT_FOUND);
        TradeEnv env = Trading.env(store);
        AccountId account = account(seller);
        return store.book().sellTo(env, account, orderId, Lot.of(order.item(), quantity),
                VaultGoods.origin(store, env, account));
    }

    /** The main hand as the place a sale's goods come from. {@code held} is the live stack in the hand. */
    private static GoodsOrigin hand(ServerPlayer seller, ItemStack held, ItemStack offered) {
        return new GoodsOrigin() {
            @Override public boolean holds(Lot lot) {
                return seller.getMainHandItem() == held && held.getCount() >= lot.count()
                        && ItemStack.isSameItemSameComponents(held, offered);
            }
            @Override public boolean take(Lot lot) {
                if (!holds(lot)) return false;
                held.shrink(lot.count());
                return true;
            }
            // Back into the inventory, or at the seller's feet when it no longer fits: never lost.
            @Override public void restore(Lot lot) { VaultStacks.handBack(seller, offered.copyWithCount(lot.count())); }
        };
    }

    // --- buying ------------------------------------------------------------------------------

    /**
     * Places a buy order for {@code buyer}. Whatever crosses open sell orders is bought at once at
     * their prices; the rest rests on the book with its credits in escrow.
     *
     * @param live the buyer's player when they are in game, so goods bought at once can go to
     *             their inventory; null delivers to their vault
     */
    public static Placed buyOrder(MinecraftServer server, UUID buyer, ServerPlayer live, String item, int quantity,
            long unitPrice, UUID requestId) {
        if (EconomyHealth.frozen()) return Placed.refused(BookResult.FROZEN);
        if (server == null || buyer == null || item == null || quantity < 1 || unitPrice < 1L || requestId == null)
            return Placed.refused(BookResult.INVALID);
        EconomyStore store = EconomyStore.get(server);
        TradeEnv env = Trading.env(store);
        // Checked before the request id is spent, so a mistyped id can simply be corrected.
        if (!env.items().known(item)) return Placed.refused(BookResult.UNKNOWN_ITEM);
        if (!store.market().rememberRequest(buyer, requestId)) return Placed.refused(BookResult.STALE_REQUEST);
        AccountId account = account(buyer);
        return store.book().placeBuy(env, account, item, quantity, unitPrice,
                live != null ? OrderSource.HAND : OrderSource.VAULT, false, receiver(store, env, account, live), null);
    }

    /**
     * Buys from one sell order at up to {@code ceiling} per unit. The caller has already recorded
     * the request id.
     *
     * @param live the buyer's player when they are in game; null delivers to their vault
     */
    public static Traded buyFromOrder(EconomyStore store, UUID buyer, ServerPlayer live, UUID orderId, int quantity,
            long ceiling) {
        if (store == null || buyer == null) return Traded.refused(BookResult.INVALID);
        TradeEnv env = Trading.env(store);
        AccountId account = account(buyer);
        Traded traded = store.book().buyFrom(env, account, orderId, quantity, ceiling, receiver(store, env, account, live));
        // Goods that went into a live inventory are the one step that spans two files; save promptly.
        if (traded.ok() && !traded.toVault()) NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        return traded;
    }

    /**
     * Where a buyer's goods go: the inventory of a buyer who is in game when all of them fit
     * there, and the buyer's vault otherwise.
     */
    private static GoodsReceiver receiver(EconomyStore store, TradeEnv env, AccountId account, ServerPlayer live) {
        GoodsReceiver vault = VaultGoods.receiver(store, env, account);
        if (live == null) return vault;
        return new GoodsReceiver() {
            @Override public boolean canTake(Lot lot) { return fits(lot) || vault.canTake(lot); }

            @Override public boolean give(Lot lot) {
                if (!fits(lot)) return vault.give(lot);
                // One stack at a time: an inventory is only ever offered what one slot can hold.
                ItemStack whole = store.goods().stackOf(lot);
                int perStack = Math.max(1, whole.getMaxStackSize());
                for (int left = lot.count(); left > 0; left -= perStack)
                    VaultStacks.giveOrDeliver(store, live, whole.copyWithCount(Math.min(perStack, left)));
                NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
                return false;
            }

            private boolean fits(Lot lot) {
                ItemStack whole = store.goods().stackOf(lot);
                return !whole.isEmpty() && live.isAlive() && !live.hasDisconnected() && VaultStacks.fits(live, whole);
            }
        };
    }

    // --- cancelling --------------------------------------------------------------------------

    /** Cancels the caller's own order: goods to their vault, unspent escrow to their credit claim. */
    public static CancelResult cancel(MinecraftServer server, UUID actor, UUID orderId) {
        if (EconomyHealth.frozen()) return CancelResult.FROZEN;
        if (server == null || actor == null || orderId == null) return CancelResult.NOT_FOUND;
        EconomyStore store = EconomyStore.get(server);
        return store.book().cancel(Trading.env(store), orderId, account(actor));
    }
}
