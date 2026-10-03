package za.co.neroland.neroeconomy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.DirectTradeMenu;
import za.co.neroland.neroeconomy.network.DirectTradePayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;

/**
 * The direct trade window: what you give on the left, what the other player gives on the right,
 * who has confirmed in between, and your inventory below. Everything shown is what the server
 * sent; the client only draws it and sends intents.
 *
 * <p>Layout (panel-relative, panel 176x224, mirrored by {@code tools/gen_gui.py}):</p>
 * <pre>
 *   0..14     header: title "Trade with NAME" at (8, 5), your balance right-aligned at 168
 *   17        captions: "You give" at 8, "They give" at 116
 *   27..80    two panes of three by three slots: yours from (8, 27), theirs from (116, 27)
 *   26..81    between them (64..112): two confirmation lamps and, while frozen, the countdown
 *   84..98    credits you give: field (8, 84, 52x14) | Set (62, 84, 26x14); theirs right-aligned at 168
 *   100..121  summary well: "You give ..." at (11, 102), "You get ..." at (11, 112)
 *   123..139  Confirm (8, 123, 78x16) | Cancel (90, 123, 78x16)
 *   143/161/179 inventory rows, 201 hotbar
 * </pre>
 */
public final class DirectTradeScreen extends TexturedContainerScreen<DirectTradeMenu> {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/direct_trade.png");
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 224;
    private static final int RIGHT = 168;
    private static final int CAPTION_Y = 17;
    private static final int MIDDLE_X = 66;
    private static final int MIDDLE_W = 45;
    private static final int LAMP = 7;
    private static final int CREDITS_Y = 84;
    private static final int SUMMARY_X = 11;
    private static final int SUMMARY_Y = 102;
    private static final int SUMMARY_W = 154;
    private static final int LINE_H = 10;
    private static final int ACTION_Y = 123;
    private static final int ACTION_W = 78;
    private static final int ACTION_H = 16;
    /** A lamp that is off, and the colour of a warning. */
    private static final int LAMP_OFF = 0xFF24303E;
    private static final int WARNING = 0xFFFFD166;

    private EditBox credits;
    private Button setButton;
    private Button confirmButton;
    /** The confirm button's tooltip is rebuilt only when what it restates changes. */
    private String restated = "";

    public DirectTradeScreen(DirectTradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, PANEL_W, PANEL_H);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
    }

    @Override
    protected void init() {
        super.init();
        String typed = this.credits == null ? "" : this.credits.getValue();
        this.credits = new EditBox(this.font, this.leftPos + 8, this.topPos + CREDITS_Y, 52, 14,
                Component.translatable("gui.neroeconomy.direct_trade.credits"));
        this.credits.setMaxLength(13);
        this.credits.setValue(typed);
        this.credits.setHint(Component.translatable("gui.neroeconomy.direct_trade.credits_hint"));
        this.credits.setTooltip(Tooltip.create(Component.translatable("gui.neroeconomy.direct_trade.credits.tooltip")));
        addRenderableWidget(this.credits);
        this.setButton = addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.direct_trade.set"),
                        ignored -> sendCredits())
                .bounds(this.leftPos + 62, this.topPos + CREDITS_Y, 26, 14)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.direct_trade.set.tooltip"))).build());
        this.confirmButton = addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.direct_trade.confirm"),
                        ignored -> confirm())
                .bounds(this.leftPos + 8, this.topPos + ACTION_Y, ACTION_W, ACTION_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.direct_trade.cancel"),
                        ignored -> onClose())
                .bounds(this.leftPos + 90, this.topPos + ACTION_Y, ACTION_W, ACTION_H)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.direct_trade.cancel.tooltip"))).build());
        this.restated = "";
    }

    /** The amount typed, or -1 when it is not a whole number of credits. Nothing typed is nothing given. */
    private long typedCredits() {
        String text = this.credits == null ? "" : this.credits.getValue().trim();
        if (text.isEmpty()) return 0L;
        try {
            long value = Long.parseLong(text);
            return value < 0L ? -1L : value;
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    private void sendCredits() {
        long amount = typedCredits();
        // The server is authoritative; an amount that is not a number simply sends nothing.
        if (amount < 0L) return;
        NeroEconomyNetwork.sendToServer(new DirectTradePayload(this.menu.containerId, UUID.randomUUID(),
                DirectTradePayload.Action.SET_CREDITS, amount));
    }

    private void confirm() {
        boolean takeBack = this.menu.confirmedByMe();
        // A confirmation names the revision on screen: if the offer changed on the way, it is ignored.
        NeroEconomyNetwork.sendToServer(new DirectTradePayload(this.menu.containerId, UUID.randomUUID(),
                takeBack ? DirectTradePayload.Action.WITHDRAW : DirectTradePayload.Action.CONFIRM,
                takeBack ? 0L : this.menu.revision()));
    }

    /** What the player gives, in words: items and credits. */
    private Component giving() {
        return Component.translatable("gui.neroeconomy.direct_trade.give", this.menu.itemCount(true), this.menu.myCredits());
    }

    /** What the player receives, in words: items, and credits after the trade tax. */
    private Component getting() {
        long theirs = this.menu.theirCredits();
        long tax = this.menu.tax(theirs);
        return tax > 0L ? Component.translatable("gui.neroeconomy.direct_trade.get_taxed", this.menu.itemCount(false),
                        theirs - tax, tax)
                : Component.translatable("gui.neroeconomy.direct_trade.get", this.menu.itemCount(false), theirs);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean editable = this.menu.editable();
        boolean mine = this.menu.confirmedByMe();
        if (this.credits != null) this.credits.setEditable(editable);
        if (this.setButton != null) this.setButton.active = editable && typedCredits() >= 0L;
        if (this.confirmButton == null) return;
        this.confirmButton.active = editable;
        this.confirmButton.setMessage(Component.translatable(mine ? "gui.neroeconomy.direct_trade.unconfirm"
                : "gui.neroeconomy.direct_trade.confirm"));
        // The confirm step restates exactly what will be given and received.
        Component tip = mine ? Component.translatable("gui.neroeconomy.direct_trade.unconfirm.tooltip")
                : Component.translatable("gui.neroeconomy.direct_trade.confirm.tooltip", giving(), getting());
        String text = tip.getString();
        if (!text.equals(this.restated)) {
            this.restated = text;
            this.confirmButton.setTooltip(Tooltip.create(tip));
        }
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        labelRight(g, Component.translatable("gui.neroeconomy.wallet", this.menu.balance()), RIGHT, this.titleLabelY, CREDIT);
        label(g, Component.translatable("gui.neroeconomy.direct_trade.you_give"), DirectTradeMenu.GIVE_X, CAPTION_Y, SUBTLE);
        label(g, Component.translatable("gui.neroeconomy.direct_trade.they_give"), DirectTradeMenu.THEIRS_X, CAPTION_Y, SUBTLE);

        // Who has confirmed: a lamp each, lit in the economy's green.
        lamp(g, 29, this.menu.confirmedByMe(), "gui.neroeconomy.direct_trade.you");
        lamp(g, 41, this.menu.confirmedByThem(), "gui.neroeconomy.direct_trade.them");
        if (this.menu.locked()) {
            // The contents are frozen; the countdown runs to the swap.
            label(g, trim(Component.translatable("gui.neroeconomy.direct_trade.frozen"), MIDDLE_W), MIDDLE_X, 55, WARNING);
            label(g, trim(Component.translatable("gui.neroeconomy.direct_trade.countdown",
                    (this.menu.lockTenths() + 9) / 10), MIDDLE_W), MIDDLE_X, 66, WARNING);
        }

        long theirs = this.menu.theirCredits();
        labelRight(g, trim(Component.translatable("gui.neroeconomy.direct_trade.their_credits", theirs), 76), RIGHT,
                CREDITS_Y + 3, theirs > 0L ? CREDIT : SUBTLE);
        label(g, trim(giving(), SUMMARY_W), SUMMARY_X, SUMMARY_Y, TITLE);
        label(g, trim(getting(), SUMMARY_W), SUMMARY_X, SUMMARY_Y + LINE_H, TITLE);
    }

    private void lamp(GuiGraphicsExtractor g, int dy, boolean lit, String key) {
        band(g, MIDDLE_X, dy, LAMP, LAMP, lit ? CREDIT : LAMP_OFF);
        label(g, trim(Component.translatable(key), MIDDLE_W - LAMP - 3), MIDDLE_X + LAMP + 3, dy, lit ? CREDIT : SUBTLE);
    }

    /** The title is the other player's name and can be long: it is cut to leave room for the balance. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int room = RIGHT - this.titleLabelX - 4
                - this.font.width(Component.translatable("gui.neroeconomy.wallet", this.menu.balance()));
        g.text(this.font, trim(this.title, Math.max(40, room)), this.titleLabelX, this.titleLabelY, TITLE, false);
    }

    /**
     * In both panes the tooltip says what the stack really is, whatever it has been named: its
     * item id, its own name when it carries a custom one, how many there are, how many
     * enchantments it has and how worn it is. The game's own tooltip comes first, unchanged.
     */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        Slot hovered = this.hoveredSlot;
        if (hovered == null || hovered.index >= DirectTradeMenu.INVENTORY_START || stack.isEmpty()) return lines;
        lines.add(Component.translatable("gui.neroeconomy.direct_trade.tooltip.id",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).withStyle(ChatFormatting.AQUA));
        if (stack.has(DataComponents.CUSTOM_NAME))
            lines.add(Component.translatable("gui.neroeconomy.direct_trade.tooltip.renamed", stack.getItemName())
                    .withStyle(ChatFormatting.YELLOW));
        lines.add(Component.translatable("gui.neroeconomy.direct_trade.tooltip.count", stack.getCount())
                .withStyle(ChatFormatting.GRAY));
        int enchantments = stack.getEnchantments().size();
        if (enchantments > 0)
            lines.add(Component.translatable("gui.neroeconomy.direct_trade.tooltip.enchantments", enchantments)
                    .withStyle(ChatFormatting.GRAY));
        if (stack.isDamageableItem())
            lines.add(Component.translatable("gui.neroeconomy.direct_trade.tooltip.durability",
                    stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()).withStyle(ChatFormatting.GRAY));
        return lines;
    }
}
