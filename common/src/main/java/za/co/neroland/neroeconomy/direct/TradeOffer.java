package za.co.neroland.neroeconomy.direct;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * The nine "you give" slots of one side of an open direct trade, server side only. The game's own
 * container code moves stacks between these slots, the cursor and the inventory and changes them
 * in place, so they are ordinary item stacks it is free to work on; {@link LiveTrade#commit} then
 * writes what the slots hold to the trade escrow after every click, exactly as the vault's screen
 * does for the vault. The same container is what the other side's window shows, read-only, as
 * "they give".
 *
 * <p>{@link #agreed} is what each slot held, as a lot, when the slots and the escrow last agreed.
 * Nothing here ever destroys a stack: {@link #clearContent} does nothing, and only the trade
 * itself empties the slots, after their goods have been handed to somebody.
 */
public final class TradeOffer implements Container {
    public static final int SLOTS = TradeEscrowSection.MAX_STACKS;

    private final NonNullList<ItemStack> slots = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final Lot[] agreed = new Lot[SLOTS];
    private boolean touched;

    TradeOffer() { }

    /** Whether something changed a slot since the last commit. */
    boolean touched() { return touched; }

    void settled() { touched = false; }

    Lot agreed(int slot) { return agreed[slot]; }

    void agree(int slot, Lot lot) { agreed[slot] = lot; }

    /** The live stack in a slot; the caller may change it in place. */
    ItemStack stack(int slot) { return slots.get(slot); }

    void put(int slot, ItemStack stack) { slots.set(slot, stack); }

    /** Forgets every stack: only ever called once the goods behind them have been handed over. */
    void empty() {
        for (int slot = 0; slot < SLOTS; slot++) {
            slots.set(slot, ItemStack.EMPTY);
            agreed[slot] = null;
        }
        touched = false;
    }

    // --- Container ---------------------------------------------------------------------------

    @Override public int getContainerSize() { return SLOTS; }

    @Override public boolean isEmpty() {
        for (ItemStack stack : slots) if (!stack.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) {
        return slot < 0 || slot >= SLOTS ? ItemStack.EMPTY : slots.get(slot);
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
        if (slot < 0 || slot >= SLOTS) return;
        slots.set(slot, stack);
        touched = true;
    }

    @Override public void setChanged() { touched = true; }

    /** The window decides who may use it; see {@code DirectTradeMenu.stillValid}. */
    @Override public boolean stillValid(Player player) { return true; }

    /** What is on the table is never emptied wholesale: nothing may destroy what it holds. */
    @Override public void clearContent() { }
}
