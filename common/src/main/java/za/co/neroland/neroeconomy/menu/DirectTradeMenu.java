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

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.direct.LiveTrade;
import za.co.neroland.neroeconomy.direct.TradeOffer;
import za.co.neroland.neroeconomy.direct.TradeSession;
import za.co.neroland.neroeconomy.direct.TradeSession.Side;
import za.co.neroland.neroeconomy.economy.Fees;
import za.co.neroland.neroeconomy.network.DirectTradePayload;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

/**
 * One player's window of a direct trade: nine "you give" slots that player can change, nine "they
 * give" slots that only show what the other player has put in, and the player's inventory. Each
 * player has their own menu; the two share one {@link LiveTrade}.
 *
 * <p><b>You give.</b> A slot takes a stack only while the trade is open for changes, only goods
 * that may be traded, and never from a creative-mode or spectator inventory. Every click is
 * settled against the trade escrow before the next one is looked at ({@link LiveTrade#commit}),
 * so shift-click, dragging, number-key and off-hand swaps, double-click collect and dropping all
 * end with the store and the screen equal. A change clears both confirmations.
 *
 * <p><b>They give.</b> These slots can be neither put into nor taken from, by any kind of click:
 * a click aimed at one is ignored before the game's own click handling sees it.
 *
 * <p><b>Frozen.</b> Once both players have confirmed, no slot in either window accepts a change
 * until the swap is made or a player cancels by closing the window.
 */
public final class DirectTradeMenu extends AbstractContainerMenu {
    public static final int GIVE_SLOTS = TradeOffer.SLOTS;
    /** Slot indices: the "you give" slots come first, then the "they give" slots, then the inventory. */
    public static final int THEIRS_START = GIVE_SLOTS;
    public static final int INVENTORY_START = GIVE_SLOTS * 2;
    /** Top-left of each three-by-three pane and of the inventory; the screen's panel is painted to match. */
    public static final int GIVE_X = 8;
    public static final int THEIRS_X = 116;
    public static final int PANE_Y = 27;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 143;
    public static final int HOTBAR_Y = 201;
    // Data slots. Credits and the balance are 64-bit and take four 16-bit slots each (see Packed64).
    private static final int PHASE_SLOT = 0;
    private static final int MINE_SLOT = 1;
    private static final int THEIRS_SLOT = 2;
    private static final int LOCK_SLOT = 3;
    private static final int TAX_SLOT = 4;
    private static final int REVISION_SLOT = 5;
    private static final int MY_CREDITS_SLOT = REVISION_SLOT + Packed64.INT_SLOTS;
    private static final int THEIR_CREDITS_SLOT = MY_CREDITS_SLOT + Packed64.SLOTS;
    private static final int BALANCE_SLOT = THEIR_CREDITS_SLOT + Packed64.SLOTS;
    private static final int DATA_COUNT = BALANCE_SLOT + Packed64.SLOTS;
    private static final int PHASE_OPEN = 0;
    private static final int PHASE_LOCKED = 1;
    private static final int PHASE_OVER = 2;

    /** The trade behind the window; null on the client, which only mirrors what it is sent. */
    private final LiveTrade trade;
    private final Side side;
    private final Player player;
    private final ContainerData data;

    /** Client side: plain containers the server's slot updates are written into. */
    public DirectTradeMenu(int id, Inventory inventory) {
        this(id, inventory, null, Side.FIRST);
    }

    /** Server side: {@code side}'s window of {@code trade}. */
    @SuppressWarnings("this-escape")
    public DirectTradeMenu(int id, Inventory inventory, LiveTrade trade, Side side) {
        super(ModMenuTypes.DIRECT_TRADE.get(), id);
        this.trade = trade;
        this.side = side;
        this.player = inventory.player;
        Container mine = trade != null ? trade.offer(side) : new SimpleContainer(GIVE_SLOTS);
        Container theirs = trade != null ? trade.offer(side.other()) : new SimpleContainer(GIVE_SLOTS);
        this.data = trade != null ? new TradeData() : new SimpleContainerData(DATA_COUNT);
        checkContainerSize(mine, GIVE_SLOTS);
        checkContainerSize(theirs, GIVE_SLOTS);
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            addSlot(new GiveSlot(mine, col + row * 3, GIVE_X + col * 18, PANE_Y + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            addSlot(new ShownSlot(theirs, col + row * 3, THEIRS_X + col * 18, PANE_Y + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
    }

    // --- what the screen shows ---------------------------------------------------------------

    /** Whether what the sides give may still be changed: not frozen, and not over. */
    public boolean editable() { return data.get(PHASE_SLOT) == PHASE_OPEN; }
    /** Whether both have confirmed and the contents are frozen until the swap. */
    public boolean locked() { return data.get(PHASE_SLOT) == PHASE_LOCKED; }
    public boolean confirmedByMe() { return data.get(MINE_SLOT) != 0; }
    public boolean confirmedByThem() { return data.get(THEIRS_SLOT) != 0; }
    /** What is left of the freeze, in tenths of a second. */
    public int lockTenths() { return Math.max(0, data.get(LOCK_SLOT)); }
    /** The revision a confirmation must name: it moves on with every change by either side. */
    public int revision() { return Packed64.readInt(data::get, REVISION_SLOT); }
    public long myCredits() { return Packed64.read(data::get, MY_CREDITS_SLOT); }
    public long theirCredits() { return Packed64.read(data::get, THEIR_CREDITS_SLOT); }
    public long balance() { return Packed64.read(data::get, BALANCE_SLOT); }
    /** The trade tax on {@code credits}, exactly as the swap will work it out. */
    public long tax(long credits) { return Fees.feeForBasisPoints(credits, Math.max(0, data.get(TAX_SLOT))); }

    /** How many items are in one of the two panes. */
    public long itemCount(boolean mine) {
        long total = 0L;
        int first = mine ? 0 : THEIRS_START;
        for (int slot = first; slot < first + GIVE_SLOTS; slot++) total += slots.get(slot).getItem().getCount();
        return total;
    }

    // --- the rules of the slots --------------------------------------------------------------

    private boolean mayEdit() { return trade != null ? trade.mayEdit() : editable(); }

    /** The client cannot judge the size cap or the tag; the server has the last word and corrects it. */
    private boolean accepts(ItemStack stack) { return trade != null ? trade.accepts(side, stack) : editable(); }

    @Override public ItemStack quickMoveStack(Player user, int index) {
        if (index < 0 || index >= slots.size() || (index >= THEIRS_START && index < INVENTORY_START)) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !mayEdit()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < THEIRS_START) {
            // Back out of the trade: into the inventory from the hotbar end, as a chest does.
            if (!moveItemStackTo(stack, INVENTORY_START, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Onto the table: the merge below does not ask the slots, so the rules are asked here.
            if (!accepts(stack) || !moveItemStackTo(stack, 0, GIVE_SLOTS, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    /** Double-click collect never gathers from the other player's pane, nor from a frozen one. */
    @Override public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
        return target.index >= INVENTORY_START || (target.index < THEIRS_START && mayEdit());
    }

    /** A dragged stack is never spread into the other player's pane, nor into a frozen one. */
    @Override public boolean canDragTo(Slot slot) {
        return slot.index >= INVENTORY_START || (slot.index < THEIRS_START && mayEdit());
    }

    // --- keeping the escrow and the slots the same -------------------------------------------

    @Override public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player user) {
        // The other player's pane cannot be clicked into, with any kind of click.
        if (slotIndex >= THEIRS_START && slotIndex < INVENTORY_START) return;
        if (trade == null) {
            super.clicked(slotIndex, buttonNum, containerInput, user);
            return;
        }
        // A change no click accounted for is recorded before this click is judged.
        trade.settle(side);
        try {
            super.clicked(slotIndex, buttonNum, containerInput, user);
        } finally {
            trade.commit(side);
        }
    }

    @Override public void broadcastChanges() {
        if (trade != null) trade.settle(side);
        super.broadcastChanges();
    }

    /** Closing the window, by the player or by the game, ends the trade and returns everything. */
    @Override public void removed(Player user) {
        super.removed(user);
        if (trade != null) trade.windowClosed(side);
    }

    @Override public boolean stillValid(Player user) {
        if (trade == null) return true;
        return user == player && !trade.session().over();
    }

    // --- intents -----------------------------------------------------------------------------

    /** What the screen's controls send; the shared intent guard has already passed. */
    public void handle(ServerPlayer sender, DirectTradePayload intent) {
        if (trade == null || sender != player || intent == null || intent.action() == null) return;
        switch (intent.action()) {
            case SET_CREDITS -> trade.setCredits(side, intent.value(), intent.requestId());
            case CONFIRM -> trade.confirm(side, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, intent.value())),
                    intent.requestId());
            case WITHDRAW -> trade.withdraw(side);
        }
    }

    /** A "you give" slot: this player's, while the trade is open for changes. */
    private final class GiveSlot extends Slot {
        GiveSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return accepts(stack); }
        @Override public boolean mayPickup(Player user) { return mayEdit(); }
    }

    /** A "they give" slot: shown, and nothing else. */
    private static final class ShownSlot extends Slot {
        ShownSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player user) { return false; }
    }

    /** What one player is shown of the trade, read from the session every time a slot is synced. */
    private final class TradeData implements ContainerData {
        private final TickCache<Long> balance = new TickCache<>();

        @Override public int get(int index) {
            TradeSession session = trade.session();
            if (index == PHASE_SLOT) return session.over() ? PHASE_OVER
                    : session.phase() == TradeSession.Phase.LOCKED ? PHASE_LOCKED : PHASE_OPEN;
            if (index == MINE_SLOT) return session.confirmed(side) ? 1 : 0;
            if (index == THEIRS_SLOT) return session.confirmed(side.other()) ? 1 : 0;
            if (index == LOCK_SLOT) return trade.lockTenths();
            if (index == TAX_SLOT) return Fees.basisPoints(EconomyConfig.TRADE_TAX_PERCENT.get());
            if (index < MY_CREDITS_SLOT) return Packed64.get(session.revision(), index - REVISION_SLOT);
            if (index < THEIR_CREDITS_SLOT) return Packed64.get(session.credits(side), index - MY_CREDITS_SLOT);
            if (index < BALANCE_SLOT) return Packed64.get(session.credits(side.other()), index - THEIR_CREDITS_SLOT);
            if (index < DATA_COUNT) return Packed64.get(balance.get(player.level().getServer().getTickCount(),
                    () -> CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS)), index - BALANCE_SLOT);
            return 0;
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
