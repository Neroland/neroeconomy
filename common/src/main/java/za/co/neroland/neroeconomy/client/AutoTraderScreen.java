package za.co.neroland.neroeconomy.client;

import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.AutoTraderMenu;
import za.co.neroland.neroeconomy.network.AutoTraderPayload;
import za.co.neroland.neroeconomy.network.AutoTraderStatePayload;
import za.co.neroland.neroeconomy.network.ClientAutoTrader;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.rules.RuleStatus;

/**
 * The owner's screen of an Auto-Trader: its input and output slots on the left with the rule's
 * progress under them, and the rule editor on the right. The rule shown is the server's; the
 * editor only sends intents, and a rule is applied in two presses — the first restates what will
 * be held in escrow, the second sends it.
 *
 * <p>Layout (panel-relative, panel 256x234, mirrored by {@code tools/gen_gui.py}):</p>
 * <pre>
 *   0..14     header: title at (8, 5), your balance right-aligned at 248
 *   17        captions: "Input" at 8, "Output" at 70
 *   27..80    two panes of three by three slots: inputs from (8, 27), outputs from (70, 27)
 *   84..124   progress well: status (11, 87), today (11, 97), in total (11, 107), value cap (11, 115)
 *   16..126   editor: Buy/Sell (134, 17, 56x14) | Item/Tag (192, 17, 56x14)
 *             item or tag id (134, 34, 114x14), what it names at (134, 51)
 *             price each (196, 62) | per day (196, 78) | budget or quantity (196, 94), 52x14 each
 *             Apply (134, 110, 36x14) | Pause (172, 110, 38x14) | Delete (212, 110, 36x14)
 *   128..149  confirm well: two lines at (11, 130) and (11, 140)
 *   153/171/189 inventory rows from x 47, 211 hotbar
 * </pre>
 */
public final class AutoTraderScreen extends TexturedContainerScreen<AutoTraderMenu> {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/auto_trader.png");
    private static final int PANEL_W = 256;
    private static final int PANEL_H = 234;
    private static final int RIGHT = 248;
    private static final int CAPTION_Y = 17;
    private static final int PROGRESS_X = 11;
    private static final int PROGRESS_Y = 87;
    private static final int PROGRESS_W = 114;
    private static final int LINE_H = 10;
    private static final int EDITOR_X = 134;
    private static final int EDITOR_W = 114;
    private static final int FIELD_X = 196;
    private static final int FIELD_W = 52;
    private static final int FIELD_H = 14;
    private static final int CONFIRM_Y = 130;
    private static final int CONFIRM_W = 234;
    private static final int GOOD = 0xFF96EC78;
    private static final int WARNING = 0xFFFFD166;

    private EditBox target;
    private EditBox price;
    private EditBox perDay;
    private EditBox total;
    private Button sideButton;
    private Button kindButton;
    private Button applyButton;
    private Button pauseButton;
    private Button deleteButton;
    /** What the editor is set to. It starts as a sell rule for an item, and takes the server's rule once. */
    private boolean buy;
    private boolean tag;
    /** The server's word on the rule; none until the first state arrives. */
    private AutoTraderStatePayload state = AutoTraderStatePayload.none(0, 0L, 0L);
    private boolean stateSeen;
    /** Apply has been pressed once for exactly this rule text; the next press sends it. */
    private String armedFor = "";

    public AutoTraderScreen(AutoTraderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, PANEL_W, PANEL_H);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
    }

    @Override
    protected void init() {
        super.init();
        String typedTarget = value(this.target, "");
        String typedPrice = value(this.price, "");
        String typedPerDay = value(this.perDay, "");
        String typedTotal = value(this.total, "");
        this.sideButton = addRenderableWidget(Button.builder(Component.empty(), ignored -> {
            this.buy = !this.buy;
            labelToggles();
        }).bounds(this.leftPos + EDITOR_X, this.topPos + 17, 56, FIELD_H)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.auto_trader.side.tooltip"))).build());
        this.kindButton = addRenderableWidget(Button.builder(Component.empty(), ignored -> {
            this.tag = !this.tag;
            labelToggles();
        }).bounds(this.leftPos + 192, this.topPos + 17, 56, FIELD_H)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.auto_trader.kind.tooltip"))).build());
        this.target = box(EDITOR_X, 34, EDITOR_W, AutoTraderPayload.MAX_TARGET_LENGTH, typedTarget,
                "gui.neroeconomy.auto_trader.target");
        this.price = box(FIELD_X, 62, FIELD_W, 13, typedPrice, "gui.neroeconomy.auto_trader.price");
        this.perDay = box(FIELD_X, 78, FIELD_W, 4, typedPerDay, "gui.neroeconomy.auto_trader.per_day");
        this.total = box(FIELD_X, 94, FIELD_W, 13, typedTotal, "gui.neroeconomy.auto_trader.total");
        this.applyButton = addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.auto_trader.apply"),
                ignored -> apply()).bounds(this.leftPos + EDITOR_X, this.topPos + 110, 36, FIELD_H).build());
        this.pauseButton = addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.auto_trader.pause"),
                ignored -> send(this.state.paused() ? AutoTraderPayload.Action.RESUME : AutoTraderPayload.Action.PAUSE))
                .bounds(this.leftPos + 172, this.topPos + 110, 38, FIELD_H)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.auto_trader.pause.tooltip"))).build());
        this.deleteButton = addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.auto_trader.delete"),
                ignored -> send(AutoTraderPayload.Action.DELETE))
                .bounds(this.leftPos + 212, this.topPos + 110, 36, FIELD_H)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.auto_trader.delete.tooltip"))).build());
        labelToggles();
    }

    private static String value(EditBox box, String fallback) { return box == null ? fallback : box.getValue(); }

    private EditBox box(int x, int y, int width, int maxLength, String value, String key) {
        EditBox box = new EditBox(this.font, this.leftPos + x, this.topPos + y, width, FIELD_H, Component.translatable(key));
        box.setMaxLength(maxLength);
        box.setValue(value);
        box.setHint(Component.translatable(key + ".hint"));
        box.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
        return addRenderableWidget(box);
    }

    private void labelToggles() {
        if (this.sideButton != null) this.sideButton.setMessage(Component.translatable(this.buy
                ? "gui.neroeconomy.auto_trader.side.buy" : "gui.neroeconomy.auto_trader.side.sell"));
        if (this.kindButton != null) this.kindButton.setMessage(Component.translatable(this.tag
                ? "gui.neroeconomy.auto_trader.kind.tag" : "gui.neroeconomy.auto_trader.kind.item"));
    }

    // --- what was typed ----------------------------------------------------------------------

    /** The id typed, without the {@code #} a tag may be written with. */
    private String typedTarget() {
        String text = value(this.target, "").trim();
        return this.tag && text.startsWith("#") ? text.substring(1) : text;
    }

    /** A whole number of at least 1, or -1 when the box holds anything else. */
    private static long number(EditBox box) {
        try {
            long parsed = Long.parseLong(value(box, "").trim());
            return parsed < 1L ? -1L : parsed;
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    /** The item an id names on this client, or null. */
    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        Item found = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return found == null || found == Items.AIR ? null : found;
    }

    private static boolean tagKnown(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, key))
                .map(named -> named.size() > 0).orElse(false);
    }

    private boolean targetValid() {
        String id = typedTarget();
        return !id.isEmpty() && (this.tag ? tagKnown(id) : item(id) != null);
    }

    private boolean ruleValid() {
        return targetValid() && number(this.price) > 0L && number(this.perDay) > 0L
                && number(this.perDay) <= Integer.MAX_VALUE && number(this.total) > 0L;
    }

    /** Everything that makes the typed rule what it is; a confirm is only good for exactly this. */
    private String ruleText() {
        return this.buy + "|" + this.tag + "|" + typedTarget() + "|" + number(this.price) + "|" + number(this.perDay)
                + "|" + number(this.total);
    }

    // --- intents -----------------------------------------------------------------------------

    private void apply() {
        if (!ruleValid()) return;
        String text = ruleText();
        if (!text.equals(this.armedFor)) {
            // First press: nothing is sent. The confirm lines now restate what will be escrowed.
            this.armedFor = text;
            return;
        }
        this.armedFor = "";
        NeroEconomyNetwork.sendToServer(new AutoTraderPayload(this.menu.containerId, UUID.randomUUID(),
                AutoTraderPayload.Action.APPLY, this.buy, this.tag, typedTarget(), number(this.price),
                (int) number(this.perDay), number(this.total)));
    }

    private void send(AutoTraderPayload.Action action) {
        this.armedFor = "";
        NeroEconomyNetwork.sendToServer(AutoTraderPayload.of(this.menu.containerId, UUID.randomUUID(), action));
    }

    // --- the server's rule -------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        AutoTraderStatePayload arrived = ClientAutoTrader.poll(this.menu.containerId);
        if (arrived != null) {
            this.state = arrived;
            // The first state fills the editor with the rule as it is, so it can be read and adjusted.
            if (!this.stateSeen && arrived.hasRule()) showInEditor(arrived);
            this.stateSeen = true;
        }
        boolean hasRule = this.state.hasRule();
        boolean valid = ruleValid();
        boolean armed = valid && ruleText().equals(this.armedFor);
        // Any change to what was typed takes the first press back.
        if (!armed) this.armedFor = "";
        if (this.applyButton != null) {
            this.applyButton.active = valid;
            this.applyButton.setMessage(Component.translatable(armed ? "gui.neroeconomy.auto_trader.apply.confirm"
                    : "gui.neroeconomy.auto_trader.apply"));
            this.applyButton.setTooltip(Tooltip.create(Component.translatable(this.buy
                    ? "gui.neroeconomy.auto_trader.apply.tooltip.buy" : "gui.neroeconomy.auto_trader.apply.tooltip.sell")));
        }
        if (this.pauseButton != null) {
            this.pauseButton.active = hasRule;
            this.pauseButton.setMessage(Component.translatable(this.state.paused() ? "gui.neroeconomy.auto_trader.resume"
                    : "gui.neroeconomy.auto_trader.pause"));
        }
        if (this.deleteButton != null) this.deleteButton.active = hasRule;
    }

    private void showInEditor(AutoTraderStatePayload rule) {
        this.buy = rule.buys();
        this.tag = rule.tag();
        if (this.target != null) this.target.setValue(rule.target());
        if (this.price != null) this.price.setValue(Long.toString(rule.limitPrice()));
        if (this.perDay != null) this.perDay.setValue(Integer.toString(rule.perDay()));
        if (this.total != null) this.total.setValue(Long.toString(rule.total()));
        labelToggles();
    }

    @Override
    public void removed() {
        super.removed();
        ClientAutoTrader.clear();
    }

    // --- drawing -----------------------------------------------------------------------------

    /** What the typed id names, or why it names nothing. */
    private Component feedback() {
        String id = typedTarget();
        if (id.isEmpty()) return Component.translatable(this.tag ? "gui.neroeconomy.auto_trader.target.empty_tag"
                : "gui.neroeconomy.auto_trader.target.empty_item");
        if (this.tag) return Component.translatable(tagKnown(id) ? "gui.neroeconomy.auto_trader.target.tag_ok"
                : "gui.neroeconomy.auto_trader.target.tag_unknown");
        Item found = item(id);
        // The item's own name, never a stack's custom one.
        return found == null ? Component.translatable("gui.neroeconomy.auto_trader.target.item_unknown")
                : Component.translatable("gui.neroeconomy.auto_trader.target.item_ok", new ItemStack(found).getItemName());
    }

    private Component status() {
        if (!this.state.hasRule()) return Component.translatable("gui.neroeconomy.auto_trader.no_rule");
        RuleStatus[] all = RuleStatus.values();
        int index = this.state.status();
        return Component.translatable(index >= 0 && index < all.length ? all[index].translationKey()
                : RuleStatus.RUNNING.translationKey());
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        labelRight(g, Component.translatable("gui.neroeconomy.wallet", this.menu.balance()), RIGHT, this.titleLabelY, CREDIT);
        label(g, Component.translatable("gui.neroeconomy.auto_trader.inputs"), AutoTraderMenu.INPUT_X, CAPTION_Y, SUBTLE);
        label(g, Component.translatable("gui.neroeconomy.auto_trader.outputs"), AutoTraderMenu.OUTPUT_X, CAPTION_Y, SUBTLE);

        // The rule's one-line status, what it has done today and in total, and the owner's daily cap.
        boolean hasRule = this.state.hasRule();
        label(g, trim(status(), PROGRESS_W), PROGRESS_X, PROGRESS_Y, hasRule ? TITLE : SUBTLE);
        if (hasRule) {
            label(g, trim(Component.translatable("gui.neroeconomy.auto_trader.today", this.state.filledToday(),
                    this.state.perDay()), PROGRESS_W), PROGRESS_X, PROGRESS_Y + LINE_H, TITLE);
            label(g, trim(Component.translatable(this.state.buys() ? "gui.neroeconomy.auto_trader.spent"
                    : "gui.neroeconomy.auto_trader.sold", this.state.done(), this.state.total()), PROGRESS_W),
                    PROGRESS_X, PROGRESS_Y + 2 * LINE_H, TITLE);
        }
        label(g, trim(Component.translatable("gui.neroeconomy.auto_trader.value", this.state.valueToday(),
                this.state.valueCap()), PROGRESS_W), PROGRESS_X, PROGRESS_Y + 3 * LINE_H - 2, SUBTLE);

        boolean valid = targetValid();
        label(g, trim(feedback(), EDITOR_W), EDITOR_X, 51, valid ? GOOD : WARNING);
        label(g, trim(Component.translatable("gui.neroeconomy.auto_trader.price"), FIELD_X - EDITOR_X - 3), EDITOR_X, 65, SUBTLE);
        label(g, trim(Component.translatable("gui.neroeconomy.auto_trader.per_day"), FIELD_X - EDITOR_X - 3), EDITOR_X, 81, SUBTLE);
        label(g, trim(Component.translatable(this.buy ? "gui.neroeconomy.auto_trader.budget"
                : "gui.neroeconomy.auto_trader.total"), FIELD_X - EDITOR_X - 3), EDITOR_X, 97, SUBTLE);

        // The confirm step: once Apply has been pressed, exactly what the second press will escrow.
        boolean armed = ruleValid() && ruleText().equals(this.armedFor);
        String lines = armed ? (this.buy ? "gui.neroeconomy.auto_trader.confirm.buy" : "gui.neroeconomy.auto_trader.confirm.sell")
                : "gui.neroeconomy.auto_trader.hint";
        label(g, trim(Component.translatable(lines + ".1", number(this.total), number(this.perDay)), CONFIRM_W),
                PROGRESS_X, CONFIRM_Y, armed ? WARNING : SUBTLE);
        label(g, trim(Component.translatable(lines + ".2", number(this.total), number(this.perDay)), CONFIRM_W),
                PROGRESS_X, CONFIRM_Y + LINE_H, armed ? WARNING : SUBTLE);
    }

    /** The inventory sits in the middle of a wide panel and needs no caption of its own. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(this.font, this.title, this.titleLabelX, this.titleLabelY, TITLE, false);
    }
}
