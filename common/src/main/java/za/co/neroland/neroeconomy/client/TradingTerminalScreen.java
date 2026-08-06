package za.co.neroland.neroeconomy.client;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;
import za.co.neroland.neroeconomy.network.ClientMarketPage;
import za.co.neroland.neroeconomy.network.MarketBuyPayload;
import za.co.neroland.neroeconomy.network.MarketPagePayload;
import za.co.neroland.neroeconomy.network.MarketQueryPayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.network.MarketPostPayload;
import za.co.neroland.neroeconomy.network.MarketClaimPayload;
import za.co.neroland.neroeconomy.network.MarketBuyOfferPayload;
import za.co.neroland.neroeconomy.network.MarketFillBuyOfferPayload;
import net.minecraft.resources.Identifier;
import za.co.neroland.neroeconomy.network.ClientOpportunityPage;
import za.co.neroland.neroeconomy.network.OpportunityIntentPayload;
import za.co.neroland.neroeconomy.network.OpportunityPagePayload;

/** Paged searchable market UI. Selection and all prices are server snapshots. */
public final class TradingTerminalScreen extends AbstractContainerScreen<TradingTerminalMenu> {
    private EditBox search;
    private EditBox quantity;
    private EditBox price;
    private Button postButton;
    private Button wantButton;
    private MarketPagePayload page = new MarketPagePayload(0, 0, 0, 0, List.of());
    private int selected;
    private OpportunityPagePayload opportunities = new OpportunityPagePayload(0, 0,
            OpportunityPagePayload.Tab.CONTRACTS, List.of());
    private boolean opportunityTab;
    public TradingTerminalScreen(TradingTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 220, 166);
    }
    @Override protected void init() {
        super.init();
        search = new EditBox(font, leftPos + 8, topPos + 22, 120, 16,
                Component.translatable("gui.neroeconomy.market.search"));
        search.setMaxLength(64); search.setHint(Component.translatable("gui.neroeconomy.market.search_hint"));
        addRenderableWidget(search);
        // Board-only controls are built unconditionally: data slot 4 is not synced yet when init()
        // runs, so anything gated on isBoard() here would never appear. containerTick() toggles them.
        quantity = numericBox(8, 116, 38, "1", "gui.neroeconomy.market.quantity");
        price = numericBox(50, 116, 66, "10", "gui.neroeconomy.market.price");
        postButton = button(Component.translatable("gui.neroeconomy.market.post"), 120, 116, this::post);
        wantButton = button(Component.translatable("gui.neroeconomy.market.want"), 120, 96, this::postBuyOffer);
        button(Component.translatable("gui.neroeconomy.market.search"), 132, 22, () -> query(0));
        button(Component.translatable("gui.neroeconomy.market.previous"), 8, 136, () -> query(Math.max(0, page.page() - 1)));
        button(Component.translatable("gui.neroeconomy.market.next"), 66, 136, () -> query(page.page() + 1));
        button(Component.translatable("gui.neroeconomy.market.select"), 124, 136,
                () -> { int size = opportunityTab ? opportunities.lines().size() : page.lines().size();
                    selected = size == 0 ? 0 : (selected + 1) % size; });
        button(Component.translatable("gui.neroeconomy.market.buy"), 174, 136, this::buy);
        button(Component.translatable("gui.neroeconomy.market.collect"), 174, 116,
                () -> NeroEconomyNetwork.sendToServer(new MarketClaimPayload(menu.containerId)));
        button(Component.translatable("gui.neroeconomy.opportunity.tab"), 174, 96, this::nextTab);
        button(Component.translatable("gui.neroeconomy.opportunity.action"), 124, 96, this::opportunityAction);
        applyBoardVisibility(menu.isBoard());
    }
    private EditBox numericBox(int x, int y, int width, String value, String hint) {
        EditBox box = new EditBox(font, leftPos + x, topPos + y, width, 16, Component.translatable(hint));
        box.setMaxLength(19); box.setValue(value);
        addRenderableWidget(box); return box;
    }
    private Button button(Component text, int x, int y, Runnable action) {
        return addRenderableWidget(Button.builder(text, ignored -> action.run())
                .bounds(leftPos + x, topPos + y, 46, 18).build());
    }
    private void applyBoardVisibility(boolean board) {
        for (var widget : new net.minecraft.client.gui.components.AbstractWidget[] {
                quantity, price, postButton, wantButton }) {
            if (widget == null) continue;
            if (!board && widget.isFocused()) widget.setFocused(false);
            widget.visible = board;
            widget.active = board;
        }
    }
    private void query(int targetPage) {
        opportunityTab = false;
        NeroEconomyNetwork.sendToServer(new MarketQueryPayload(menu.containerId, UUID.randomUUID(),
                search == null ? "" : search.getValue(), targetPage));
    }
    private void buy() {
        if (page.lines().isEmpty()) return;
        MarketPagePayload.Line line = page.lines().get(Math.min(selected, page.lines().size() - 1));
        if (line.buyOffer()) NeroEconomyNetwork.sendToServer(new MarketFillBuyOfferPayload(menu.containerId,
                UUID.randomUUID(), line.listingId(), 1));
        else NeroEconomyNetwork.sendToServer(new MarketBuyPayload(menu.containerId, UUID.randomUUID(),
                    line.listingId(), 1, line.unitPrice()));
    }
    private void post() {
        if (quantity == null || price == null) return;
        try {
            int count = Integer.parseInt(quantity.getValue());
            long unitPrice = Long.parseLong(price.getValue());
            NeroEconomyNetwork.sendToServer(new MarketPostPayload(menu.containerId, UUID.randomUUID(), count, unitPrice));
        } catch (NumberFormatException ignored) { }
    }
    private void postBuyOffer() {
        if (quantity == null || price == null) return;
        try {
            Identifier item = Identifier.parse(search.getValue());
            int count = Integer.parseInt(quantity.getValue());
            long unitPrice = Long.parseLong(price.getValue());
            NeroEconomyNetwork.sendToServer(new MarketBuyOfferPayload(menu.containerId, UUID.randomUUID(), item,
                    count, unitPrice));
        } catch (IllegalArgumentException ignored) { }
    }
    private void nextTab() {
        opportunityTab = true;
        var values = OpportunityPagePayload.Tab.values();
        var tab = values[(opportunities.tab().ordinal() + 1) % values.length];
        NeroEconomyNetwork.sendToServer(new OpportunityIntentPayload(menu.containerId, UUID.randomUUID(), tab,
                OpportunityIntentPayload.Action.QUERY, "", 0L));
    }
    private void opportunityAction() {
        if (!opportunityTab || opportunities.lines().isEmpty()) return;
        var line = opportunities.lines().get(Math.min(selected, opportunities.lines().size() - 1));
        var action = opportunities.tab() == OpportunityPagePayload.Tab.AUCTIONS
                ? OpportunityIntentPayload.Action.BID
                : "CLAIMED".equals(line.state()) ? OpportunityIntentPayload.Action.FULFILL
                : OpportunityIntentPayload.Action.CLAIM;
        NeroEconomyNetwork.sendToServer(new OpportunityIntentPayload(menu.containerId, UUID.randomUUID(),
                opportunities.tab(), action, line.id().toString(), Math.max(1L, line.amount() + 1L)));
    }
    @Override protected void containerTick() {
        super.containerTick();
        applyBoardVisibility(menu.isBoard());
        MarketPagePayload update = ClientMarketPage.poll(menu.containerId);
        if (update != null && update.revision() > page.revision()) {
            boolean pageChanged = update.page() != page.page();
            page = update;
            selected = pageChanged ? 0 : Math.min(selected, Math.max(0, page.lines().size() - 1));
        }
        OpportunityPagePayload opportunityUpdate = ClientOpportunityPage.poll(menu.containerId);
        if (opportunityUpdate != null && opportunityUpdate.revision() > opportunities.revision()) {
            // The server re-pushes the open tab once a second to keep countdowns live, so neither the
            // active tab nor the selection may be reset by a refresh of the same tab.
            boolean tabChanged = opportunityUpdate.tab() != opportunities.tab();
            opportunities = opportunityUpdate;
            selected = tabChanged ? 0 : Math.min(selected, Math.max(0, opportunities.lines().size() - 1));
        }
    }
    /** Remaining time until an auction closes or a job expires, rendered from the server timestamp. */
    private static String remaining(long endsAt) {
        long seconds = Math.max(0L, endsAt - System.currentTimeMillis()) / 1000L;
        return seconds >= 3600L
                ? String.format(Locale.ROOT, "%dh%02dm", seconds / 3600L, seconds % 3600L / 60L)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }
    @Override public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF203849);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF0D1620);
        g.text(font, Component.translatable("gui.neroeconomy.balance", menu.balance()), leftPos + 8, topPos + 44,
                0xFF63D7FF, false);
        if (opportunityTab) for (int index = 0; index < opportunities.lines().size(); index++) {
            var line = opportunities.lines().get(index); int color = index == selected ? 0xFFFFD166 : 0xFFD6ECFF;
            MutableComponent text = Component.translatable("gui.neroeconomy.opportunity.line", line.target(),
                    line.amount(), line.state());
            if (line.endsAt() > 0L) text = text.append(Component.literal("  " + remaining(line.endsAt())));
            g.text(font, text, leftPos + 8, topPos + 60 + index * 10, color, false);
        } else for (int index = 0; index < page.lines().size(); index++) {
            var line = page.lines().get(index); int color = index == selected ? 0xFFFFD166 : 0xFFD6ECFF;
            g.text(font, Component.translatable(line.buyOffer() ? "gui.neroeconomy.market.buy_line"
                    : "gui.neroeconomy.market.line", line.itemId().toString(), line.stock(), line.unitPrice()),
                    leftPos + 8, topPos + 60 + index * 10, color, false);
        }
        super.extractContents(g, mouseX, mouseY, partialTick);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, 8, 6, 0xFFD6ECFF, false);
    }
}
