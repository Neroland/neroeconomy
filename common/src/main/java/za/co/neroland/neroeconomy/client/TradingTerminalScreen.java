package za.co.neroland.neroeconomy.client;

import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;
import za.co.neroland.neroeconomy.network.ClientMarketPage;
import za.co.neroland.neroeconomy.network.ClientOpportunityPage;
import za.co.neroland.neroeconomy.network.MarketBuyOfferPayload;
import za.co.neroland.neroeconomy.network.MarketBuyPayload;
import za.co.neroland.neroeconomy.network.MarketClaimPayload;
import za.co.neroland.neroeconomy.network.MarketFillBuyOfferPayload;
import za.co.neroland.neroeconomy.network.MarketPagePayload;
import za.co.neroland.neroeconomy.network.MarketPostPayload;
import za.co.neroland.neroeconomy.network.MarketQueryPayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.network.OpportunityIntentPayload;
import za.co.neroland.neroeconomy.network.OpportunityPagePayload;
import za.co.neroland.neroeconomy.obligation.JobState;
import za.co.neroland.neroeconomy.obligation.JobTargets;
import za.co.neroland.neroeconomy.text.TimeText;

/**
 * Paged searchable market UI, shared by the Trading Terminal and the Market Board. Selection and all
 * prices are server snapshots; the board only adds the posting row.
 *
 * <p>Layout (panel-relative, panel 256x226, no menu slots):</p>
 * <pre>
 *   0..16     header: title (8, 6)
 *   20..38    search field (8, 20, 180x18) | Search (192, 20, 56x18)
 *   42..58    status well: balance (11, 46), result/claim counts right-aligned at 245
 *   62..156   listing frame: 8 fixed rows of 11px from y 66, text clipped to the frame
 *   160..178  Previous (8) | Next (70) | Select (132), page readout right-aligned at 245
 *   182..200  Buy (8) | Collect (70) | Jobs (132) | Act (194), all 54 wide
 *   204..222  board only: quantity (8, 52) | price (68, 52) | Post (128, 58) | Want (194, 54)
 * </pre>
 */
public final class TradingTerminalScreen extends TexturedContainerScreen<TradingTerminalMenu> {

    private static final Identifier TERMINAL_TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/trading_terminal.png");
    private static final Identifier BOARD_TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/market_board.png");
    /** Terminal accent: exchange teal. Board accent: a lighter notice green. */
    private static final int TERMINAL_ACCENT = 0xFF30D6BE;
    private static final int BOARD_ACCENT = 0xFF96EC78;

    private static final int PANEL_W = 256;
    private static final int PANEL_H = 226;
    private static final int LEFT = 8;
    private static final int RIGHT = 245;
    /** Listing frame (inclusive left/top, exclusive right/bottom) and its fixed row grid. */
    private static final int LIST_X = 9;
    private static final int LIST_Y = 64;
    private static final int LIST_W = 238;
    private static final int VISIBLE_ROWS = 8;
    private static final int ROW_H = 11;
    private static final int ROW_TEXT_X = 12;
    /** Rows stop short of the scroll rail so a long item id can never reach it. */
    private static final int RAIL_X = 240;
    private static final int RAIL_W = 4;
    private static final int ROW_TEXT_W = RAIL_X - 3 - ROW_TEXT_X;
    private static final int NAV_Y = 160;
    private static final int ACTION_Y = 182;
    private static final int POST_Y = 204;
    private static final int BUTTON_W = 54;
    private static final int BUTTON_H = 18;

    private EditBox search;
    private EditBox quantity;
    private EditBox price;
    private Button postButton;
    private Button wantButton;
    private MarketPagePayload page = new MarketPagePayload(0, 0, 0, 0, List.of());
    private int selected;
    private OpportunityPagePayload opportunities = new OpportunityPagePayload(0, 0,
            OpportunityPagePayload.Tab.CONTRACTS, List.of());
    /** This client's monotonic clock when {@link #opportunities} arrived; its timers count down from here. */
    private long opportunitiesReceivedNanos;
    private boolean opportunityTab;

    public TradingTerminalScreen(TradingTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TERMINAL_TEXTURE, PANEL_W, PANEL_H);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    /** The board and the terminal share this screen, so the sheet is chosen from the synced flag. */
    @Override
    protected Identifier background() {
        return this.menu.isBoard() ? BOARD_TEXTURE : TERMINAL_TEXTURE;
    }

    private int activeAccent() {
        return this.menu.isBoard() ? BOARD_ACCENT : TERMINAL_ACCENT;
    }

    @Override
    protected void init() {
        super.init();
        this.search = new EditBox(this.font, this.leftPos + LEFT, this.topPos + 20, 180, BUTTON_H,
                Component.translatable("gui.neroeconomy.market.search"));
        this.search.setMaxLength(64);
        this.search.setHint(Component.translatable("gui.neroeconomy.market.search_hint"));
        addRenderableWidget(this.search);
        button(Component.translatable("gui.neroeconomy.market.search"), 192, 20, 56, () -> query(0));

        // Board-only controls are built unconditionally: data slot 4 is not synced yet when init()
        // runs, so anything gated on isBoard() here would never appear. containerTick() toggles them.
        this.quantity = numericBox(LEFT, POST_Y, 52, "1", "gui.neroeconomy.market.quantity");
        this.price = numericBox(68, POST_Y, 52, "10", "gui.neroeconomy.market.price");
        this.postButton = button(Component.translatable("gui.neroeconomy.market.post"), 128, POST_Y, 58, this::post);
        this.wantButton = button(Component.translatable("gui.neroeconomy.market.want"), 194, POST_Y, BUTTON_W,
                this::postBuyOffer);

        button(Component.translatable("gui.neroeconomy.market.previous"), LEFT, NAV_Y, BUTTON_W,
                () -> query(Math.max(0, this.page.page() - 1)));
        button(Component.translatable("gui.neroeconomy.market.next"), 70, NAV_Y, BUTTON_W,
                () -> query(this.page.page() + 1));
        button(Component.translatable("gui.neroeconomy.market.select"), 132, NAV_Y, BUTTON_W, this::selectNext);

        button(Component.translatable("gui.neroeconomy.market.buy"), LEFT, ACTION_Y, BUTTON_W, this::buy);
        button(Component.translatable("gui.neroeconomy.market.collect"), 70, ACTION_Y, BUTTON_W,
                () -> NeroEconomyNetwork.sendToServer(new MarketClaimPayload(this.menu.containerId)));
        button(Component.translatable("gui.neroeconomy.opportunity.tab"), 132, ACTION_Y, BUTTON_W, this::nextTab);
        button(Component.translatable("gui.neroeconomy.opportunity.action"), 194, ACTION_Y, BUTTON_W,
                this::opportunityAction);

        applyBoardVisibility(this.menu.isBoard());
    }

    private EditBox numericBox(int x, int y, int width, String value, String hint) {
        EditBox box = new EditBox(this.font, this.leftPos + x, this.topPos + y, width, BUTTON_H,
                Component.translatable(hint));
        box.setMaxLength(19);
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private Button button(Component text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(text, ignored -> action.run())
                .bounds(this.leftPos + x, this.topPos + y, width, BUTTON_H).build());
    }

    private void applyBoardVisibility(boolean board) {
        for (AbstractWidget widget : new AbstractWidget[] { this.quantity, this.price, this.postButton,
                this.wantButton }) {
            if (widget == null) {
                continue;
            }
            if (!board && widget.isFocused()) {
                widget.setFocused(false);
            }
            widget.visible = board;
            widget.active = board;
        }
    }

    private void selectNext() {
        int size = lineCount();
        this.selected = size == 0 ? 0 : (this.selected + 1) % size;
    }

    private int lineCount() {
        return this.opportunityTab ? this.opportunities.lines().size() : this.page.lines().size();
    }

    /** The first row drawn, scrolled just far enough to keep the selection inside the fixed frame. */
    private int firstVisible() {
        int size = lineCount();
        if (size <= VISIBLE_ROWS) {
            return 0;
        }
        int first = Math.min(Math.max(0, this.selected - VISIBLE_ROWS + 1), size - VISIBLE_ROWS);
        return Math.min(first, this.selected);
    }

    private void query(int targetPage) {
        this.opportunityTab = false;
        NeroEconomyNetwork.sendToServer(new MarketQueryPayload(this.menu.containerId,
                this.search == null ? "" : this.search.getValue(), targetPage));
    }

    private void buy() {
        if (this.page.lines().isEmpty()) {
            return;
        }
        MarketPagePayload.Line line = this.page.lines().get(Math.min(this.selected, this.page.lines().size() - 1));
        if (line.buyOffer()) {
            NeroEconomyNetwork.sendToServer(new MarketFillBuyOfferPayload(this.menu.containerId, UUID.randomUUID(),
                    line.listingId(), 1));
        } else {
            NeroEconomyNetwork.sendToServer(new MarketBuyPayload(this.menu.containerId, UUID.randomUUID(),
                    line.listingId(), 1, line.unitPrice(), line.itemId()));
        }
    }

    private void post() {
        if (this.quantity == null || this.price == null) {
            return;
        }
        try {
            int count = Integer.parseInt(this.quantity.getValue());
            long unitPrice = Long.parseLong(this.price.getValue());
            NeroEconomyNetwork.sendToServer(new MarketPostPayload(this.menu.containerId, UUID.randomUUID(), count,
                    unitPrice));
        } catch (NumberFormatException ignored) {
            // Malformed input simply sends nothing; the server stays authoritative.
        }
    }

    private void postBuyOffer() {
        if (this.quantity == null || this.price == null) {
            return;
        }
        try {
            Identifier item = Identifier.parse(this.search.getValue());
            int count = Integer.parseInt(this.quantity.getValue());
            long unitPrice = Long.parseLong(this.price.getValue());
            NeroEconomyNetwork.sendToServer(new MarketBuyOfferPayload(this.menu.containerId, UUID.randomUUID(), item,
                    count, unitPrice));
        } catch (IllegalArgumentException ignored) {
            // Includes NumberFormatException and a malformed item id.
        }
    }

    private void nextTab() {
        this.opportunityTab = true;
        var values = OpportunityPagePayload.Tab.values();
        var tab = values[(this.opportunities.tab().ordinal() + 1) % values.length];
        NeroEconomyNetwork.sendToServer(new OpportunityIntentPayload(this.menu.containerId, UUID.randomUUID(), tab,
                OpportunityIntentPayload.Action.QUERY, "", 0L));
    }

    private void opportunityAction() {
        if (!this.opportunityTab || this.opportunities.lines().isEmpty()) {
            return;
        }
        var line = this.opportunities.lines().get(Math.min(this.selected, this.opportunities.lines().size() - 1));
        boolean auction = this.opportunities.tab() == OpportunityPagePayload.Tab.AUCTIONS;
        var action = auction ? OpportunityIntentPayload.Action.BID
                : line.state() == JobState.CLAIMED ? OpportunityIntentPayload.Action.FULFILL
                : OpportunityIntentPayload.Action.CLAIM;
        // A bid is exactly the minimum next bid the server put on the row; the server refuses it,
        // rather than raising it, if the auction has moved on since this page was sent.
        NeroEconomyNetwork.sendToServer(new OpportunityIntentPayload(this.menu.containerId, UUID.randomUUID(),
                this.opportunities.tab(), action, line.id().toString(), auction ? line.nextBid() : 0L));
    }

    /** Closing the screen empties both page mailboxes, so nothing sent to this terminal lingers. */
    @Override
    public void removed() {
        super.removed();
        ClientMarketPage.clear();
        ClientOpportunityPage.clear();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        applyBoardVisibility(this.menu.isBoard());
        MarketPagePayload update = ClientMarketPage.poll(this.menu.containerId);
        if (update != null && update.revision() > this.page.revision()) {
            boolean pageChanged = update.page() != this.page.page();
            this.page = update;
            this.selected = pageChanged ? 0 : Math.min(this.selected, Math.max(0, this.page.lines().size() - 1));
        }
        ClientOpportunityPage.Received received = ClientOpportunityPage.poll(this.menu.containerId);
        if (received != null && received.page().revision() > this.opportunities.revision()) {
            // The server re-pushes the open tab every few seconds, so neither the active tab nor the
            // selection may be reset by a refresh of the same tab.
            boolean tabChanged = received.page().tab() != this.opportunities.tab();
            this.opportunities = received.page();
            this.opportunitiesReceivedNanos = received.receivedNanos();
            this.selected = tabChanged ? 0
                    : Math.min(this.selected, Math.max(0, this.opportunities.lines().size() - 1));
        }
    }

    /**
     * Time left until an auction closes or a job expires. The server sends how long was left when it
     * built the page; this counts down from when the page arrived, on this client's own monotonic
     * clock, so no server timestamp is ever compared with the local clock.
     */
    private Component remaining(long remainingAtReceipt) {
        TimeText.Remaining left = TimeText.remaining(TimeText.remainingNow(remainingAtReceipt,
                this.opportunitiesReceivedNanos, System.nanoTime()));
        return Component.translatable(left.key(), left.first(), left.second());
    }

    /** A job target as shown: ids are shown as sent, a player-bounty token through its lang key. */
    private static Object targetText(String target) {
        String key = JobTargets.playerLabelKey(target);
        return key == null ? target : Component.translatable(key);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        int tint = activeAccent();
        label(g, Component.translatable("gui.neroeconomy.wallet", this.menu.balance()), 11, 46, CREDIT);
        labelRight(g, Component.translatable("gui.neroeconomy.market.counts",
                this.opportunityTab ? lineCount() : this.page.total(), this.menu.claimCount()), RIGHT, 46, SUBTLE);

        int size = lineCount();
        int first = firstVisible();
        int rows = Math.min(VISIBLE_ROWS, Math.max(0, size - first));
        for (int row = 0; row < rows; row++) {
            int index = first + row;
            int rowY = LIST_Y + 1 + row * ROW_H;
            boolean chosen = index == this.selected;
            if (chosen) {
                band(g, LIST_X, rowY, LIST_W, ROW_H - 1, (tint & 0x00FFFFFF) | 0x40000000);
                band(g, LIST_X, rowY, 2, ROW_H - 1, tint);
            }
            label(g, trim(lineText(index), ROW_TEXT_W), ROW_TEXT_X, rowY + 2, chosen ? SELECTED : TITLE);
        }
        if (size == 0) {
            label(g, Component.translatable("gui.neroeconomy.market.empty"), ROW_TEXT_X, LIST_Y + 6, SUBTLE);
        } else if (size > VISIBLE_ROWS) {
            // The frame shows a fixed eight rows, so a rail marks where the window sits in the results.
            int trackY = LIST_Y + 1;
            int trackH = VISIBLE_ROWS * ROW_H;
            band(g, RAIL_X, trackY, RAIL_W, trackH, TROUGH);
            int thumbH = Math.max(6, trackH * VISIBLE_ROWS / size);
            int thumbY = trackY + (trackH - thumbH) * first / Math.max(1, size - VISIBLE_ROWS);
            band(g, RAIL_X, thumbY, RAIL_W, thumbH, tint);
        }

        labelRight(g, Component.translatable("gui.neroeconomy.market.page", this.page.page() + 1), RIGHT,
                NAV_Y + 5, SUBTLE);
        if (!this.menu.isBoard()) {
            label(g, Component.translatable("gui.neroeconomy.market.board_only"), 11, POST_Y + 5, SUBTLE);
        }
    }

    private Component lineText(int index) {
        if (this.opportunityTab) {
            var line = this.opportunities.lines().get(index);
            Component time = remaining(line.remainingMillis());
            if (this.opportunities.tab() == OpportunityPagePayload.Tab.AUCTIONS) {
                return Component.translatable("gui.neroeconomy.opportunity.auction_line", targetText(line.target()),
                        line.amount(), line.nextBid(), time);
            }
            return Component.translatable("gui.neroeconomy.opportunity.line", targetText(line.target()),
                    line.amount(), Component.translatable(line.state().translationKey()), time);
        }
        var line = this.page.lines().get(index);
        return Component.translatable(line.buyOffer() ? "gui.neroeconomy.market.buy_line"
                : "gui.neroeconomy.market.line", line.itemId().toString(), line.stock(), line.unitPrice());
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // No player inventory on this menu, so only the title is drawn.
        g.text(this.font, this.title, this.titleLabelX, this.titleLabelY, TITLE, false);
    }
}
