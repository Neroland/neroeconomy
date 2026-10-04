package za.co.neroland.neroeconomy.menu;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.block.AutoTraderBlockEntity;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.network.AutoTraderPayload;
import za.co.neroland.neroeconomy.network.AutoTraderStatePayload;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleResult;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.trader.AutoTraders;

/**
 * The owner's window of an Auto-Trader: its nine input slots, its nine output slots, the
 * player's inventory, and the rule editor. Only the owner ever has this menu open.
 *
 * <p>The slots are the block's own: ordinary container slots, moved by the game's own click
 * handling like a chest's. A stack goes into an input slot only if it may be traded and does not
 * come out of a creative-mode inventory; nothing can be put into an output slot. The rule itself
 * never travels through a slot or a data slot: the server sends it as a state payload when it
 * changes, and the editor's buttons send intents back.
 */
public final class AutoTraderMenu extends AbstractContainerMenu {
    public static final int INPUTS = AutoTraderBlockEntity.INPUTS;
    public static final int SLOTS = AutoTraderBlockEntity.SLOTS;
    /** Top-left of each three-by-three pane and of the inventory; the screen's panel is painted to match. */
    public static final int INPUT_X = 8;
    public static final int OUTPUT_X = 70;
    public static final int PANE_Y = 27;
    public static final int INVENTORY_X = 47;
    public static final int INVENTORY_Y = 153;
    public static final int HOTBAR_Y = 211;
    /** The owner's balance, as four 16-bit data slots (see {@link Packed64}). */
    private static final int DATA_COUNT = Packed64.SLOTS;
    /** The rule's state is looked at again this often, and sent only when it has changed. */
    private static final int STATE_TICKS = 10;

    private final Container container;
    /** The block behind the window; null on the client. */
    private final AutoTraderBlockEntity trader;
    private final Player player;
    private final ContainerData data;
    private AutoTraderStatePayload sent;
    private int ticks;

    /** Client side: a plain container the server's slot updates are written into. */
    public AutoTraderMenu(int id, Inventory inventory) {
        this(id, inventory, null);
    }

    /** Server side: the owner's window of {@code trader}. */
    @SuppressWarnings("this-escape")
    public AutoTraderMenu(int id, Inventory inventory, AutoTraderBlockEntity trader) {
        super(ModMenuTypes.AUTO_TRADER.get(), id);
        this.trader = trader;
        this.player = inventory.player;
        this.container = trader != null ? trader : new SimpleContainer(SLOTS);
        this.data = trader != null && player instanceof ServerPlayer owner ? new BalanceData(owner)
                : new SimpleContainerData(DATA_COUNT);
        checkContainerSize(container, SLOTS);
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            addSlot(new InputSlot(container, col + row * 3, INPUT_X + col * 18, PANE_Y + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            addSlot(new OutputSlot(container, INPUTS + col + row * 3, OUTPUT_X + col * 18, PANE_Y + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
    }

    public long balance() { return Packed64.read(data::get, 0); }

    // --- the slots ---------------------------------------------------------------------------

    /** Whether the player may put this stack into an input slot by hand. */
    private boolean accepts(int slot, ItemStack stack) {
        // Goods never enter the economy from a creative-mode or spectator inventory.
        return container.canPlaceItem(slot, stack) && GoodsSource.allowed(player);
    }

    @Override public ItemStack quickMoveStack(Player user, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < SLOTS) {
            // Out of the block: into the inventory from the hotbar end, as a chest does.
            if (!moveItemStackTo(stack, SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Into the inputs: the merge below does not ask the slots, so the rules are asked here.
            if (!accepts(0, stack) || !moveItemStackTo(stack, 0, INPUTS, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override public boolean stillValid(Player user) {
        if (trader == null) return true;
        // Only the owner, standing at the block; an Auto-Trader that loses its owner closes at once.
        return user == player && IntentGuard.anchored(user, trader) && trader.isOwner(user.getUUID());
    }

    // --- the rule ----------------------------------------------------------------------------

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (trader != null && (sent == null || ++ticks >= STATE_TICKS)) pushState();
    }

    /** Sends the rule's state to the owner if it differs from what they were last sent. */
    private void pushState() {
        ticks = 0;
        if (!(player instanceof ServerPlayer owner) || !(trader.getLevel() instanceof ServerLevel level)) return;
        AutoTraderStatePayload state = state(owner, level);
        if (state.equals(sent)) return;
        sent = state;
        NeroEconomyNetwork.sendToPlayer(owner, state);
    }

    private AutoTraderStatePayload state(ServerPlayer owner, ServerLevel level) {
        long cap = EconomyConfig.RULE_DAILY_VALUE_CAP.get();
        if (EconomyHealth.frozen()) return AutoTraderStatePayload.none(containerId, 0L, cap);
        long today = EconomyStore.get(level.getServer()).rules().valueToday(new PlayerAccountId(owner.getUUID()),
                NeroEconomyCommon.CURRENCY_PROVIDER.now());
        TradeRule rule = AutoTraders.rule(level, trader.getBlockPos(), owner.getUUID());
        if (rule == null) return AutoTraderStatePayload.none(containerId, today, cap);
        return new AutoTraderStatePayload(containerId, true, rule.buys(), rule.tag(), rule.target(), rule.limitPrice(),
                rule.perDay(), rule.total(), rule.done(), rule.filledToday(), rule.status().ordinal(), rule.paused(),
                rule.budget(), today, cap);
    }

    /** What the rule editor's buttons send; the shared intent guard has already passed. */
    public void handle(ServerPlayer sender, AutoTraderPayload intent) {
        if (trader == null || sender != player || intent == null || intent.action() == null
                || !(trader.getLevel() instanceof ServerLevel level)) return;
        switch (intent.action()) {
            case APPLY -> apply(sender, level, intent);
            case PAUSE, RESUME -> {
                boolean pause = intent.action() == AutoTraderPayload.Action.PAUSE;
                RuleOutcome outcome = AutoTraders.setPaused(sender, trader, pause);
                if (outcome.ok()) say(sender, pause ? "command.neroeconomy.rule.paused" : "command.neroeconomy.rule.resumed");
                else refuse(sender, outcome.result());
            }
            case DELETE -> {
                RuleOutcome outcome = AutoTraders.delete(sender, trader);
                if (outcome.ok()) say(sender, outcome.refundedToClaim() ? "message.neroeconomy.auto_trader.deleted_claim"
                        : "message.neroeconomy.auto_trader.deleted", outcome.refunded());
                else refuse(sender, outcome.result());
            }
        }
        pushState();
    }

    private void apply(ServerPlayer sender, ServerLevel level, AutoTraderPayload intent) {
        // A sell rule puts whatever reaches the input slots on the market. Somebody with an endless
        // supply of items may not set one up; buying is never refused, it adds nothing of value.
        if (!intent.buy() && GoodsSource.refused(sender)) return;
        boolean hadRule = AutoTraders.rule(level, trader.getBlockPos(), sender.getUUID()) != null;
        // A tag is typed with or without its leading '#'; the rule stores it without.
        String target = intent.target() == null ? "" : intent.target().trim();
        if (intent.tag() && target.startsWith("#")) target = target.substring(1);
        RuleOutcome outcome = AutoTraders.apply(sender, trader, intent.buy() ? RuleSide.BUY : RuleSide.SELL, intent.tag(),
                target, intent.limitPrice(), intent.perDay(), intent.total(), intent.requestId() == null
                        ? UUID.randomUUID() : intent.requestId());
        // A replay of an intent that was already acted on: answered by the first one.
        if (outcome.result() == RuleResult.STALE_REQUEST) return;
        if (!outcome.ok()) {
            refuse(sender, outcome.result());
            // The rule it had is gone only if it was returned in full before the new one was refused.
            if (hadRule && AutoTraders.rule(level, trader.getBlockPos(), sender.getUUID()) == null)
                say(sender, "message.neroeconomy.auto_trader.previous_returned");
            return;
        }
        TradeRule rule = outcome.rule();
        Component what = rule.tag() ? Component.literal("#" + rule.target()) : TradeMessages.itemName(rule.target());
        // Restated exactly: what was escrowed for a buy rule, and that nothing was for a sell rule.
        say(sender, rule.buys() ? "message.neroeconomy.auto_trader.applied_buy" : "message.neroeconomy.auto_trader.applied_sell",
                rule.perDay(), what, rule.limitPrice(), rule.total());
    }

    private static void say(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable(key, args));
    }

    private static void refuse(ServerPlayer player, RuleResult result) {
        player.sendSystemMessage(Component.translatable("command.neroeconomy.rule.refused",
                Component.translatable(result.translationKey())));
    }

    /** An input slot: takes what may be traded, from an inventory that may supply it. */
    private final class InputSlot extends Slot {
        InputSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return accepts(getContainerSlot(), stack); }
    }

    /** An output slot: what the rule bought. It can be emptied and never filled by hand. */
    private static final class OutputSlot extends Slot {
        OutputSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
    }

    /** The owner's balance, looked up once per tick however many of its four slots are read. */
    private static final class BalanceData implements ContainerData {
        private final ServerPlayer player;
        private final TickCache<Long> balance = new TickCache<>();
        BalanceData(ServerPlayer player) { this.player = player; }
        @Override public int get(int index) {
            long value = balance.get(player.level().getServer().getTickCount(),
                    () -> CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS));
            return Packed64.get(value, index);
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
