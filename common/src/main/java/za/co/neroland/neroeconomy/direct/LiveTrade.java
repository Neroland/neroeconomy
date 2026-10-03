package za.co.neroland.neroeconomy.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.direct.TradeSession.CancelReason;
import za.co.neroland.neroeconomy.direct.TradeSession.Change;
import za.co.neroland.neroeconomy.direct.TradeSession.Side;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.GoodsSection;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.menu.DirectTradeMenu;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * One open direct trade in the running game: the {@link TradeSession} that holds its rules, the
 * two players, their two windows and their two sets of "you give" slots. Everything here runs on
 * the server thread.
 *
 * <p><b>Slots and escrow.</b> A side's nine slots are a {@link TradeOffer}. After every click, and
 * once a tick for anything that changed a slot outside a click, {@link #commit} turns the slots
 * into lots, writes them to {@link TradeEscrowSection} and asks for the prompt save — so between
 * clicks the slots and the store always agree, and a stack is in the store from the moment it
 * leaves the inventory. A stack that reached a slot it may not be in is handed back to its player.
 *
 * <p><b>Ending.</b> A swap ({@link TradeSwap}) hands each side's goods to the other; a cancel
 * hands them back. Either way the lots come out of the escrow and go to a player who is in the
 * game and alive — into the inventory, with whatever does not fit going to the vault — or
 * straight to the vault of one who is not. Nothing is ever dropped on the ground.
 */
public final class LiveTrade {
    private final MinecraftServer server;
    private final TradeSession session;
    private final ServerPlayer[] players = new ServerPlayer[2];
    private final TradeOffer[] offers = {new TradeOffer(), new TradeOffer()};
    /** The terminal each player was at when the trade began, or null; see {@link TradePresence}. */
    private final BlockEntity[] anchors = new BlockEntity[2];
    private final AbstractContainerMenu[] menus = new AbstractContainerMenu[2];
    /** A side whose window is already closing must not be told to close it again. */
    private final boolean[] windowGone = new boolean[2];

    LiveTrade(MinecraftServer server, ServerPlayer first, BlockEntity firstAnchor, ServerPlayer second,
            BlockEntity secondAnchor) {
        this.server = server;
        this.session = new TradeSession(Accounts.of(first.getUUID()), Accounts.of(second.getUUID()));
        this.players[0] = first;
        this.players[1] = second;
        this.anchors[0] = firstAnchor;
        this.anchors[1] = secondAnchor;
    }

    // --- reads -------------------------------------------------------------------------------

    public TradeSession session() { return session; }
    public ServerPlayer player(Side side) { return players[side.ordinal()]; }
    public TradeOffer offer(Side side) { return offers[side.ordinal()]; }

    /** How much of the freeze is left, in tenths of a second, for the countdown on screen. */
    public int lockTenths() {
        return (int) Math.min(Short.MAX_VALUE, (session.lockRemaining(DirectTrades.clock()) + 99L) / 100L);
    }

    private EconomyStore store() { return EconomyStore.get(server); }

    private AccountId account(Side side) { return session.account(side); }

    private void tell(Side side, Component message) {
        ServerPlayer player = player(side);
        if (!player.hasDisconnected()) player.sendSystemMessage(message);
    }

    // --- the rules of the slots --------------------------------------------------------------

    /** Whether a side may change what it gives right now. */
    public boolean mayEdit() { return session.editable() && !EconomyHealth.frozen(); }

    /**
     * Whether a side may put this stack on the table. Never from a creative-mode or spectator
     * inventory, never an item in {@code neroeconomy:untradeable}, never a stack over the size cap.
     */
    public boolean accepts(Side side, ItemStack stack) {
        ServerPlayer player = player(side);
        return mayEdit() && GoodsSource.allowed(player) && MarketSavedData.tradeable(stack, player.registryAccess());
    }

    // --- keeping the slots and the escrow the same -------------------------------------------

    /** Commits a side's slots if anything changed them since the last commit. */
    public void settle(Side side) {
        if (offer(side).touched()) commit(side);
    }

    /**
     * Writes a side's slots to the escrow. Called after every click in that side's window, and
     * for a change no click accounted for. A change the session accepts clears both confirmations.
     */
    public void commit(Side side) {
        TradeOffer offer = offer(side);
        offer.settled();
        if (session.over() || EconomyHealth.frozen()) return;
        EconomyStore store = store();
        GoodsSection goods = store.goods();
        List<Lot> lots = new ArrayList<>(TradeOffer.SLOTS);
        boolean changed = false;
        for (int slot = 0; slot < TradeOffer.SLOTS; slot++) {
            Lot before = offer.agreed(slot);
            Lot now = lotIn(side, slot, goods);
            if (now != null) lots.add(now);
            if (now == null ? before != null : !now.equals(before)) changed = true;
            offer.agree(slot, now);
        }
        if (!changed) return;
        if (!store.tradeEscrow().replace(account(side), lots)) {
            // Nine slots can never hold more than nine stacks, so this cannot happen; if it does the
            // trade ends and everything goes back, rather than carry on with goods that are not recorded.
            cancel(CancelReason.NOT_ALLOWED, null);
            return;
        }
        // The contents were frozen and changed all the same, which no click can do: nobody agreed
        // to what is there now, so the trade is open again and both must confirm afresh.
        if (session.goodsChanged(side) == Change.LOCKED) session.swapRefused();
        // Goods crossed between a live inventory and the store; get the store to disk promptly.
        NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
    }

    /**
     * The lot a slot holds now. What was already there may stay, in whatever quantity is left of
     * it; anything new must be acceptable, and what is not is handed back to the player.
     */
    private Lot lotIn(Side side, int slot, GoodsSection goods) {
        TradeOffer offer = offer(side);
        ItemStack stack = offer.stack(slot);
        if (stack.isEmpty()) {
            if (stack != ItemStack.EMPTY) offer.put(slot, ItemStack.EMPTY);
            return null;
        }
        Lot before = offer.agreed(slot);
        Lot seen = goods.peekLot(stack);
        boolean acceptable = accepts(side, stack);
        if (before != null && seen != null && seen.sameGoods(before)) {
            if (seen.count() <= before.count()) return seen;
            if (!acceptable) {
                // More of what was already there, from an inventory that may not add any: the extra goes back.
                VaultStacks.handBack(player(side), stack.split(stack.getCount() - before.count()));
                return before;
            }
        }
        if (acceptable) {
            Lot lot = goods.lotOf(stack);
            if (lot != null) return lot;
        }
        offer.put(slot, ItemStack.EMPTY);
        VaultStacks.handBack(player(side), stack);
        return null;
    }

    /** What a side has on the table, as the lots the last commit recorded. */
    private List<Lot> lots(Side side) {
        List<Lot> lots = new ArrayList<>(TradeOffer.SLOTS);
        for (int slot = 0; slot < TradeOffer.SLOTS; slot++) {
            Lot lot = offer(side).agreed(slot);
            if (lot != null) lots.add(lot);
        }
        return lots;
    }

    private static long itemCount(List<Lot> lots) {
        long total = 0L;
        for (Lot lot : lots) total += lot.count();
        return total;
    }

    // --- what a window asks for --------------------------------------------------------------

    /** A side sets the credits it gives. The amount is checked now and again at the swap. */
    public void setCredits(Side side, long amount, UUID requestId) {
        if (session.over() || requestId == null) return;
        ServerPlayer player = player(side);
        if (amount > 0L && amount <= TradeSession.MAX_CREDITS) {
            // What may go to another player: the balance less a starting balance that has not vested.
            long spendable = NeroEconomyCommon.CURRENCY_PROVIDER.spendable(player.getUUID());
            if (spendable < amount) {
                tell(side, Component.translatable("message.neroeconomy.direct_trade.credits.too_many", amount, spendable));
                return;
            }
        }
        Change change = session.setCredits(side, amount, requestId, store().market()::rememberRequest);
        if (change == Change.INVALID) tell(side, Component.translatable("message.neroeconomy.direct_trade.credits.invalid"));
        else if (change == Change.LOCKED) tell(side, Component.translatable("message.neroeconomy.direct_trade.locked"));
    }

    /** A side confirms the trade as it stood at {@code revision}. */
    public void confirm(Side side, int revision, UUID requestId) {
        if (session.over() || requestId == null) return;
        // Whatever a click left uncommitted is on the table before the confirmation is judged.
        settle(Side.FIRST);
        settle(Side.SECOND);
        Change change = session.confirm(side, revision, requestId, store().market()::rememberRequest, DirectTrades.clock());
        if (change == Change.STALE_REVISION)
            tell(side, Component.translatable("message.neroeconomy.direct_trade.changed"));
    }

    /** A side takes its confirmation back. */
    public void withdraw(Side side) {
        if (session.withdraw(side) == Change.LOCKED)
            tell(side, Component.translatable("message.neroeconomy.direct_trade.locked"));
    }

    // --- opening -----------------------------------------------------------------------------

    /** Opens both windows. @return false, with the trade cancelled, when either could not be opened */
    boolean open() {
        for (Side side : Side.values()) {
            ServerPlayer player = player(side);
            // The title names the other player: each of them asked for, or agreed to, this trade.
            Component title = Component.translatable("container.neroeconomy.direct_trade", player(side.other()).getName());
            OptionalInt opened = player.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) -> {
                DirectTradeMenu menu = new DirectTradeMenu(containerId, inventory, this, side);
                menus[side.ordinal()] = menu;
                return menu;
            }, title));
            if (opened.isEmpty() || session.over()) {
                cancel(CancelReason.NOT_ALLOWED, null);
                return false;
            }
        }
        return true;
    }

    // --- the tick ----------------------------------------------------------------------------

    private boolean present(Side side) {
        ServerPlayer player = player(side);
        return !player.hasDisconnected() && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private TradePresence.Party party(Side side) {
        ServerPlayer player = player(side);
        BlockEntity anchor = anchors[side.ordinal()];
        return new TradePresence.Party(present(side), player.isAlive(), player.isSpectator(),
                anchor != null && IntentGuard.anchored(player, anchor));
    }

    /** Whether the two may still trade with each other, exactly as it was judged when they began. */
    private TradePresence.Verdict presence() {
        ServerPlayer first = players[0];
        ServerPlayer second = players[1];
        boolean sameLevel = first.level() == second.level();
        return TradePresence.check(party(Side.FIRST), party(Side.SECOND), sameLevel,
                sameLevel ? first.distanceToSqr(second) : Double.NaN, EconomyConfig.TRADE_RANGE_BLOCKS.get());
    }

    /** Once a tick: ends a trade that may not go on, and makes the swap when the freeze runs out. */
    void tick(long now) {
        if (session.over()) {
            DirectTrades.ended(this);
            return;
        }
        if (EconomyHealth.frozen()) {
            cancel(CancelReason.STOPPED, null);
            return;
        }
        TradePresence.Verdict verdict = presence();
        if (!verdict.ok()) {
            cancel(verdict.cancelReason(), null);
            return;
        }
        for (Side side : Side.values()) {
            // A window that is no longer the one the player has open is a closed window.
            if (player(side).containerMenu != menus[side.ordinal()]) {
                cancel(CancelReason.CLOSED, null);
                return;
            }
            settle(side);
        }
        if (session.due(now)) swap();
    }

    // --- the swap ----------------------------------------------------------------------------

    /**
     * Where a side's goods go: the inventory of a player who is in the game and alive, with
     * whatever does not fit going to their vault, and the vault alone otherwise. {@code vaulted}
     * counts the items that went to the vault, so the player can be told.
     */
    private GoodsReceiver receiver(Side side, boolean mayUseInventory, long[] vaulted) {
        EconomyStore store = store();
        TradeEnv env = Trading.env(store);
        ServerPlayer player = player(side);
        AccountId account = account(side);
        boolean live = mayUseInventory && present(side) && player.isAlive() && !player.isRemoved();
        return new GoodsReceiver() {
            @Override public boolean canTake(Lot lot) { return true; }
            @Override public boolean give(Lot lot) {
                ItemStack stack = live ? store.goods().stackOf(lot) : ItemStack.EMPTY;
                if (stack.isEmpty()) {
                    VaultGoods.deliver(store, env, account, lot);
                    vaulted[0] += lot.count();
                    return true;
                }
                int moved = VaultStacks.giveOrDeliver(store, player, stack);
                vaulted[0] += lot.count() - moved;
                return moved < lot.count();
            }
        };
    }

    private void swap() {
        EconomyStore store = store();
        // A player whose starting balance has in fact vested by now is not held to the lock.
        NeroEconomyCommon.CURRENCY_PROVIDER.spendable(player(Side.FIRST).getUUID());
        NeroEconomyCommon.CURRENCY_PROVIDER.spendable(player(Side.SECOND).getUUID());
        long[] firstVaulted = new long[1];
        long[] secondVaulted = new long[1];
        TradeSwap.Outcome outcome = TradeSwap.execute(store, Trading.env(store), account(Side.FIRST),
                session.credits(Side.FIRST), lots(Side.FIRST), receiver(Side.FIRST, true, firstVaulted),
                account(Side.SECOND), session.credits(Side.SECOND), lots(Side.SECOND),
                receiver(Side.SECOND, true, secondVaulted));
        if (!outcome.ok()) {
            // Nothing moved. The trade is open again and both are told why in plain words.
            session.swapRefused();
            for (Side side : Side.values()) tell(side, Component.translatable("message.neroeconomy.direct_trade.swap.refused",
                    Component.translatable(outcome.result().translationKey(side))));
            return;
        }
        session.swapped();
        offers[0].empty();
        offers[1].empty();
        NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        TradeSwap.Terms terms = outcome.terms();
        long firstItems = itemCount(outcome.first());
        long secondItems = itemCount(outcome.second());
        done(Side.FIRST, firstItems, secondItems, terms, firstVaulted[0]);
        done(Side.SECOND, secondItems, firstItems, terms, secondVaulted[0]);
        finish();
    }

    /** Restates, to one side, exactly what it gave and what it received. */
    private void done(Side side, long gaveItems, long gotItems, TradeSwap.Terms terms, long vaulted) {
        tell(side, Component.translatable("message.neroeconomy.direct_trade.done", gaveItems, terms.credits(side),
                gotItems, terms.receives(side)));
        if (terms.tax(side.other()) > 0L)
            tell(side, Component.translatable("message.neroeconomy.direct_trade.done.tax", terms.tax(side.other())));
        if (vaulted > 0L) tell(side, Component.translatable("message.neroeconomy.direct_trade.to_vault", vaulted));
    }

    // --- ending without a swap ---------------------------------------------------------------

    /** A side's window was closed, by the player or by the game. */
    public void windowClosed(Side side) {
        windowGone[side.ordinal()] = true;
        cancel(CancelReason.CLOSED, null);
    }

    /**
     * Ends the trade and returns everything to its owners. Does nothing when the trade is already
     * over, so every path that can end a trade may call it.
     *
     * @param toVault a side whose goods must go to its vault whatever state its player is in (a
     *                player who is leaving the game), or null
     */
    void cancel(CancelReason reason, Side toVault) {
        if (session.over()) return;
        // What a click left uncommitted is recorded first, so the escrow is what the slots hold.
        if (!EconomyHealth.frozen()) {
            settle(Side.FIRST);
            settle(Side.SECOND);
        }
        if (!session.cancel(reason)) return;
        EconomyStore store = store();
        for (Side side : Side.values()) {
            long[] vaulted = new long[1];
            List<Lot> back = TradeSwap.giveBack(store, account(side), receiver(side, side != toVault, vaulted));
            offer(side).empty();
            tell(side, Component.translatable("message.neroeconomy.direct_trade.cancelled",
                    Component.translatable(session.cancelReason().translationKey()), itemCount(back)));
            if (vaulted[0] > 0L) tell(side, Component.translatable("message.neroeconomy.direct_trade.to_vault", vaulted[0]));
        }
        NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        finish();
    }

    /** The trade is over: it leaves the list of open trades and both windows close. */
    private void finish() {
        DirectTrades.ended(this);
        for (Side side : Side.values()) {
            ServerPlayer player = player(side);
            if (windowGone[side.ordinal()] || menus[side.ordinal()] == null || player.containerMenu != menus[side.ordinal()])
                continue;
            windowGone[side.ordinal()] = true;
            if (player.hasDisconnected()) player.doCloseContainer();
            else player.closeContainer();
        }
    }
}
