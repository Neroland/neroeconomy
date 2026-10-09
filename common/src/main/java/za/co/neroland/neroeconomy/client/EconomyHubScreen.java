package za.co.neroland.neroeconomy.client;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.client.demo.DemoScript;
import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubPages;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.menu.EconomyHubMenu;
import za.co.neroland.neroeconomy.network.ClientHubPage;
import za.co.neroland.neroeconomy.network.HubIntentPayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;

/**
 * The Economy Hub: a tab bar down the left and the current tab's page beside it. The page is what
 * the server last sent; the screen and its tab views only draw it and send intents. Each tab is one
 * {@link HubTabView}; a tab this build does not have yet shows {@link HubComingSoonView}.
 *
 * <p>Layout (panel-relative, panel 280x224 = a 24px tab column and a 256x224 body, both sheets made
 * by {@code tools/gen_gui.py}). Body coordinates below start at x 24:</p>
 * <pre>
 *   tab column  ten 20x20 tab buttons from (2, 6), 21px apart; the selected one is lit
 *   body 0..16  header: tab name at (8, 5); a Merchant Stall's House button at (110, 2, 112x12);
 *               the "?" button at (236, 2, 14x12)
 *   body 18..222 the tab's own page (see each view)
 * </pre>
 * The whole panel fits the 320x240 a 854x480 window has at GUI scale 2, the largest it allows.
 *
 * <p>The "?" button runs the tab's "Show me" walkthrough ({@link HubDemo}): while it runs the body
 * shows a separate sample view built by the same {@link #newView factory} but handed the demo's
 * sender, the tab bar and the House button are gone, clicks go to widgets only (never to the
 * container), and Esc or the inventory key ends it. The live view is left untouched meanwhile and
 * comes back exactly as it was, typed text included. Only {@link #live} can reach the server.</p>
 */
public final class EconomyHubScreen extends TexturedContainerScreen<EconomyHubMenu> {
    private static final Identifier BODY_TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/economy_hub.png");
    private static final Identifier TABS_TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/economy_hub_tabs.png");
    static final int TAB_W = 24;
    static final int BODY_W = 256;
    static final int PANEL_W = TAB_W + BODY_W;
    static final int PANEL_H = 224;
    private static final int TAB_X = 2;
    private static final int TAB_Y = 6;
    private static final int TAB_STEP = 21;
    private static final int TAB_SIZE = 20;
    static final int GOOD = 0xFF96EC78;
    static final int WARNING = 0xFFFFD166;
    static final int BAD = 0xFFFF8A65;

    private final Map<HubTab, HubTabView> views = new EnumMap<>(HubTab.class);
    private final Map<String, ItemStack> icons = new HashMap<>();
    /** What each of this screen's buttons runs, so a demo can press one without an input event. */
    private final Map<Button, Runnable> presses = new IdentityHashMap<>();
    /** The one sender that reaches the server; handed to the live views only. */
    private final HubIntents live = this::send;
    /** The walkthrough running over the body; null when none is. */
    private HubDemo demo;
    private HubPage page;
    /** Set until the first page has arrived: nothing can be acted on before the server has spoken. */
    private boolean waiting = true;

    public EconomyHubScreen(EconomyHubMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BODY_TEXTURE, PANEL_W, PANEL_H);
        this.page = HubPage.empty(menu.containerId, HubTab.WALLET);
        this.titleLabelX = TAB_W + 8;
        this.titleLabelY = 5;
    }

    // --- what the views use ------------------------------------------------------------------

    Font font() { return this.font; }

    HubPage page() { return this.page; }

    boolean waiting() { return this.waiting; }

    /** Rebuilds the widgets, for a view whose controls changed (a market mode, the rule form). */
    void rebuild() { rebuildWidgets(); }

    /** Panel-relative x of the body's left edge, in screen coordinates. */
    int bodyLeft() { return this.leftPos + TAB_W; }

    int bodyTop() { return this.topPos; }

    /** Sends an intent about the tab on screen, with a fresh request id. Only {@link #live} calls it. */
    private void send(HubAction action, UUID target, long a, long b, String text) {
        NeroEconomyNetwork.sendToServer(new HubIntentPayload(this.menu.containerId, UUID.randomUUID(),
                this.page.tab(), action, target, a, b, text == null ? "" : text));
    }

    Button button(Component label, String tooltipKey, int x, int y, int width, int height, Runnable action) {
        Button button = Button.builder(label, ignored -> action.run())
                .bounds(bodyLeft() + x, bodyTop() + y, width, height)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey))).build();
        this.presses.put(button, action);
        return addRenderableWidget(button);
    }

    /**
     * A demo's own button: listened to but not drawn with the other widgets, because the demo draws
     * it over its caption.
     */
    Button demoButton(Component label, String tooltipKey, int x, int y, int width, int height, Runnable action) {
        Button button = Button.builder(label, ignored -> action.run())
                .bounds(bodyLeft() + x, bodyTop() + y, width, height)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey))).build();
        return addWidget(button);
    }

    /** Presses the enabled, shown button at a body-relative point, as a click would. @return whether one was */
    boolean pressAt(int x, int y) {
        for (Map.Entry<Button, Runnable> entry : List.copyOf(this.presses.entrySet())) {
            Button button = entry.getKey();
            if (!this.children().contains(button) || !button.active || !button.visible
                    || !button.isMouseOver(bodyLeft() + x, bodyTop() + y)) continue;
            entry.getValue().run();
            return true;
        }
        return false;
    }

    /** Sets the text of the box at a body-relative point. @return whether there was one */
    boolean typeAt(int x, int y, String text) {
        for (var child : this.children()) {
            if (child instanceof EditBox box && box.visible && box.isMouseOver(bodyLeft() + x, bodyTop() + y)) {
                box.setValue(text);
                return true;
            }
        }
        return false;
    }

    /** Hides every widget that overlaps a body-relative rectangle, except {@code keep}. */
    void hideWidgetsIn(int x, int y, int w, int h, List<Button> keep) {
        int left = bodyLeft() + x;
        int top = bodyTop() + y;
        for (var child : this.children()) {
            if (!(child instanceof AbstractWidget widget) || keep.contains(widget)) continue;
            if (widget.getX() < left + w && widget.getX() + widget.getWidth() > left
                    && widget.getY() < top + h && widget.getY() + widget.getHeight() > top) widget.visible = false;
        }
    }

    EditBox box(EditBox previous, String key, int x, int y, int width, int maxLength) {
        EditBox box = new EditBox(this.font, bodyLeft() + x, bodyTop() + y, width, 14, Component.translatable(key));
        box.setMaxLength(maxLength);
        if (previous != null) box.setValue(previous.getValue());
        box.setHint(Component.translatable(key + ".hint"));
        box.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
        return addRenderableWidget(box);
    }

    /** Text at a body-relative position. */
    void text(GuiGraphicsExtractor g, Component text, int x, int y, int color) {
        label(g, text, TAB_W + x, y, color);
    }

    /** Text cut to {@code width}, at a body-relative position. */
    void text(GuiGraphicsExtractor g, Component text, int x, int y, int width, int color) {
        label(g, trim(text, width), TAB_W + x, y, color);
    }

    /** Text wrapped to {@code width}, at a body-relative position. */
    void wrapped(GuiGraphicsExtractor g, Component text, int x, int y, int width, int color) {
        g.textWithWordWrap(this.font, text, bodyLeft() + x, bodyTop() + y, width, color, false);
    }

    void textRight(GuiGraphicsExtractor g, Component text, int xRight, int y, int color) {
        labelRight(g, text, TAB_W + xRight, y, color);
    }

    void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        band(g, TAB_W + x, y, w, h, color);
    }

    int width(Component text) { return this.font.width(text); }

    Component cut(Component text, int width) { return trim(text, width); }

    /** The icon of an item id, at a body-relative position. Unknown ids draw nothing. */
    void icon(GuiGraphicsExtractor g, String itemId, int x, int y) {
        ItemStack stack = stack(itemId);
        if (!stack.isEmpty()) g.item(stack, bodyLeft() + x, bodyTop() + y);
    }

    private ItemStack stack(String itemId) {
        return this.icons.computeIfAbsent(itemId == null ? "" : itemId, id -> {
            Identifier key = Identifier.tryParse(id);
            Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
            return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    /**
     * An item's own name — never a stack's custom name, which is text somebody typed. An id this
     * client does not know is shown as the id.
     */
    Component itemName(String itemId) {
        ItemStack stack = stack(itemId);
        return stack.isEmpty() ? Component.literal(itemId == null ? "" : itemId) : stack.getItemName();
    }

    /** A row argument: a lang key, or a {@code #tag} id shown as it is. */
    static Component arg(String value) {
        if (value == null || value.isEmpty()) return Component.empty();
        return value.startsWith("#") ? Component.literal(value) : Component.translatable(value);
    }

    // --- tabs --------------------------------------------------------------------------------

    private boolean admin() { return this.page.number(HubPages.ADMIN, 0L) == 1L; }

    /** The one view factory: the live views and a demo's sample views are made here alike. */
    HubTabView newView(HubTab tab, HubIntents intents, HubPage first) {
        return switch (tab) {
            case WALLET -> new HubWalletView(this, intents, first);
            case VAULT -> new HubVaultView(this, intents, first);
            case MARKET -> new HubMarketView(this, intents, first);
            case ORDERS -> new HubOrdersView(this, intents, first);
            case INVEST -> new HubInvestView(this, intents, first);
            case TRADE -> new HubTradeView(this, intents, first);
            case JOBS -> new HubJobsView(this, intents, first);
            case AUCTIONS -> new HubAuctionsView(this, intents, first);
            case SHOP -> new HubShopView(this, intents, first);
            case ADMIN -> new HubAdminView(this, intents, first);
        };
    }

    private HubTabView view(HubTab tab) {
        return this.views.computeIfAbsent(tab, key -> key.built() ? newView(key, this.live, this.page)
                : new HubComingSoonView(this, this.live, this.page, key));
    }

    private HubTabView current() { return view(this.page.tab()); }

    /** The view the body shows: the demo's sample view while one runs, the live one otherwise; may be null. */
    private HubTabView shown() { return this.demo != null ? this.demo.view() : current(); }

    // --- the "Show me" demo ------------------------------------------------------------------

    /** Starts the walkthrough of the tab on screen. */
    private void startDemo() {
        if (this.waiting || this.demo != null) return;
        this.demo = new HubDemo(this, DemoScript.of(this.page.tab()));
        this.demo.show(0);
    }

    /** Ends the walkthrough and shows the live tab again, as it was. */
    void closeDemo() {
        if (this.demo == null) return;
        this.demo = null;
        setFocused(null);
        rebuildWidgets();
    }

    @Override
    protected void init() {
        super.init();
        this.presses.clear();
        if (this.demo != null) {
            this.demo.init();
            return;
        }
        List<HubTab> tabs = HubTab.visibleTabs(admin());
        for (int index = 0; index < tabs.size(); index++) {
            HubTab tab = tabs.get(index);
            Button button = Button.builder(Component.empty(), ignored -> {
                if (!this.waiting && tab != this.page.tab()) this.live.send(HubAction.OPEN_TAB, null, tab.ordinal(), 0L, "");
            }).bounds(this.leftPos + TAB_X, this.topPos + TAB_Y + index * TAB_STEP, TAB_SIZE, TAB_SIZE)
                    .tooltip(Tooltip.create(Component.translatable(tab.tooltipKey()))).build();
            button.active = tab != this.page.tab();
            addRenderableWidget(button);
        }
        // The "?" button opens the tab's "Show me" walkthrough.
        button(Component.translatable("gui.neroeconomy.hub.help"), this.page.tab().helpKey(), 236, 2, 14, 12,
                this::startDemo).active = !this.waiting;
        if (this.page.tab() == HubTab.MARKET && this.page.number(HubPages.STALL, 0L) == 1L) {
            Component house = this.page.key(HubPages.HOUSE).isEmpty()
                    ? Component.translatable("gui.neroeconomy.hub.market.house_none")
                    : Component.translatable(this.page.key(HubPages.HOUSE));
            button(trim(house, 104), "gui.neroeconomy.hub.market.house.tooltip", 110, 2, 112, 12,
                    () -> this.live.send(HubAction.MARKET_HOUSE, null, 0L, 0L, ""));
        }
        current().init();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        HubPage update = ClientHubPage.poll(this.menu.containerId);
        if (update != null && update.revision() > this.page.revision()) {
            boolean rebuild = this.waiting || update.tab() != this.page.tab()
                    || update.number(HubPages.ADMIN, 0L) != this.page.number(HubPages.ADMIN, 0L)
                    || update.number(HubPages.STALL, 0L) != this.page.number(HubPages.STALL, 0L)
                    || !update.key(HubPages.HOUSE).equals(this.page.key(HubPages.HOUSE));
            boolean switched = update.tab() != this.page.tab();
            this.page = update;
            this.waiting = false;
            view(update.tab()).accept(update);
            if (this.demo != null && switched) this.demo = null;
            if (this.demo == null && rebuild) rebuildWidgets();
        }
        if (this.demo == null) {
            current().tick();
        } else if (this.demo.view() != null) {
            this.demo.view().tick();
            this.demo.hideUnderCaption();
        }
    }

    @Override
    public void removed() {
        super.removed();
        ClientHubPage.clear();
    }

    // --- drawing -----------------------------------------------------------------------------

    @Override
    protected void drawPanel(GuiGraphicsExtractor extractor) {
        extractor.blit(RenderPipelines.GUI_TEXTURED, TABS_TEXTURE, this.leftPos, this.topPos, 0.0F, 0.0F, TAB_W,
                this.imageHeight, 256, 256);
        extractor.blit(RenderPipelines.GUI_TEXTURED, BODY_TEXTURE, this.leftPos + TAB_W, this.topPos, 0.0F, 0.0F,
                BODY_W, this.imageHeight, 256, 256);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        if (this.demo != null) {
            if (this.demo.view() != null) this.demo.view().draw(g);
            return;
        }
        List<HubTab> tabs = HubTab.visibleTabs(admin());
        for (int index = 0; index < tabs.size(); index++) {
            HubTab tab = tabs.get(index);
            int y = this.topPos + TAB_Y + index * TAB_STEP;
            if (tab == this.page.tab()) band(g, 0, TAB_Y + index * TAB_STEP, 2, TAB_SIZE, CREDIT);
            ItemStack stack = stack(tab.icon());
            if (!stack.isEmpty()) g.item(stack, this.leftPos + TAB_X + 2, y + 2);
        }
        if (this.waiting) {
            text(g, Component.translatable("gui.neroeconomy.hub.loading"), 8, 24, SUBTLE);
            return;
        }
        current().draw(g);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(this.font, Component.translatable(this.page.tab().key()), this.titleLabelX, this.titleLabelY, TITLE,
                false);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractContents(extractor, mouseX, mouseY, partialTick);
        if (this.demo != null) this.demo.draw(extractor, mouseX, mouseY, partialTick);
        if (this.waiting || this.minecraft == null) return;
        HubTabView view = shown();
        if (view == null || this.demo != null && this.demo.onCaption(mouseX - bodyLeft(), mouseY - bodyTop())) return;
        Component tip = view.tooltipAt(mouseX - bodyLeft(), mouseY - bodyTop());
        if (tip != null) extractor.setTooltipForNextFrame(this.font, Tooltip.splitTooltip(this.minecraft, tip),
                mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.demo != null) return demoClicked(event, doubleClick);
        if (!this.waiting && event.button() == 0
                && current().click((int) Math.floor(event.x()) - bodyLeft(), (int) Math.floor(event.y()) - bodyTop()))
            return true;
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * A click while a demo runs: the demo's buttons first, then the sample view's widgets, then the
     * sample view itself. The container is never told, so a demo click cannot become a slot click.
     */
    private boolean demoClicked(MouseButtonEvent event, boolean doubleClick) {
        HubDemo running = this.demo;
        int x = (int) Math.floor(event.x()) - bodyLeft();
        int y = (int) Math.floor(event.y()) - bodyTop();
        Button control = null;
        for (Button button : running.controls())
            if (button.visible && button.isMouseOver(event.x(), event.y())) control = button;
        if (control != null) {
            control.mouseClicked(event, doubleClick);
            return true;
        }
        if (running.onCaption(x, y)) return true;
        for (var child : List.copyOf(this.children())) {
            if (running.controls().contains(child) || !child.isMouseOver(event.x(), event.y())) continue;
            if (child.mouseClicked(event, doubleClick)) {
                setFocused(child);
                if (event.button() == 0) setDragging(true);
            }
            return true;
        }
        if (event.button() == 0 && running.view() != null) running.view().click(x, y);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.demo == null) return super.mouseReleased(event);
        if (event.button() == 0 && isDragging()) {
            setDragging(false);
            if (getFocused() != null) getFocused().mouseReleased(event);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.demo == null) return super.mouseDragged(event, dx, dy);
        if (getFocused() != null && isDragging() && event.button() == 0) getFocused().mouseDragged(event, dx, dy);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean typing = getFocused() instanceof EditBox box && box.canConsumeInput();
        if (this.demo != null && (event.isEscape()
                || !typing && this.minecraft != null && this.minecraft.options.keyInventory.matches(event))) {
            closeDemo();
            return true;
        }
        return super.keyPressed(event);
    }
}
