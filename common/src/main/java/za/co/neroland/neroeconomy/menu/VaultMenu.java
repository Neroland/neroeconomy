package za.co.neroland.neroeconomy.menu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.hub.HubOpener;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.RateLimiter;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

/**
 * The vault screen's menu: a chest-style grid over the player's own vault, plus their inventory.
 * It is opened by the server from the Bank Terminal, for the player standing at it, and it stays
 * valid only while they stay there.
 *
 * <p>The grid always has {@link #SLOTS} slots so the screen has one layout; the first
 * {@code vaultStacks} of them can be used and the rest are switched off. A vault that holds more
 * stacks than fit is still fully reachable: stacks that are not on screen move into slots as they
 * become empty.
 *
 * <p><b>Putting in</b> follows the vault's rules: only goods that may be traded, only into the
 * usable slots, and not at all while the vault holds more stacks than its cap — a new stack needs
 * the vault to be under the cap, topping up a stack needs it to be at or under. <b>Taking out</b>
 * always works. Every click is settled against the store before the next one is looked at (see
 * {@link VaultView}), so shift-click, dragging, number-key swaps, double-click collect and
 * dropping all end with the vault and the screen equal.
 */
public final class VaultMenu extends AbstractContainerMenu implements TerminalAnchored {
    public static final int COLUMNS = 9;
    public static final int ROWS = 6;
    public static final int SLOTS = COLUMNS * ROWS;
    /** Top-left of the grid and of the player inventory; the screen's panel is painted to match. */
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    public static final int INVENTORY_Y = 153;
    public static final int HOTBAR_Y = 211;
    // Data slots: how many grid slots can be used, and how many stacks are not on screen.
    private static final int CAP_SLOT = 0;
    private static final int HIDDEN_SLOT = 1;
    private static final int DATA_COUNT = 2;

    private final Container grid;
    /** The live view of the store; null on the client, which only mirrors what it is sent. */
    private final VaultView view;
    private final ContainerData data;
    private final BlockEntity terminal;

    /** Client side: a plain container the server's slot updates are written into. */
    public VaultMenu(int id, Inventory inventory) {
        this(id, inventory, (VaultView) null, null);
    }

    /** Server side: {@code player}'s own vault, opened at {@code terminal}. */
    public VaultMenu(int id, Inventory inventory, ServerPlayer player, BlockEntity terminal) {
        this(id, inventory, new VaultView(EconomyStore.get(player.level().getServer()), player), terminal);
    }

    @SuppressWarnings("this-escape")
    private VaultMenu(int id, Inventory inventory, VaultView view, BlockEntity terminal) {
        super(ModMenuTypes.VAULT.get(), id);
        this.view = view;
        this.terminal = terminal;
        this.grid = view != null ? view : new SimpleContainer(SLOTS);
        this.data = view != null ? new ViewData(view) : new SimpleContainerData(DATA_COUNT);
        checkContainerSize(grid, SLOTS);
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
        for (int row = 0; row < ROWS; row++) for (int col = 0; col < COLUMNS; col++)
            addSlot(new VaultSlot(grid, col + row * COLUMNS, GRID_X + col * 18, GRID_Y + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < COLUMNS; col++)
            addSlot(new Slot(inventory, col + row * COLUMNS + 9, GRID_X + col * 18, INVENTORY_Y + row * 18));
        for (int col = 0; col < COLUMNS; col++) addSlot(new Slot(inventory, col, GRID_X + col * 18, HOTBAR_Y));
    }

    /** Menu button: back to the Economy Hub, on its Vault tab, at the same terminal. */
    public static final int BACK_BUTTON = 0;

    @Override public boolean clickMenuButton(Player player, int id) {
        if (id != BACK_BUTTON || !(player instanceof ServerPlayer opener) || terminal == null
                || !IntentGuard.button(player, this, RateLimiter.Budget.QUERY)) return false;
        HubOpener.open(opener, terminal, HubTab.VAULT);
        return true;
    }

    /** The Bank Terminal the vault was opened at: a player looking at their vault is still at it. */
    @Override public BlockEntity terminalAnchor() { return terminal; }

    // --- what the screen shows ---------------------------------------------------------------

    /** How many grid slots can be used: {@code vaultStacks}. All of them until the server has said. */
    public int cap() {
        int cap = data.get(CAP_SLOT);
        return cap <= 0 ? SLOTS : Math.min(SLOTS, cap);
    }

    /** Stacks of the vault that are not on screen. */
    public int hidden() { return Math.max(0, data.get(HIDDEN_SLOT)); }

    /** Every stack of the vault: the ones in the grid plus the ones not on screen. */
    public int stackCount() {
        int shown = 0;
        for (int slot = 0; slot < SLOTS; slot++) if (!grid.getItem(slot).isEmpty()) shown++;
        return shown + hidden();
    }

    /** Whether the vault holds more stacks than its cap, so that nothing can be put in. */
    public boolean overCap() { return stackCount() > cap(); }

    // --- the rules of putting in -------------------------------------------------------------

    /** Whether {@code stack} may go into the grid slot {@code slot}, which holds {@code held}. */
    private boolean mayPlace(int slot, ItemStack held, ItemStack stack) {
        int cap = cap();
        if (slot >= cap) return false;
        int stacks = stackCount();
        // A new stack needs the vault to be under its cap; topping one up, or swapping, needs it not over.
        if (held.isEmpty() ? stacks >= cap : stacks > cap) return false;
        // The client cannot judge the size cap; the server has the last word and corrects it.
        return view == null || view.accepts(stack);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < SLOTS) {
            // Out of the vault: always allowed, into the inventory from the hotbar end, as a chest does.
            if (!moveItemStackTo(stack, SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Into the vault: the merge below does not ask the slots, so the rules are asked here.
            if (overCap() || (view != null && !view.accepts(stack))) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, 0, cap(), false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    // --- keeping the store and the slots the same --------------------------------------------

    @Override public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (view == null) {
            super.clicked(slotIndex, buttonNum, containerInput, player);
            return;
        }
        // A delivery that arrived since the last click is on screen before this click is judged.
        view.refresh();
        try {
            super.clicked(slotIndex, buttonNum, containerInput, player);
        } finally {
            view.commit();
        }
    }

    @Override public void broadcastChanges() {
        if (view != null) view.refresh();
        super.broadcastChanges();
    }

    @Override public void removed(Player player) {
        super.removed(player);
        if (view != null) view.refresh();
    }

    @Override public boolean stillValid(Player player) {
        if (view == null) return true;
        return !EconomyHealth.frozen() && view.stillValid(player) && IntentGuard.anchored(player, terminal);
    }

    /** A grid slot: usable up to the cap, and only for goods the vault accepts. */
    private final class VaultSlot extends Slot {
        VaultSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }

        @Override public boolean mayPlace(ItemStack stack) {
            return VaultMenu.this.mayPlace(getContainerSlot(), getItem(), stack);
        }

        /** Slots past the cap are not drawn and cannot be hovered. */
        @Override public boolean isActive() { return getContainerSlot() < cap(); }
    }

    private static final class ViewData implements ContainerData {
        private final VaultView view;
        ViewData(VaultView view) { this.view = view; }
        @Override public int get(int index) {
            // A data slot travels as a short; a count past that is shown as the largest it can carry.
            if (index == CAP_SLOT) return view.active();
            if (index == HIDDEN_SLOT) return Math.min(Short.MAX_VALUE, view.hidden());
            return 0;
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
