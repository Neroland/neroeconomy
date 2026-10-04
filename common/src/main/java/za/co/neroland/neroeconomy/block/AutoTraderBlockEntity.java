package za.co.neroland.neroeconomy.block;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.menu.AutoTraderMenu;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.trader.AutoTraders;
import za.co.neroland.neroeconomy.trader.TraderInventory;

/**
 * The Auto-Trader: a container that one auto-trade rule works from. It is an ordinary vanilla
 * {@link WorldlyContainer} with eighteen real slots saved with the block, which is all a hopper, a
 * NeroLogistics duct or drone, or another mod's pipe needs to see:
 *
 * <ul>
 *   <li>slots 0–8 are <b>inputs</b>: any face may insert any stack that may be traded, and no face
 *       may extract. A sell rule lists the unmodified goods it finds there.</li>
 *   <li>slots 9–17 are <b>outputs</b>: no face may insert, any face may extract. A buy rule puts
 *       what it buys there.</li>
 * </ul>
 * {@link #canPlaceItem}, {@link #canPlaceItemThroughFace} and {@link #canTakeItemThroughFace}
 * answer truthfully for every slot, because some transports insert without asking first.
 *
 * <p><b>The owner</b> is not stored here. It is recorded only in the store's Auto-Trader section,
 * by position, so an erasure reaches a block in a chunk that is not loaded and no player id is
 * left behind in the world. <b>The rule</b> is not stored here either: it names this position as
 * its binding (see {@link AutoTraders}).
 *
 * <p><b>It never loads a chunk.</b> The rule reaches the slots only through {@link #slots}, which
 * reports itself unreachable unless the block's chunk is loaded and ticking; the rule then pauses
 * and its order leaves the book.
 *
 * <p><b>Removal</b>, by anything: the rule is deleted (its budget goes back to the owner, the
 * goods it had listed — which are on the order book, not in a slot — go to the owner's vault), and
 * what the slots hold, inputs nobody had listed yet and outputs alike, drops like a chest's
 * contents. A removal that skips the drop (a command that replaces the block) loses the slots'
 * contents exactly as it loses a chest's; the rule and its budget are still settled.
 */
public final class AutoTraderBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    public static final int INPUTS = TraderInventory.INPUTS;
    public static final int SLOTS = TraderInventory.SLOTS;
    /** The rule is told about a change to the slots at most this often. */
    private static final int WAKE_TICKS = 20;
    private static final int[] EVERY_SLOT = everySlot();

    private static int[] everySlot() {
        int[] slots = new int[SLOTS];
        for (int slot = 0; slot < SLOTS; slot++) slots[slot] = slot;
        return slots;
    }

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** The block is being torn down: nothing in it can be reached by its rule any more. */
    private boolean removing;
    /** Whether the slots changed since the rule was last told to look. */
    private boolean stirred = true;
    private int wakeCountdown;

    public AutoTraderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AUTO_TRADER.get(), pos, state);
    }

    // --- the owner ---------------------------------------------------------------------------

    private UUID owner() {
        return level instanceof ServerLevel serverLevel ? AutoTraders.owner(serverLevel, worldPosition) : null;
    }

    public boolean hasOwner() { return owner() != null; }

    public boolean isOwner(UUID player) {
        UUID current = owner();
        return current != null && current.equals(player);
    }

    // --- the rule's view of the slots --------------------------------------------------------

    /** The slots as the rule bound to this block reaches them. */
    public TraderInventory.Slots slots() { return view; }

    private final TraderInventory.Slots view = new TraderInventory.Slots() {
        @Override public boolean reachable() {
            return !removing && !isRemoved() && level instanceof ServerLevel serverLevel
                    && serverLevel.shouldTickBlocksAt(worldPosition);
        }

        @Override public boolean empty(int slot) { return items.get(slot).isEmpty(); }

        @Override public Lot unmodified(int slot) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty() || !MarketSavedData.pristine(stack)) return null;
            return Lot.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
        }

        @Override public void set(int slot, Lot lot) {
            ItemStack stack = ItemStack.EMPTY;
            if (lot != null) {
                Item item = item(lot.item());
                // An id this server no longer knows names nothing that can be held: the slot is left empty.
                if (item != null) stack = new ItemStack(item, lot.count());
            }
            items.set(slot, stack);
            // Marks the chunk to be saved without waking the rule: this change is the rule's own.
            AutoTraderBlockEntity.super.setChanged();
            // Goods crossed between the world and the store; get the store to disk promptly.
            NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        }

        @Override public int limit(String id) {
            Item item = item(id);
            // An id this server does not know cannot be held in a slot; such goods go to the vault.
            return item == null ? 0 : Math.min(getMaxStackSize(), item.getDefaultMaxStackSize());
        }
    };

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        Item value = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return value == null || value == Items.AIR ? null : value;
    }

    // --- ticking -----------------------------------------------------------------------------

    /** Server tick: when the slots changed — or the block has just loaded — the rule is told to look again. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, AutoTraderBlockEntity trader) {
        if (!trader.stirred || --trader.wakeCountdown > 0) return;
        trader.stirred = false;
        trader.wakeCountdown = WAKE_TICKS;
        if (level instanceof ServerLevel serverLevel) AutoTraders.wake(serverLevel, pos);
    }

    /**
     * The chunk is unloading, or the block is going. The rule is queued so that it notices at once
     * that it can no longer reach the slots, rather than at the next simulation step.
     */
    @Override public void setRemoved() {
        super.setRemoved();
        if (!removing && level instanceof ServerLevel serverLevel) AutoTraders.wake(serverLevel, worldPosition);
    }

    // --- removal -----------------------------------------------------------------------------

    /**
     * The block is being replaced, by anything: a player, an explosion, a command that keeps side
     * effects. The rule goes first, with the block already unreachable, so the goods it had listed
     * go from the order book to the owner's vault rather than back into slots that are about to
     * be emptied; then the default handler drops what the slots hold, inputs and outputs alike,
     * as it drops a chest's contents.
     */
    @Override public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        removing = true;
        if (level instanceof ServerLevel serverLevel) AutoTraders.removed(serverLevel, pos);
        super.preRemoveSideEffects(pos, state);
    }

    // --- MenuProvider ------------------------------------------------------------------------

    @Override public Component getDisplayName() { return Component.translatable("container.neroeconomy.auto_trader"); }

    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new AutoTraderMenu(id, inventory, this);
    }

    // --- Container ---------------------------------------------------------------------------

    @Override public int getContainerSize() { return SLOTS; }

    @Override public boolean isEmpty() {
        for (ItemStack stack : items) if (!stack.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) {
        return slot < 0 || slot >= SLOTS ? ItemStack.EMPTY : items.get(slot);
    }

    @Override public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }

    @Override public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOTS) return;
        items.set(slot, stack);
        setChanged();
    }

    /** Any change to the slots, by a player, a hopper or a pipe: the rule will be told to look again. */
    @Override public void setChanged() {
        super.setChanged();
        stirred = true;
    }

    /** Same rule as every NeroEconomy menu: still here, in the player's own level, and in reach. */
    @Override public boolean stillValid(Player player) { return IntentGuard.anchored(player, this); }

    /**
     * Only an input slot takes anything, and only a stack that may be traded. Where an item comes
     * from cannot be known for a hopper or a pipe, so nothing else is asked here; a player putting
     * a stack in by hand is held to the creative-mode guard by the menu.
     */
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= INPUTS || stack == null || stack.isEmpty()) return false;
        // The client cannot judge the size cap; the server has the last word.
        if (level == null || level.isClientSide()) return true;
        return MarketSavedData.tradeable(stack, level.registryAccess());
    }

    /**
     * Empties the block on behalf of a caller that will not keep the contents, exactly as a
     * chest is emptied. What the rule had listed is not in a slot and is not touched.
     */
    @Override public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override public int[] getSlotsForFace(Direction side) { return EVERY_SLOT; }

    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return canPlaceItem(slot, stack);
    }

    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot >= INPUTS && slot < SLOTS;
    }

    // --- persistence -------------------------------------------------------------------------

    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
    }

    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
        stirred = true;
    }
}
