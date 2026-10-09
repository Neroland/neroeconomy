package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.exchange.PriceText;
import za.co.neroland.neroeconomy.hub.AuctionsPages;
import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;

/**
 * The Auctions tab: every running auction the player may see (a Merchant House's lots badged with
 * its name), their own auctions, bidding, cancelling one of theirs that has no bid, and starting
 * one from the main hand with the auction fee stated before the confirm. Times are the server's,
 * counted down here; the least next bid is the server's too.
 *
 * <pre>
 *   20   All (8, 40) | Mine (50, 40) | Create (92, 50)
 *   36   the fee and the least step between bids
 *   LISTS   50: six rows of 20px | 174: page buttons | bid (120, 174, 50) + Bid (174, 74), or
 *           Cancel (150, 174, 98) for one's own | 194: the confirm lines
 *   CREATE  50: what is in the hand | 68: how many (8, 40), least bid (52, 70), minutes (126, 50),
 *           interplanetary (180, 68) | 90..: the fee | 194: Create (8, 80) + the confirm
 * </pre>
 */
final class HubAuctionsView extends HubTabView {
    private EditBox amount;
    private EditBox quantity;
    private EditBox minimum;
    private EditBox minutes;
    private boolean orbit;
    private Button bid;
    private Button cancel;
    private Button create;

    HubAuctionsView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listY = 50;
        this.listW = 240;
        this.rowH = 20;
        layout();
    }

    private HubAction.AuctionsView view() { return HubAction.AuctionsView.byOrdinal(header(AuctionsPages.VIEW)); }

    private void layout() { this.visibleRows = view() == HubAction.AuctionsView.CREATE ? 0 : AuctionsPages.AUCTION_ROWS; }

    @Override
    void accept(HubPage next) {
        HubAction.AuctionsView was = view();
        boolean orbitWas = header(AuctionsPages.ORBIT) == 1L;
        super.accept(next);
        if (was != view() || orbitWas != (header(AuctionsPages.ORBIT) == 1L)) {
            this.selected = null;
            disarm();
            layout();
            this.screen.rebuild();
        }
    }

    private void tab(HubAction.AuctionsView target, String key, int x, int w) {
        Button button = this.screen.button(Component.translatable(key), key + ".tooltip", x, 20, w, 14,
                () -> this.intents.send(HubAction.AUCTION_VIEW, null, target.ordinal(), 0L, ""));
        button.active = view() != target;
    }

    @Override
    void init() {
        layout();
        tab(HubAction.AuctionsView.ALL, "gui.neroeconomy.hub.auctions.all", 8, 40);
        tab(HubAction.AuctionsView.MINE, "gui.neroeconomy.hub.auctions.mine", 50, 40);
        tab(HubAction.AuctionsView.CREATE, "gui.neroeconomy.hub.auctions.create", 92, 50);
        this.bid = null;
        this.cancel = null;
        this.create = null;
        if (view() != HubAction.AuctionsView.CREATE) {
            pageButtons(8, 174);
            this.amount = this.screen.box(this.amount, "gui.neroeconomy.hub.auctions.amount", 92, 174, 54, 13);
            this.bid = this.screen.button(Component.empty(), "gui.neroeconomy.hub.auctions.bid.tooltip", 150, 174, 46, 14,
                    () -> {
                        HubPage.Row row = selectedRow();
                        long value = number(this.amount);
                        if (row == null || value <= 0L) return;
                        press("bid|" + row.id() + "|" + value,
                                () -> this.intents.send(HubAction.AUCTION_BID, row.id(), value, 0L, ""));
                    });
            this.cancel = this.screen.button(Component.empty(), "gui.neroeconomy.hub.auctions.cancel.tooltip", 200, 174,
                    48, 14, () -> {
                        HubPage.Row row = selectedRow();
                        if (row != null) press("cancel|" + row.id(),
                                () -> this.intents.send(HubAction.AUCTION_CANCEL, row.id(), 0L, 0L, ""));
                    });
            return;
        }
        this.quantity = this.screen.box(this.quantity, "gui.neroeconomy.hub.auctions.quantity", 8, 68, 40, 2);
        this.minimum = this.screen.box(this.minimum, "gui.neroeconomy.hub.auctions.minimum", 52, 68, 70, 13);
        this.minutes = this.screen.box(this.minutes, "gui.neroeconomy.hub.auctions.minutes", 126, 68, 50, 5);
        if (header(AuctionsPages.ORBIT) == 1L) {
            this.screen.button(Component.translatable(this.orbit ? "gui.neroeconomy.hub.auctions.orbit.on"
                    : "gui.neroeconomy.hub.auctions.orbit.off"), "gui.neroeconomy.hub.auctions.orbit.tooltip", 180, 68, 68,
                    14, () -> {
                        this.orbit = !this.orbit;
                        disarm();
                        this.screen.rebuild();
                    });
        } else {
            this.orbit = false;
        }
        this.create = this.screen.button(Component.empty(), "gui.neroeconomy.hub.auctions.create_button.tooltip", 8, 194,
                80, 14, this::create);
    }

    private String createForm() {
        long count = number(this.quantity);
        long least = number(this.minimum);
        long length = number(this.minutes);
        if (count < 1L || count > header(AuctionsPages.MAX_QUANTITY) || least < 1L || length < 1L
                || length > header(AuctionsPages.MAX_MINUTES)) return "";
        return count + "|" + least + "|" + length + ":" + (this.orbit ? 1 : 0);
    }

    private void create() {
        String form = createForm();
        if (form.isEmpty()) return;
        long count = number(this.quantity);
        long least = number(this.minimum);
        String text = form.substring(form.lastIndexOf('|') + 1);
        press("create|" + form, () -> this.intents.send(HubAction.AUCTION_CREATE, null, count, least, text));
    }

    @Override
    void tick() {
        HubPage.Row row = selectedRow();
        if (this.bid != null) {
            long value = number(this.amount);
            String what = row == null ? "" : "bid|" + row.id() + "|" + value;
            boolean mine = row != null && row.flag(AuctionsPages.FLAG_MINE);
            if (!this.armedFor.isEmpty() && !armed(what) && (row == null || !armed("cancel|" + row.id()))) disarm();
            this.bid.active = row != null && !mine && value > 0L;
            label(this.bid, "gui.neroeconomy.hub.auctions.bid", !what.isEmpty() && armed(what));
            this.cancel.active = row != null && mine && row.num(1) <= 0L;
            label(this.cancel, "gui.neroeconomy.hub.auctions.cancel", row != null && armed("cancel|" + row.id()));
        }
        if (this.create != null) {
            String form = createForm();
            if (!this.armedFor.isEmpty() && !armed("create|" + form)) disarm();
            this.create.active = !form.isEmpty();
            label(this.create, "gui.neroeconomy.hub.auctions.create_button", !form.isEmpty() && armed("create|" + form));
        }
    }

    @Override
    protected void onSelect() {
        // The least next bid is the server's; it fills the box as a starting point.
        HubPage.Row row = selectedRow();
        if (row != null && this.amount != null && row.num(2) > 0L) this.amount.setValue(Long.toString(row.num(2)));
    }

    // --- drawing -----------------------------------------------------------------------------

    private Component fee() { return Component.literal(PriceText.percent((int) header(AuctionsPages.FEE_BPS)).replace("+", "")); }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable("gui.neroeconomy.hub.auctions.terms", fee(), header(AuctionsPages.INCREMENT),
                header(AuctionsPages.OPEN_MINE), header(AuctionsPages.MAX_AUCTIONS)), 8, 37, 240,
                TexturedContainerScreen.SUBTLE);
        if (view() == HubAction.AuctionsView.CREATE) {
            drawCreate(g);
            return;
        }
        drawRows(g, view() == HubAction.AuctionsView.MINE ? "gui.neroeconomy.hub.auctions.mine_empty"
                : "gui.neroeconomy.hub.auctions.empty", (row, top) -> {
                    s.icon(g, row.icon(), 10, top + 2);
                    boolean picked = row.id().equals(this.selected);
                    Component price = row.num(1) > 0L
                            ? Component.translatable("gui.neroeconomy.hub.auctions.top_bid", row.num(1))
                            : Component.translatable("gui.neroeconomy.hub.auctions.no_bid", row.num(2));
                    int priceWidth = s.width(price);
                    s.text(g, Component.translatable("gui.neroeconomy.hub.trade.items", row.num(0), s.itemName(row.icon())),
                            30, top + 2, 212 - priceWidth, picked ? TexturedContainerScreen.SELECTED
                                    : TexturedContainerScreen.TITLE);
                    s.textRight(g, price, 246, top + 2, TexturedContainerScreen.CREDIT);
                    Component who = row.flag(AuctionsPages.FLAG_HOUSE) ? Component.translatable(row.arg(0))
                            : Component.translatable(row.flag(AuctionsPages.FLAG_MINE) ? "gui.neroeconomy.hub.auctions.yours"
                                    : "gui.neroeconomy.hub.auctions.player");
                    Component second = Component.translatable(row.flag(AuctionsPages.FLAG_LEADING)
                            ? "gui.neroeconomy.hub.auctions.row2_leading" : "gui.neroeconomy.hub.auctions.row2", who,
                            timeLeft(row.num(3)), row.num(2));
                    s.text(g, second, 30, top + 11, 200, row.flag(AuctionsPages.FLAG_LEADING) ? EconomyHubScreen.GOOD
                            : row.flag(AuctionsPages.FLAG_HOUSE) ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
                    if (row.flag(AuctionsPages.FLAG_ORBIT)) s.textRight(g,
                            Component.translatable("gui.neroeconomy.hub.auctions.orbit_badge"), 246, top + 11,
                            TexturedContainerScreen.SUBTLE);
                });
        drawPageText(g, 54, 177);
        HubPage.Row row = selectedRow();
        long value = number(this.amount);
        Component line;
        int colour = EconomyHubScreen.WARNING;
        if (row != null && armed("bid|" + row.id() + "|" + value))
            line = Component.translatable("gui.neroeconomy.hub.auctions.confirm_bid", value, row.num(0),
                    s.itemName(row.icon()));
        else if (row != null && armed("cancel|" + row.id()))
            line = Component.translatable("gui.neroeconomy.hub.auctions.confirm_cancel", row.num(0), s.itemName(row.icon()));
        else {
            colour = TexturedContainerScreen.SUBTLE;
            line = Component.translatable(row == null ? "gui.neroeconomy.hub.auctions.pick"
                    : row.flag(AuctionsPages.FLAG_MINE) ? "gui.neroeconomy.hub.auctions.own_hint"
                    : "gui.neroeconomy.hub.auctions.bid_hint", row == null ? 0L : row.num(2));
        }
        s.wrapped(g, line, 8, 194, 240, colour);
    }

    private void drawCreate(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        ItemStack held = held();
        if (held.isEmpty()) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.auctions.hand_empty"), 8, 52, 240, EconomyHubScreen.WARNING);
        } else {
            String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            s.icon(g, id, 8, 48);
            s.text(g, Component.translatable("gui.neroeconomy.hub.auctions.hand", held.getCount(), s.itemName(id)), 28, 52,
                    220, TexturedContainerScreen.TITLE);
        }
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.auctions.fee_note", fee(), header(AuctionsPages.MAX_MINUTES)),
                8, 90, 240, TexturedContainerScreen.SUBTLE);
        String form = createForm();
        if (form.isEmpty()) {
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.auctions.create_hint", header(AuctionsPages.MAX_QUANTITY)),
                    8, 130, 240, TexturedContainerScreen.SUBTLE);
            return;
        }
        if (armed("create|" + form)) s.wrapped(g, Component.translatable("gui.neroeconomy.hub.auctions.confirm_create",
                number(this.quantity), number(this.minimum), number(this.minutes), fee()), 92, 194, 156,
                EconomyHubScreen.WARNING);
    }

    @Override
    Component tooltipAt(int x, int y) {
        HubPage.Row row = rowAt(x, y);
        if (row == null) return y >= 36 && y < 46 ? Component.translatable("gui.neroeconomy.hub.auctions.terms.tooltip") : null;
        return Component.translatable(row.flag(AuctionsPages.FLAG_HOUSE) ? "gui.neroeconomy.hub.auctions.house.tooltip"
                : "gui.neroeconomy.hub.auctions.row.tooltip");
    }
}
