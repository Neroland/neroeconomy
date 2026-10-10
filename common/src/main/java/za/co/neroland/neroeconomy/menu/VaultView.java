package za.co.neroland.neroeconomy.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.GoodsSection;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.vault.VaultSection;
import za.co.neroland.neroeconomy.vault.VaultSlotRule;
import za.co.neroland.neroeconomy.vault.VaultStacks;
import za.co.neroland.neroeconomy.vault.VaultWindow;

/**
 * One player's vault as the container behind an open {@link VaultMenu}: a live view of the vault
 * section, server side only.
 *
 * <p>The game's own container code moves stacks between slots, the cursor and the inventory, and
 * it changes stacks in place, so the slots here are ordinary item stacks it is free to work on.
 * What keeps the store and the slots the same thing is the order of three steps around every
 * click, all on the server thread and in one call:
 * <ol>
 *   <li>{@link #refresh} — if the vault changed since the slots were filled (a delivery arrived),
 *       the slots are filled again from the store, so a click never acts on a stale picture;</li>
 *   <li>the game handles the click against the slots;</li>
 *   <li>{@link #commit} — the slots are read back as lots and written to the store as the vault's
 *       new contents, hidden stacks move into slots that became empty, and the prompt save is
 *       asked for, because goods crossed between the store and a live inventory.</li>
 * </ol>
 * Between clicks the slots and the store are therefore always equal, which is why a carried stack,
 * a death, a disconnect or a closed screen needs no special handling: none of them touches a slot.
 *
 * <p>Nothing is ever dropped by this class. A stack that reached a slot it may not be in — past
 * the cap, or goods that may not be traded — is put back into the player's inventory.
 */
final class VaultView implements Container {
    private final EconomyStore store;
    private final ServerPlayer player;
    private final AccountId account;
    private final NonNullList<ItemStack> slots = NonNullList.withSize(VaultMenu.SLOTS, ItemStack.EMPTY);
    /** What each slot held, as a lot, when the slots and the store last agreed. */
    private final Lot[] agreed = new Lot[VaultMenu.SLOTS];
    private VaultWindow window;
    private long revision = -1L;
    private int active;
    private boolean touched;

    VaultView(EconomyStore store, ServerPlayer player) {
        this.store = store;
        this.player = player;
        this.account = new PlayerAccountId(player.getUUID());
        load();
    }

    // --- what the menu asks ------------------------------------------------------------------

    /** Slots a stack may be put in: the vault cap. */
    int active() { return active; }

    /** Stacks of the vault that are not in any slot. */
    int hidden() { return window.hiddenCount(); }

    /**
     * Whether the player may put this stack into the vault at all. Never from a creative-mode or
     * spectator inventory: the vault is part of the economy, and goods in it can be sold.
     */
    boolean accepts(ItemStack stack) {
        return !EconomyHealth.frozen() && GoodsSource.allowed(player)
                && MarketSavedData.tradeable(stack, player.registryAccess());
    }

    // --- keeping the slots and the store the same --------------------------------------------

    /**
     * Before every click and once a tick while open: first commits a change no click accounted
     * for, then fills the slots again if the vault, or the cap, changed since they were last filled.
     */
    void refresh() {
        if (touched) commit();
        if (store.vault().revision(account) != revision || Math.min(VaultMenu.SLOTS, VaultStacks.cap()) != active)
            load();
    }

    private void load() {
        GoodsSection goods = store.goods();
        active = Math.min(VaultMenu.SLOTS, VaultStacks.cap());
        window = VaultWindow.open(store.vault().stacks(account), active, goods::showable);
        for (int slot = 0; slot < VaultMenu.SLOTS; slot++) {
            Lot lot = window.slot(slot);
            agreed[slot] = lot;
            slots.set(slot, lot == null ? ItemStack.EMPTY : goods.stackOf(lot));
        }
        revision = store.vault().revision(account);
        touched = false;
    }

    /**
     * Writes the slots to the store. Called after every click, and before the slots are refilled
     * whenever something changed them outside a click.
     */
    void commit() {
        touched = false;
        if (EconomyHealth.frozen()) return;
        GoodsSection goods = store.goods();
        VaultSection vault = store.vault();
        Lot[] now = new Lot[VaultMenu.SLOTS];
        for (int slot = 0; slot < VaultMenu.SLOTS; slot++) now[slot] = lotIn(slot, goods);
        if (vault.revision(account) != revision) {
            // The vault changed underneath the slots without a refresh in between, which no click
            // can cause. The slots are not written over it: only what they gained or lost is applied.
            applyDifference(now, vault, goods);
            load();
            return;
        }
        for (int slot = 0; slot < active; slot++) window.set(slot, now[slot]);
        // Stacks that did not fit on the screen move into the slots that have become free.
        for (int slot : window.surface(goods::showable)) {
            now[slot] = window.slot(slot);
            slots.set(slot, goods.stackOf(now[slot]));
        }
        vault.replaceAll(account, window.contents());
        System.arraycopy(now, 0, agreed, 0, now.length);
        revision = vault.revision(account);
    }

    /**
     * The lot a slot holds now. A stack that may not be here — in a slot past the cap, or new goods
     * that may not be traded — is handed back to the player and the slot is left empty.
     */
    private Lot lotIn(int slot, GoodsSection goods) {
        ItemStack stack = slots.get(slot);
        if (stack.isEmpty()) {
            if (stack != ItemStack.EMPTY) slots.set(slot, ItemStack.EMPTY);
            return null;
        }
        Lot before = agreed[slot];
        Lot seen = goods.peekLot(stack);
        // The goods that were already here may stay, in whatever quantity is left of them. A slot whose
        // count grew holds new goods too, and those are asked the same question as a new stack.
        if (slot < active && before != null && seen != null && seen.sameGoods(before)) {
            int stay = VaultSlotRule.mayStay(before.count(), seen.count(),
                    seen.count() <= before.count() || accepts(stack));
            if (stay == seen.count()) return seen;
            VaultStacks.handBack(player, stack.split(seen.count() - stay));
            return seen.withCount(stay);
        }
        if (slot < active && accepts(stack)) {
            Lot lot = goods.lotOf(stack);
            if (lot != null) return lot;
        }
        slots.set(slot, ItemStack.EMPTY);
        VaultStacks.handBack(player, stack);
        return null;
    }

    /** Applies what the slots gained and lost since they last agreed with the store, kind by kind. */
    private void applyDifference(Lot[] now, VaultSection vault, GoodsSection goods) {
        Map<Lot.Key, Long> change = new HashMap<>();
        for (Lot lot : now) if (lot != null) change.merge(lot.key(), (long) lot.count(), Long::sum);
        for (Lot lot : agreed) if (lot != null) change.merge(lot.key(), (long) -lot.count(), Long::sum);
        long missing = 0L;
        for (Map.Entry<Lot.Key, Long> entry : change.entrySet()) {
            Lot.Key key = entry.getKey();
            long amount = entry.getValue();
            if (amount < 0L) missing += -amount - vault.withdraw(account, key.item(), key.payload(), -amount);
            for (long left = amount; left > 0L; left -= Integer.MAX_VALUE)
                vault.deliver(account, List.of(new Lot(key.item(), (int) Math.min(left, Integer.MAX_VALUE),
                        key.payload())), VaultStacks.cap(), VaultStacks.hardBound(), goods.limits());
        }
        if (missing > 0L) NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A vault screen and the vault behind it "
                + "disagreed and could not be reconciled exactly; {} items were taken out that the vault no "
                + "longer held.", missing);
    }

    // --- Container ---------------------------------------------------------------------------

    @Override public int getContainerSize() { return VaultMenu.SLOTS; }

    @Override public boolean isEmpty() {
        for (ItemStack stack : slots) if (!stack.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) {
        return slot < 0 || slot >= VaultMenu.SLOTS ? ItemStack.EMPTY : slots.get(slot);
    }

    @Override public ItemStack removeItem(int slot, int count) {
        ItemStack removed = ContainerHelper.removeItem(slots, slot, count);
        if (!removed.isEmpty()) touched = true;
        return removed;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(slots, slot);
        if (!removed.isEmpty()) touched = true;
        return removed;
    }

    @Override public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= VaultMenu.SLOTS) return;
        slots.set(slot, stack);
        touched = true;
    }

    @Override public void setChanged() { touched = true; }

    @Override public boolean stillValid(Player user) { return user == player && !EconomyHealth.frozen(); }

    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot < active && accepts(stack); }

    /** A vault is never emptied wholesale: nothing may destroy what it holds. */
    @Override public void clearContent() { }
}
