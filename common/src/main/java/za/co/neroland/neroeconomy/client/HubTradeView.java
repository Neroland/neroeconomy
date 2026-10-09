package za.co.neroland.neroeconomy.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.OfferForm;
import za.co.neroland.neroeconomy.hub.TradePages;

/**
 * The Trade tab: offers sent to the player and offers they sent (each from or to "a player": the
 * offer rules never name the other side), the "Send offer" form, the mute switch, and asking a
 * player standing near for a direct trade. The players near enough are the ones this client can
 * see itself within the range the server sent; their names never come from a page.
 *
 * <pre>
 *   20   Waiting (8, 52) | Sent (62, 44) | Send offer (108, 64) | Trade nearby (174, 74)
 *   36   whether offers are muted, and Mute / Unmute (188, 34, 60x12)
 *   INBOX, SENT  52: six rows of 20px | 176: page buttons | Accept (150, 48) Decline (200, 48) or
 *                Take back (150, 98) | 196, 206: the confirm lines
 *   SEND    52: six vault rows of 14px | 140: page buttons | Give from (150, 140, 98x14) | 158: To,
 *           count, credits | 176: ask item, count, credits | 194: Send (8, 194, 80x14) | 212: the confirm
 *   NEARBY  52: up to eight names of 14px | 176: Ask to trade (8, 176, 100x14) | 196: what happens next
 * </pre>
 */
final class HubTradeView extends HubTabView {
    private static final int NEARBY_ROWS = 8;
    private static final int NEARBY_TOP = 52;
    private static final int NEARBY_ROW = 14;

    private OfferForm.Source source = OfferForm.Source.HAND;
    private EditBox recipient;
    private EditBox giveCount;
    private EditBox giveCredits;
    private EditBox askItem;
    private EditBox askCount;
    private EditBox askCredits;
    private Button sendButton;
    private Button acceptButton;
    private Button declineButton;
    private Button cancelButton;
    private Button askButton;
    private final List<String> nearby = new ArrayList<>();
    private String nearbyPicked = "";
    private int nearbyRefresh;
    /** A demo's made-up names, shown instead of the players this client sees; null for the live tab. */
    private List<String> sampleNearby;

    HubTradeView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listW = 240;
        layout();
    }

    private HubAction.TradeView view() { return HubAction.TradeView.byOrdinal(header(TradePages.VIEW)); }

    private void layout() {
        this.listY = 52;
        switch (view()) {
            case INBOX, OUTBOX -> {
                this.rowH = 20;
                this.visibleRows = TradePages.OFFER_ROWS;
            }
            case SEND -> {
                this.rowH = 14;
                this.visibleRows = TradePages.VAULT_ROWS;
            }
            default -> this.visibleRows = 0;
        }
    }

    @Override
    void accept(HubPage next) {
        HubAction.TradeView was = view();
        super.accept(next);
        if (was != view()) {
            this.selected = null;
            disarm();
            layout();
            this.screen.rebuild();
        }
    }

    private void tab(HubAction.TradeView target, String key, int x, int w) {
        Button button = this.screen.button(Component.translatable(key, header(TradePages.INCOMING)), key + ".tooltip", x,
                20, w, 14, () -> this.intents.send(HubAction.TRADE_VIEW, null, target.ordinal(), 0L, ""));
        button.active = view() != target;
    }

    @Override
    void init() {
        layout();
        tab(HubAction.TradeView.INBOX, "gui.neroeconomy.hub.trade.inbox", 8, 52);
        tab(HubAction.TradeView.OUTBOX, "gui.neroeconomy.hub.trade.outbox", 62, 44);
        tab(HubAction.TradeView.SEND, "gui.neroeconomy.hub.trade.send_tab", 108, 64);
        tab(HubAction.TradeView.DIRECT, "gui.neroeconomy.hub.trade.direct", 174, 74);
        boolean muted = header(TradePages.MUTED) == 1L;
        this.screen.button(Component.translatable(muted ? "gui.neroeconomy.hub.trade.unmute" : "gui.neroeconomy.hub.trade.mute"),
                "gui.neroeconomy.hub.trade.mute.tooltip", 188, 35, 60, 12,
                () -> this.intents.send(HubAction.OFFER_MUTE, null, header(TradePages.MUTED) == 1L ? 0L : 1L, 0L, ""));
        this.sendButton = null;
        this.acceptButton = null;
        this.declineButton = null;
        this.cancelButton = null;
        this.askButton = null;
        switch (view()) {
            case INBOX -> {
                pageButtons(8, 176);
                this.acceptButton = this.screen.button(Component.empty(), "gui.neroeconomy.hub.trade.accept.tooltip", 150,
                        176, 48, 14, () -> answer(true));
                this.declineButton = this.screen.button(Component.empty(), "gui.neroeconomy.hub.trade.decline.tooltip",
                        200, 176, 48, 14, () -> answer(false));
            }
            case OUTBOX -> {
                pageButtons(8, 176);
                this.cancelButton = this.screen.button(Component.empty(), "gui.neroeconomy.hub.trade.cancel.tooltip", 150,
                        176, 98, 14, () -> {
                            HubPage.Row row = selectedRow();
                            if (row != null) press("cancel|" + row.id(),
                                    () -> this.intents.send(HubAction.OFFER_CANCEL, row.id(), 0L, 0L, ""));
                        });
            }
            case SEND -> {
                pageButtons(8, 140);
                this.screen.button(Component.translatable("gui.neroeconomy.hub.trade.source."
                        + this.source.name().toLowerCase(java.util.Locale.ROOT)), "gui.neroeconomy.hub.trade.source.tooltip",
                        150, 140, 98, 14, () -> {
                            OfferForm.Source[] all = OfferForm.Source.values();
                            this.source = all[(this.source.ordinal() + 1) % all.length];
                            if (this.source != OfferForm.Source.VAULT) this.selected = null;
                            disarm();
                            this.screen.rebuild();
                        });
                this.recipient = this.screen.box(this.recipient, "gui.neroeconomy.hub.trade.recipient", 8, 158, 70, 64);
                this.giveCount = this.screen.box(this.giveCount, "gui.neroeconomy.hub.trade.give_count", 82, 158, 36, 4);
                this.giveCount.active = this.source != OfferForm.Source.NONE;
                this.giveCredits = this.screen.box(this.giveCredits, "gui.neroeconomy.hub.trade.give_credits", 122, 158,
                        62, 13);
                this.askItem = this.screen.box(this.askItem, "gui.neroeconomy.hub.trade.ask_item", 8, 176, 110, 128);
                this.askCount = this.screen.box(this.askCount, "gui.neroeconomy.hub.trade.ask_count", 122, 176, 40, 4);
                this.askCredits = this.screen.box(this.askCredits, "gui.neroeconomy.hub.trade.ask_credits", 166, 176, 82,
                        13);
                this.sendButton = this.screen.button(Component.empty(), "gui.neroeconomy.hub.trade.send.tooltip", 8, 194,
                        80, 14, this::send);
            }
            default -> this.askButton = this.screen.button(Component.translatable("gui.neroeconomy.hub.trade.ask"),
                    "gui.neroeconomy.hub.trade.ask.tooltip", 8, 176, 100, 14, () -> {
                        if (!this.nearbyPicked.isEmpty())
                            this.intents.send(HubAction.TRADE_REQUEST, null, 0L, 0L, this.nearbyPicked);
                    });
        }
    }

    private void answer(boolean accept) {
        HubPage.Row row = selectedRow();
        if (row == null) return;
        if (accept) press("accept|" + row.id(), () -> this.intents.send(HubAction.OFFER_ACCEPT, row.id(), 0L, 0L, ""));
        else press("decline|" + row.id(), () -> this.intents.send(HubAction.OFFER_DECLINE, row.id(), 0L, 0L, ""));
    }

    // --- the send form -----------------------------------------------------------------------

    /** A whole number of at least 0 from a box that may be left empty; -1 for anything else. */
    private static long optional(EditBox box) {
        String text = value(box);
        if (text.isEmpty()) return 0L;
        try {
            long value = Long.parseLong(text);
            return value < 0L ? -1L : value;
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    /** The form as typed, or null when it is not complete or not valid. */
    private OfferForm form() {
        long count = this.source == OfferForm.Source.NONE ? 0L : optional(this.giveCount);
        String text = value(this.recipient) + ';' + this.source.name().charAt(0) + ';' + count + ';'
                + optional(this.giveCredits) + ';' + value(this.askItem) + ';' + optional(this.askCount) + ';'
                + optional(this.askCredits);
        OfferForm form = OfferForm.parse(text);
        if (form == null) return null;
        if (form.source() == OfferForm.Source.VAULT && selectedRow() == null) return null;
        return form;
    }

    private void send() {
        OfferForm form = form();
        if (form == null) return;
        press("send|" + form.encode() + "|" + this.selected, () -> {
            HubPage.Row row = form.source() == OfferForm.Source.VAULT ? selectedRow() : null;
            this.intents.send(HubAction.OFFER_SEND, row == null ? null : row.id(), 0L, 0L, form.encode());
        });
    }

    @Override
    boolean click(int x, int y) {
        if (view() == HubAction.TradeView.DIRECT) {
            int index = (y - NEARBY_TOP) / NEARBY_ROW;
            if (x < 8 || x >= 248 || y < NEARBY_TOP || index >= this.nearby.size()) return false;
            String name = this.nearby.get(index);
            this.nearbyPicked = name.equals(this.nearbyPicked) ? "" : name;
            return true;
        }
        if (view() == HubAction.TradeView.SEND && this.source != OfferForm.Source.VAULT) return false;
        return super.click(x, y);
    }

    /** For a demo: made-up names to list instead of the players near, so no real player is shown. */
    void sampleNearby(List<String> names) {
        this.sampleNearby = List.copyOf(names);
        this.nearbyRefresh = 0;
    }

    /** The players this client sees within the trade range, nearest first. */
    private void refreshNearby() {
        this.nearby.clear();
        if (this.sampleNearby != null) {
            for (String name : this.sampleNearby) if (this.nearby.size() < NEARBY_ROWS) this.nearby.add(name);
            if (!this.nearby.contains(this.nearbyPicked)) this.nearbyPicked = "";
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player self = minecraft.player;
        if (self == null || minecraft.level == null) return;
        double range = header(TradePages.RANGE);
        List<Player> found = new ArrayList<>();
        for (Player other : minecraft.level.players())
            if (other != self && !other.isSpectator() && other.distanceToSqr(self) <= range * range) found.add(other);
        found.sort(Comparator.comparingDouble(other -> other.distanceToSqr(self)));
        for (Player other : found) {
            if (this.nearby.size() >= NEARBY_ROWS) break;
            this.nearby.add(other.getScoreboardName());
        }
        if (!this.nearby.contains(this.nearbyPicked)) this.nearbyPicked = "";
    }

    @Override
    void tick() {
        HubPage.Row row = selectedRow();
        if (!this.armedFor.isEmpty() && (row == null || !this.armedFor.endsWith(String.valueOf(row.id())))
                && !this.armedFor.startsWith("send|")) disarm();
        if (this.acceptButton != null) {
            this.acceptButton.active = row != null;
            this.declineButton.active = row != null;
            label(this.acceptButton, "gui.neroeconomy.hub.trade.accept", row != null && armed("accept|" + row.id()));
            label(this.declineButton, "gui.neroeconomy.hub.trade.decline", row != null && armed("decline|" + row.id()));
        }
        if (this.cancelButton != null) {
            this.cancelButton.active = row != null;
            label(this.cancelButton, "gui.neroeconomy.hub.trade.cancel", row != null && armed("cancel|" + row.id()));
        }
        if (this.sendButton != null) {
            OfferForm form = form();
            String what = form == null ? "" : "send|" + form.encode() + "|" + this.selected;
            if (this.armedFor.startsWith("send|") && !armed(what)) disarm();
            this.sendButton.active = form != null;
            label(this.sendButton, "gui.neroeconomy.hub.trade.send", !what.isEmpty() && armed(what));
        }
        if (view() == HubAction.TradeView.DIRECT && --this.nearbyRefresh <= 0) {
            this.nearbyRefresh = 20;
            refreshNearby();
        }
        if (this.askButton != null) this.askButton.active = !this.nearbyPicked.isEmpty();
    }

    // --- drawing -----------------------------------------------------------------------------

    /** What an offer gives, in plain words. */
    private Component gives(HubPage.Row row) {
        Component items = row.num(0) <= 0L ? Component.translatable("gui.neroeconomy.hub.trade.no_items")
                : row.num(1) > 1L ? Component.translatable("gui.neroeconomy.hub.trade.items_more", row.num(0),
                        this.screen.itemName(row.icon()), row.num(1) - 1L)
                : Component.translatable("gui.neroeconomy.hub.trade.items", row.num(0), this.screen.itemName(row.icon()));
        return row.num(2) > 0L ? Component.translatable("gui.neroeconomy.hub.trade.plus_credits", items, row.num(2)) : items;
    }

    /** What an offer asks in return. */
    private Component asks(HubPage.Row row) {
        boolean items = row.flag(TradePages.FLAG_ASKS_ITEMS);
        long credits = row.num(4);
        if (!items && credits <= 0L) return Component.translatable("gui.neroeconomy.hub.trade.nothing");
        Component goods = items ? Component.translatable("gui.neroeconomy.hub.trade.items", row.num(3),
                this.screen.itemName(row.arg(0))) : null;
        if (goods == null) return Component.translatable("gui.neroeconomy.hub.credits", credits);
        return credits > 0L ? Component.translatable("gui.neroeconomy.hub.trade.plus_credits", goods, credits) : goods;
    }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        boolean muted = header(TradePages.MUTED) == 1L;
        s.text(g, Component.translatable(muted ? "gui.neroeconomy.hub.trade.muted" : "gui.neroeconomy.hub.trade.open",
                header(TradePages.OUTGOING), header(TradePages.MAX_OFFERS)), 8, 37, 178,
                muted ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
        switch (view()) {
            case INBOX, OUTBOX -> drawOffers(g, view() == HubAction.TradeView.INBOX);
            case SEND -> drawSend(g);
            default -> drawNearby(g);
        }
    }

    private void drawOffers(GuiGraphicsExtractor g, boolean inbox) {
        EconomyHubScreen s = this.screen;
        drawRows(g, inbox ? "gui.neroeconomy.hub.trade.inbox_empty" : "gui.neroeconomy.hub.trade.outbox_empty",
                (row, top) -> {
                    s.icon(g, row.icon().isEmpty() ? "neroeconomy:credit_chip" : row.icon(), 10, top + 2);
                    boolean picked = row.id().equals(this.selected);
                    s.text(g, Component.translatable(inbox ? "gui.neroeconomy.hub.trade.gives_in"
                            : "gui.neroeconomy.hub.trade.gives_out", gives(row)), 30, top + 2, 216,
                            picked ? TexturedContainerScreen.SELECTED : TexturedContainerScreen.TITLE);
                    s.text(g, Component.translatable("gui.neroeconomy.hub.trade.asks", asks(row), timeLeft(row.num(5))), 30,
                            top + 11, 216, TexturedContainerScreen.SUBTLE);
                });
        drawPageText(g, 54, 179);
        HubPage.Row row = selectedRow();
        if (row == null) {
            s.text(g, Component.translatable(inbox ? "gui.neroeconomy.hub.trade.pick_in" : "gui.neroeconomy.hub.trade.pick_out"),
                    8, 196, 240, TexturedContainerScreen.SUBTLE);
            return;
        }
        Component line;
        int colour = EconomyHubScreen.WARNING;
        if (inbox && armed("accept|" + row.id())) line = Component.translatable("gui.neroeconomy.hub.trade.confirm_accept",
                gives(row), asks(row), Component.literal(za.co.neroland.neroeconomy.exchange.PriceText.percent(
                        (int) header(TradePages.TAX_BPS)).replace("+", "")));
        else if (inbox && armed("decline|" + row.id()))
            line = Component.translatable("gui.neroeconomy.hub.trade.confirm_decline");
        else if (!inbox && armed("cancel|" + row.id()))
            line = Component.translatable("gui.neroeconomy.hub.trade.confirm_cancel", gives(row));
        else {
            line = Component.translatable(inbox ? "gui.neroeconomy.hub.trade.answer_hint" : "gui.neroeconomy.hub.trade.cancel_hint");
            colour = TexturedContainerScreen.SUBTLE;
        }
        s.wrapped(g, line, 8, 196, 240, colour);
    }

    private void drawSend(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        if (this.source == OfferForm.Source.VAULT) {
            drawRows(g, "gui.neroeconomy.hub.trade.vault_empty", (row, top) -> {
                s.icon(g, row.icon(), 10, top - 1);
                boolean picked = row.id().equals(this.selected);
                Component name = row.flag(TradePages.FLAG_MODIFIED)
                        ? Component.translatable("gui.neroeconomy.hub.modified", s.itemName(row.icon()))
                        : s.itemName(row.icon());
                s.text(g, name, 30, top + 3, 170, picked ? TexturedContainerScreen.SELECTED : TexturedContainerScreen.TITLE);
                s.textRight(g, Component.translatable("gui.neroeconomy.hub.vault.count", row.num(0)), 246, top + 3,
                        TexturedContainerScreen.SUBTLE);
            });
            drawPageText(g, 54, 143);
        } else {
            s.wrapped(g, Component.translatable(this.source == OfferForm.Source.HAND
                    ? "gui.neroeconomy.hub.trade.from_hand_hint" : "gui.neroeconomy.hub.trade.credits_only_hint"), 8, 56,
                    240, TexturedContainerScreen.SUBTLE);
        }
        s.text(g, Component.translatable("gui.neroeconomy.hub.trade.form_caption", header(TradePages.HOURS)), 8, 128,
                240, TexturedContainerScreen.SUBTLE);
        OfferForm form = form();
        if (form == null) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.trade.form_hint"), 92, 197, 156,
                    TexturedContainerScreen.SUBTLE);
            return;
        }
        String what = "send|" + form.encode() + "|" + this.selected;
        HubPage.Row row = selectedRow();
        Component goods = form.count() == 0 ? Component.translatable("gui.neroeconomy.hub.trade.no_items")
                : form.source() == OfferForm.Source.VAULT && row != null
                        ? Component.translatable("gui.neroeconomy.hub.trade.items", form.count(), s.itemName(row.icon()))
                        : Component.translatable("gui.neroeconomy.hub.trade.items_hand", form.count());
        Component asked = form.askCount() > 0
                ? Component.translatable("gui.neroeconomy.hub.trade.items", form.askCount(), s.itemName(form.askItem()))
                : Component.translatable("gui.neroeconomy.hub.trade.nothing");
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.trade.confirm_send", form.recipient(), goods,
                form.credits(), asked, form.askCredits()), 92, 196, 156,
                armed(what) ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
    }

    private void drawNearby(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable("gui.neroeconomy.hub.trade.nearby", header(TradePages.RANGE)), 8, 44, 240,
                TexturedContainerScreen.SUBTLE);
        if (this.nearby.isEmpty()) s.text(g, Component.translatable("gui.neroeconomy.hub.trade.nobody"), 10,
                NEARBY_TOP + 4, 236, TexturedContainerScreen.SUBTLE);
        for (int index = 0; index < this.nearby.size(); index++) {
            String name = this.nearby.get(index);
            int y = NEARBY_TOP + index * NEARBY_ROW;
            boolean picked = name.equals(this.nearbyPicked);
            if (picked) s.fill(g, 8, y, 240, NEARBY_ROW - 1, 0x4030D6BE);
            s.text(g, Component.literal(name), 12, y + 3, 230, picked ? TexturedContainerScreen.SELECTED
                    : TexturedContainerScreen.TITLE);
        }
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.trade.direct_hint"), 8, 196, 240,
                TexturedContainerScreen.SUBTLE);
    }

    @Override
    Component tooltipAt(int x, int y) {
        if (view() == HubAction.TradeView.DIRECT) {
            int index = (y - NEARBY_TOP) / NEARBY_ROW;
            return y >= NEARBY_TOP && x >= 8 && x < 248 && index < this.nearby.size()
                    ? Component.translatable("gui.neroeconomy.hub.trade.nearby.tooltip") : null;
        }
        HubPage.Row row = rowAt(x, y);
        if (row == null) return y >= 36 && y < 46 && x < 186 ? Component.translatable("gui.neroeconomy.hub.trade.state.tooltip") : null;
        return switch (view()) {
            case INBOX -> Component.translatable("gui.neroeconomy.hub.trade.offer_in.tooltip");
            case OUTBOX -> Component.translatable("gui.neroeconomy.hub.trade.offer_out.tooltip");
            default -> Component.translatable("gui.neroeconomy.hub.trade.vault_row.tooltip");
        };
    }
}
