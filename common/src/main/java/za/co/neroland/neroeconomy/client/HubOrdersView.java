package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubPages;
import za.co.neroland.neroeconomy.hub.RuleForm;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.text.TimeText;

/**
 * The My orders tab: the player's own sell orders, buy orders, shop listings and auto-trade rules,
 * with cancel, pause, resume, delete and "pause all", and a form for a new rule. Cancelling and
 * deleting take two presses, and the second restates what comes back.
 *
 * <pre>
 *   list mode   20 rules and today's traded value; 32: six rows of 20px; 156 previous / next, page text
 *               174 Cancel or Pause (8, 60x14) | Delete (72, 60x14) | Pause all (136, 56x14) | New rule (196, 52x14)
 *               192 / 202 what the selected row is, or the confirm line
 *   form mode   20 Buy/Sell (8, 20, 60x14) | Item/Tag (72, 20, 60x14); 38 item or tag id (8, 38, 180x14)
 *               56 what the id names; 70 / 88 / 106 price each, per day, budget or quantity (120, 80x14)
 *               126 Create (8, 126, 80x14) | Back (92, 126, 60x14); 146 / 156 the confirm lines
 * </pre>
 */
final class HubOrdersView extends HubTabView {
    private boolean form;
    private boolean buy = true;
    private boolean tag;
    private EditBox target;
    private EditBox limit;
    private EditBox perDay;
    private EditBox total;
    private Button primary;
    private Button delete;
    private Button pauseAll;
    private Button create;

    HubOrdersView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listY = 32;
        this.listW = 240;
        this.rowH = 20;
        this.visibleRows = HubPages.ORDERS_ROWS;
    }

    @Override
    void init() {
        if (this.form) {
            initForm();
            return;
        }
        pageButtons(8, 156);
        this.primary = this.screen.button(Component.empty(), "gui.neroeconomy.hub.orders.primary.tooltip", 8, 174, 60,
                14, this::primary);
        this.delete = this.screen.button(Component.translatable("gui.neroeconomy.hub.orders.delete"),
                "gui.neroeconomy.hub.orders.delete.tooltip", 72, 174, 60, 14, this::delete);
        this.pauseAll = this.screen.button(Component.empty(), "gui.neroeconomy.hub.orders.pause_all.tooltip", 136, 174,
                56, 14, () -> this.intents.send(HubAction.RULE_PAUSE_ALL, null, header(HubPages.PAUSED_ALL) == 1L ? 0L : 1L,
                        0L, ""));
        this.screen.button(Component.translatable("gui.neroeconomy.hub.orders.new_rule"),
                "gui.neroeconomy.hub.orders.new_rule.tooltip", 196, 174, 52, 14, () -> {
                    this.form = true;
                    disarm();
                    this.screen.rebuild();
                });
    }

    private void initForm() {
        this.screen.button(Component.translatable(this.buy ? "gui.neroeconomy.hub.orders.form.buy"
                : "gui.neroeconomy.hub.orders.form.sell"), "gui.neroeconomy.hub.orders.form.side.tooltip", 8, 20, 60, 14,
                () -> {
                    this.buy = !this.buy;
                    this.screen.rebuild();
                });
        this.screen.button(Component.translatable(this.tag ? "gui.neroeconomy.hub.orders.form.tag"
                : "gui.neroeconomy.hub.orders.form.item"), "gui.neroeconomy.hub.orders.form.kind.tooltip", 72, 20, 60, 14,
                () -> {
                    this.tag = !this.tag;
                    this.screen.rebuild();
                });
        this.target = this.screen.box(this.target, "gui.neroeconomy.hub.orders.form.target", 8, 38, 180, RuleForm.MAX_TARGET);
        this.limit = this.screen.box(this.limit, "gui.neroeconomy.hub.orders.form.limit", 120, 70, 80, 13);
        this.perDay = this.screen.box(this.perDay, "gui.neroeconomy.hub.orders.form.per_day", 120, 88, 80, 4);
        this.total = this.screen.box(this.total, "gui.neroeconomy.hub.orders.form.total", 120, 106, 80, 13);
        this.create = this.screen.button(Component.empty(), "gui.neroeconomy.hub.orders.form.create.tooltip", 8, 126,
                80, 14, this::create);
        this.screen.button(Component.translatable("gui.neroeconomy.hub.back"), "gui.neroeconomy.hub.orders.form.back.tooltip",
                92, 126, 60, 14, () -> {
                    this.form = false;
                    disarm();
                    this.screen.rebuild();
                });
    }

    // --- the list ----------------------------------------------------------------------------

    private boolean isRule(HubPage.Row row) { return row != null && row.flag(HubPage.FLAG_A); }

    private boolean cancellable(HubPage.Row row) { return row != null && row.flag(HubPage.FLAG_D); }

    private void primary() {
        HubPage.Row row = selectedRow();
        if (row == null) return;
        if (isRule(row)) {
            this.intents.send(HubAction.RULE_PAUSE, row.id(), row.flag(HubPage.FLAG_B) ? 0L : 1L, 0L, "");
        } else if (cancellable(row)) {
            press("cancel|" + row.id(), () -> this.intents.send(HubAction.ORDER_CANCEL, row.id(), 0L, 0L, ""));
        }
    }

    private void delete() {
        HubPage.Row row = selectedRow();
        if (row == null || !isRule(row)) return;
        press("delete|" + row.id(), () -> this.intents.send(HubAction.RULE_DELETE, row.id(), 0L, 0L, ""));
    }

    // --- the form ----------------------------------------------------------------------------

    private String typedTarget() {
        String text = value(this.target);
        return text.startsWith("#") ? text.substring(1) : text;
    }

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

    private boolean formValid() {
        String id = typedTarget();
        return !id.isEmpty() && (this.tag ? tagKnown(id) : item(id) != null) && number(this.limit) > 0L
                && number(this.perDay) > 0L && number(this.perDay) <= Integer.MAX_VALUE && number(this.total) > 0L;
    }

    private RuleForm formValue() {
        return new RuleForm(this.buy ? RuleSide.BUY : RuleSide.SELL, this.tag, (int) number(this.perDay), typedTarget());
    }

    private String formText() { return formValue().encode() + "|" + number(this.limit) + "|" + number(this.total); }

    private void create() {
        if (!formValid()) return;
        RuleForm rule = formValue();
        press(formText(), () -> {
            this.intents.send(HubAction.RULE_ADD, null, number(this.limit), number(this.total), rule.encode());
            this.form = false;
            this.screen.rebuild();
        });
    }

    @Override
    void tick() {
        if (this.form) {
            if (!this.armedFor.isEmpty() && !(formValid() && armed(formText()))) disarm();
            if (this.create != null) {
                this.create.active = formValid();
                label(this.create, "gui.neroeconomy.hub.orders.form.create", formValid() && armed(formText()));
            }
            return;
        }
        HubPage.Row row = selectedRow();
        if (!this.armedFor.isEmpty() && (row == null || !this.armedFor.endsWith(row.id().toString()))) disarm();
        if (this.primary != null) {
            this.primary.active = isRule(row) || cancellable(row);
            if (row != null && isRule(row)) this.primary.setMessage(Component.translatable(row.flag(HubPage.FLAG_B)
                    ? "gui.neroeconomy.hub.orders.resume" : "gui.neroeconomy.hub.orders.pause"));
            else label(this.primary, "gui.neroeconomy.hub.orders.cancel", row != null && armed("cancel|" + row.id()));
        }
        if (this.delete != null) {
            this.delete.active = isRule(row);
            label(this.delete, "gui.neroeconomy.hub.orders.delete", row != null && armed("delete|" + row.id()));
        }
        if (this.pauseAll != null) {
            this.pauseAll.active = header(HubPages.RULES) > 0L;
            this.pauseAll.setMessage(Component.translatable(header(HubPages.PAUSED_ALL) == 1L
                    ? "gui.neroeconomy.hub.orders.resume_all" : "gui.neroeconomy.hub.orders.pause_all"));
        }
    }

    // --- drawing -----------------------------------------------------------------------------

    private static Component left(long millis) {
        TimeText.Remaining left = TimeText.remaining(millis);
        return Component.translatable(left.key(), left.first(), left.second());
    }

    /** What a rule trades: an item by its own name, or a tag as {@code #namespace:path}. */
    private Component ruleTarget(HubPage.Row row) {
        return row.flag(HubPage.FLAG_C) ? EconomyHubScreen.arg(row.arg(1)) : this.screen.itemName(row.icon());
    }

    private Component firstLine(HubPage.Row row) {
        Component what = isRule(row) ? ruleTarget(row) : this.screen.itemName(row.icon());
        return Component.translatable(row.title(), what);
    }

    private Component secondLine(HubPage.Row row) {
        return switch (row.title()) {
            case HubPages.ORDER_SELL -> Component.translatable("gui.neroeconomy.hub.orders.sell_line", row.num(0),
                    row.num(1), left(row.num(2)));
            case HubPages.ORDER_BUY -> Component.translatable("gui.neroeconomy.hub.orders.buy_line", row.num(0),
                    row.num(1), row.num(2), left(row.num(3)));
            case HubPages.ORDER_SHOP -> Component.translatable("gui.neroeconomy.hub.orders.shop_line", row.num(0),
                    row.num(1));
            default -> Component.translatable(row.title().equals(HubPages.RULE_BUY)
                    ? "gui.neroeconomy.hub.orders.rule_buy_line" : "gui.neroeconomy.hub.orders.rule_sell_line",
                    EconomyHubScreen.arg(row.arg(0)), row.num(1), row.num(2), row.num(3), row.num(4), row.num(0));
        };
    }

    private Component confirmLine(HubPage.Row row) {
        if (row == null) return Component.translatable("gui.neroeconomy.hub.orders.hint");
        if (armed("cancel|" + row.id())) return row.title().equals(HubPages.ORDER_BUY)
                ? Component.translatable("gui.neroeconomy.hub.orders.confirm.cancel_buy", row.num(2))
                : Component.translatable("gui.neroeconomy.hub.orders.confirm.cancel_sell", row.num(0),
                        this.screen.itemName(row.icon()));
        if (armed("delete|" + row.id())) return row.title().equals(HubPages.RULE_BUY)
                ? Component.translatable("gui.neroeconomy.hub.orders.confirm.delete_buy", row.num(5))
                : Component.translatable("gui.neroeconomy.hub.orders.confirm.delete_sell");
        if (row.title().equals(HubPages.ORDER_SHOP)) return Component.translatable("gui.neroeconomy.hub.orders.shop_hint");
        return Component.translatable(isRule(row) ? "gui.neroeconomy.hub.orders.rule_hint"
                : "gui.neroeconomy.hub.orders.order_hint");
    }

    @Override
    void draw(GuiGraphicsExtractor g) {
        if (this.form) {
            drawForm(g);
            return;
        }
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable(header(HubPages.PAUSED_ALL) == 1L ? "gui.neroeconomy.hub.orders.summary_paused"
                : "gui.neroeconomy.hub.orders.summary", header(HubPages.RULES), header(HubPages.MAX_RULES),
                header(HubPages.VALUE_TODAY), header(HubPages.VALUE_CAP)), 8, 20, 240, TexturedContainerScreen.SUBTLE);
        drawSelection(g);
        if (this.page.rows().isEmpty())
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.orders.empty"), 10, 36, 236, TexturedContainerScreen.SUBTLE);
        for (int index = 0; index < this.page.rows().size() && index < this.visibleRows; index++) {
            HubPage.Row row = this.page.rows().get(index);
            int y = this.listY + index * this.rowH;
            boolean picked = row.id().equals(this.selected);
            s.icon(g, row.icon(), 10, y + 2);
            s.text(g, firstLine(row), 30, y + 2, 214, picked ? TexturedContainerScreen.SELECTED : TexturedContainerScreen.TITLE);
            s.text(g, secondLine(row), 30, y + 11, 214, isRule(row) && row.flag(HubPage.FLAG_B) ? EconomyHubScreen.WARNING
                    : TexturedContainerScreen.SUBTLE);
        }
        drawPageText(g, 54, 159);
        HubPage.Row row = selectedRow();
        boolean confirming = row != null && (armed("cancel|" + row.id()) || armed("delete|" + row.id()));
        s.text(g, confirmLine(row), 8, 192, 240, confirming ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
    }

    private Component feedback() {
        String id = typedTarget();
        if (id.isEmpty()) return Component.translatable(this.tag ? "gui.neroeconomy.auto_trader.target.empty_tag"
                : "gui.neroeconomy.auto_trader.target.empty_item");
        if (this.tag) return Component.translatable(tagKnown(id) ? "gui.neroeconomy.auto_trader.target.tag_ok"
                : "gui.neroeconomy.auto_trader.target.tag_unknown");
        Item found = item(id);
        return found == null ? Component.translatable("gui.neroeconomy.auto_trader.target.item_unknown")
                : Component.translatable("gui.neroeconomy.auto_trader.target.item_ok", this.screen.itemName(id));
    }

    private void drawForm(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        String id = typedTarget();
        boolean known = !id.isEmpty() && (this.tag ? tagKnown(id) : item(id) != null);
        s.text(g, feedback(), 8, 56, 240, known ? EconomyHubScreen.GOOD : EconomyHubScreen.WARNING);
        s.text(g, Component.translatable("gui.neroeconomy.hub.orders.form.limit"), 8, 73, 108, TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.orders.form.per_day"), 8, 91, 108, TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable(this.buy ? "gui.neroeconomy.hub.orders.form.budget"
                : "gui.neroeconomy.hub.orders.form.quantity"), 8, 109, 108, TexturedContainerScreen.SUBTLE);
        boolean armed = formValid() && armed(formText());
        String lines = armed ? (this.buy ? "gui.neroeconomy.hub.orders.form.confirm_buy"
                : "gui.neroeconomy.hub.orders.form.confirm_sell") : "gui.neroeconomy.hub.orders.form.hint";
        s.text(g, Component.translatable(lines + ".1", number(this.total), number(this.perDay), number(this.limit)),
                8, 146, 240, armed ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable(lines + ".2", number(this.total), number(this.perDay), number(this.limit)),
                8, 156, 240, armed ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
    }

    @Override
    boolean click(int x, int y) { return !this.form && super.click(x, y); }

    @Override
    Component tooltipAt(int x, int y) {
        if (this.form) return null;
        if (y >= 19 && y < 29 && x >= 8 && x < 248) return Component.translatable("gui.neroeconomy.hub.orders.summary.tooltip");
        HubPage.Row row = rowAt(x, y);
        if (row != null && isRule(row)) return Component.translatable("gui.neroeconomy.hub.orders.rule.tooltip");
        return null;
    }
}
