package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubPages;

/**
 * The Market tab: search everything on offer (shops, the order book, the Merchant Houses), buy a
 * row, sell from the main hand or the vault, or place a buy order. Every price is the server's:
 * a row shows what this viewer would be charged, and a quote — asked of the server — comes before
 * every confirm, which restates what will be spent and received.
 *
 * <pre>
 *   20   search (8, 20, 118x14, sent a moment after typing stops) | Buy (130) | Sell (170) | Want (210), 38x14
 *   38   five rows of 20px: icon (10), item name (30), price right-aligned at 246; seller and stock under it
 *   140  previous / next (8, 140) | page text (54, 143)
 *   158  the mode's inputs        174  Quote (8, 174, 60x14) | confirm (72, 174, 76x14)
 *   192  the quote, or what to do next    202  more about the row or the item    212  the confirm line
 * </pre>
 */
final class HubMarketView extends HubTabView {
    private enum Mode { BUY, SELL, WANT }

    private static final int SEARCH_DELAY_TICKS = 8;

    private Mode mode = Mode.BUY;
    private boolean fromVault;
    private EditBox search;
    private EditBox quantity;
    private EditBox price;
    private EditBox item;
    private Button quoteButton;
    private Button confirmButton;
    private String sentSearch = "";
    private int searchDelay = -1;
    /** The inputs a quote was asked for; the page's quote only counts while they still match. */
    private String quotedFor = "";

    HubMarketView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listY = 38;
        this.listW = 240;
        this.rowH = 20;
        this.visibleRows = HubPages.MARKET_ROWS;
    }

    private boolean vaultList() { return this.page.number(HubPages.MODE, 0L) == HubAction.MarketMode.VAULT.ordinal(); }

    @Override
    void init() {
        this.search = this.screen.box(this.search, "gui.neroeconomy.hub.market.search", 8, 20, 118, 64);
        this.search.setResponder(text -> this.searchDelay = SEARCH_DELAY_TICKS);
        modeButton(Mode.BUY, "gui.neroeconomy.hub.market.mode.buy", 130);
        modeButton(Mode.SELL, "gui.neroeconomy.hub.market.mode.sell", 170);
        modeButton(Mode.WANT, "gui.neroeconomy.hub.market.mode.want", 210);
        pageButtons(8, 140);
        switch (this.mode) {
            case BUY -> this.quantity = this.screen.box(this.quantity, "gui.neroeconomy.hub.market.quantity", 8, 158, 48, 4);
            case SELL -> {
                this.screen.button(Component.translatable(this.fromVault ? "gui.neroeconomy.hub.market.from_vault"
                        : "gui.neroeconomy.hub.market.from_hand"), "gui.neroeconomy.hub.market.source.tooltip", 8, 158,
                        72, 14, () -> {
                            this.fromVault = !this.fromVault;
                            this.selected = null;
                            this.intents.send(HubAction.MARKET_MODE, null, this.fromVault ? 1L : 0L, 0L, "");
                            this.screen.rebuild();
                        });
                this.quantity = this.screen.box(this.quantity, "gui.neroeconomy.hub.market.quantity", 84, 158, 48, 4);
                this.price = this.screen.box(this.price, "gui.neroeconomy.hub.market.price", 136, 158, 60, 13);
            }
            default -> {
                this.item = this.screen.box(this.item, "gui.neroeconomy.hub.market.item", 8, 158, 120, 200);
                this.quantity = this.screen.box(this.quantity, "gui.neroeconomy.hub.market.quantity", 132, 158, 48, 4);
                this.price = this.screen.box(this.price, "gui.neroeconomy.hub.market.price", 184, 158, 64, 13);
            }
        }
        this.quoteButton = this.screen.button(Component.translatable("gui.neroeconomy.hub.market.quote"),
                "gui.neroeconomy.hub.market.quote.tooltip", 8, 174, 60, 14, this::quote);
        this.confirmButton = this.screen.button(Component.empty(), confirmTooltip(), 72, 174, 76, 14, this::confirm);
    }

    private void modeButton(Mode target, String key, int x) {
        Button button = this.screen.button(Component.translatable(key), key + ".tooltip", x, 20, 38, 14, () -> {
            boolean vault = target == Mode.SELL && this.fromVault;
            if (vault != vaultList()) this.intents.send(HubAction.MARKET_MODE, null, vault ? 1L : 0L, 0L, "");
            this.mode = target;
            this.selected = null;
            disarm();
            this.screen.rebuild();
        });
        button.active = this.mode != target;
    }

    private String confirmKey() {
        return switch (this.mode) {
            case BUY -> "gui.neroeconomy.hub.market.buy";
            case SELL -> "gui.neroeconomy.hub.market.sell";
            default -> "gui.neroeconomy.hub.market.place";
        };
    }

    private String confirmTooltip() { return confirmKey() + ".tooltip"; }

    // --- inputs and quotes -------------------------------------------------------------------

    /** Everything the next quote depends on; a quote or a confirm is only good for exactly this. */
    private String inputs() {
        return switch (this.mode) {
            case BUY -> "B|" + this.selected + "|" + number(this.quantity);
            case SELL -> "S|" + (this.fromVault ? String.valueOf(this.selected) : "hand") + "|" + number(this.quantity)
                    + "|" + number(this.price);
            default -> "W|" + value(this.item) + "|" + number(this.quantity) + "|" + number(this.price);
        };
    }

    private HubAction quoteAction() {
        return switch (this.mode) {
            case BUY -> HubAction.MARKET_QUOTE;
            case SELL -> HubAction.MARKET_SELL_QUOTE;
            default -> HubAction.MARKET_BID_QUOTE;
        };
    }

    private boolean inputsComplete() {
        return switch (this.mode) {
            case BUY -> selectedRow() != null && !vaultList() && number(this.quantity) > 0L;
            case SELL -> (!this.fromVault || (selectedRow() != null && vaultList())) && number(this.quantity) > 0L
                    && number(this.price) > 0L;
            default -> !value(this.item).isEmpty() && number(this.quantity) > 0L && number(this.price) > 0L;
        };
    }

    /** Whether the page carries a quote for exactly what is typed and picked now. */
    private boolean quoteShown() {
        return !this.page.key(HubPages.QUOTE).isEmpty() && this.quotedFor.equals(inputs())
                && this.page.number(HubPages.QUOTE_ACTION, -1L) == quoteAction().ordinal();
    }

    private boolean quoteOk() { return quoteShown() && this.page.number(HubPages.QUOTE_OK, 0L) == 1L; }

    private void quote() {
        if (!inputsComplete()) return;
        this.quotedFor = inputs();
        disarm();
        switch (this.mode) {
            case BUY -> this.intents.send(HubAction.MARKET_QUOTE, this.selected, number(this.quantity), 0L, "");
            case SELL -> this.intents.send(HubAction.MARKET_SELL_QUOTE, this.fromVault ? this.selected : null,
                    number(this.quantity), number(this.price), "");
            default -> this.intents.send(HubAction.MARKET_BID_QUOTE, null, number(this.quantity), number(this.price),
                    value(this.item));
        }
    }

    private void confirm() {
        if (!quoteOk()) return;
        String what = inputs();
        press(what, () -> {
            switch (this.mode) {
                case BUY -> this.intents.send(HubAction.MARKET_BUY, this.selected, number(this.quantity),
                        this.page.number(HubPages.QUOTE_UNIT, 0L), "");
                case SELL -> this.intents.send(HubAction.MARKET_SELL, this.fromVault ? this.selected : null,
                        number(this.quantity), number(this.price), "");
                default -> this.intents.send(HubAction.MARKET_BID, null, number(this.quantity), number(this.price),
                        value(this.item));
            }
            this.quotedFor = "";
        });
    }

    @Override
    protected void onSelect() {
        // Wanting what a row offers: its item fills the item box.
        HubPage.Row row = selectedRow();
        if (this.mode == Mode.WANT && row != null && this.item != null) this.item.setValue(row.icon());
    }

    @Override
    void tick() {
        if (this.searchDelay > 0 && --this.searchDelay == 0) {
            String text = value(this.search);
            if (!text.equals(this.sentSearch)) {
                this.sentSearch = text;
                this.selected = null;
                this.intents.send(HubAction.MARKET_SEARCH, null, 0L, 0L, text);
            }
        }
        if (!this.armedFor.isEmpty() && !armed(inputs())) disarm();
        if (this.quoteButton != null) this.quoteButton.active = inputsComplete();
        if (this.confirmButton != null) {
            this.confirmButton.active = quoteOk();
            label(this.confirmButton, confirmKey(), armed(inputs()));
        }
    }

    // --- drawing -----------------------------------------------------------------------------

    private Component quoteLine() {
        return Component.translatable(this.page.key(HubPages.QUOTE), this.page.number(HubPages.QUOTE_TOTAL, 0L),
                this.page.number(HubPages.QUOTE_QTY, 0L), this.page.number(HubPages.QUOTE_UNIT, 0L),
                this.page.number(HubPages.QUOTE_FEE, 0L), this.page.number(HubPages.QUOTE_NET, 0L),
                this.page.number(HubPages.QUOTE_LISTING, 0L));
    }

    private Component hint() {
        HubPage.Row row = selectedRow();
        return switch (this.mode) {
            case BUY -> row == null ? Component.translatable("gui.neroeconomy.hub.market.hint.buy_pick")
                    : row.flag(HubPage.FLAG_A) ? Component.translatable("gui.neroeconomy.hub.market.hint.own")
                    : Component.translatable("gui.neroeconomy.hub.market.hint.buy_quote");
            case SELL -> this.fromVault && row == null
                    ? Component.translatable("gui.neroeconomy.hub.market.hint.sell_pick")
                    : Component.translatable("gui.neroeconomy.hub.market.hint.sell_quote");
            default -> Component.translatable("gui.neroeconomy.hub.market.hint.want");
        };
    }

    /** The second line: about the picked row, where the goods come from, or the item typed. */
    private Component detail() {
        HubPage.Row row = selectedRow();
        switch (this.mode) {
            case BUY -> {
                if (row == null || !row.flag(HubPage.FLAG_B)) return Component.empty();
                return Component.translatable("gui.neroeconomy.hub.market.commodity", price(row.num(2)),
                        price(row.num(3)), row.num(4), row.num(5));
            }
            case SELL -> {
                if (!this.fromVault) return Component.translatable("gui.neroeconomy.hub.market.selling_hand");
                return row == null ? Component.empty()
                        : Component.translatable("gui.neroeconomy.hub.market.selling_vault", row.num(0),
                                this.screen.itemName(row.icon()));
            }
            default -> {
                String id = value(this.item);
                if (id.isEmpty()) return Component.empty();
                Identifier key = Identifier.tryParse(id);
                var found = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
                return found == null || found == Items.AIR
                        ? Component.translatable("gui.neroeconomy.hub.market.item_unknown")
                        : Component.translatable("gui.neroeconomy.hub.market.item_ok", this.screen.itemName(id));
            }
        }
    }

    /** A price, or a dash where there is none. */
    private static Component price(long value) {
        return value > 0L ? Component.translatable("gui.neroeconomy.hub.credits", value)
                : Component.translatable("gui.neroeconomy.hub.none");
    }

    private Component confirmLine() {
        long total = this.page.number(HubPages.QUOTE_TOTAL, 0L);
        long qty = this.page.number(HubPages.QUOTE_QTY, 0L);
        return switch (this.mode) {
            case BUY -> Component.translatable("gui.neroeconomy.hub.market.confirm.buy", total, qty,
                    this.page.number(HubPages.QUOTE_VAULT, 0L) == 1L
                            ? Component.translatable("gui.neroeconomy.hub.market.to_vault")
                            : Component.translatable("gui.neroeconomy.hub.market.to_inventory"));
            case SELL -> Component.translatable("gui.neroeconomy.hub.market.confirm.sell", qty,
                    this.page.number(HubPages.QUOTE_UNIT, 0L), this.page.number(HubPages.QUOTE_LISTING, 0L),
                    this.page.number(HubPages.QUOTE_NET, 0L));
            default -> Component.translatable("gui.neroeconomy.hub.market.confirm.want",
                    this.page.number(HubPages.QUOTE_NET, 0L), this.page.number(HubPages.QUOTE_LISTING, 0L), qty);
        };
    }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        drawSelection(g);
        boolean vault = vaultList();
        if (this.page.rows().isEmpty()) {
            s.wrapped(g, Component.translatable(vault ? "gui.neroeconomy.hub.market.vault_empty"
                    : "gui.neroeconomy.hub.market.empty"), 10, 44, 236, TexturedContainerScreen.SUBTLE);
        }
        for (int index = 0; index < this.page.rows().size() && index < this.visibleRows; index++) {
            HubPage.Row row = this.page.rows().get(index);
            int y = this.listY + index * this.rowH;
            boolean picked = row.id().equals(this.selected);
            s.icon(g, row.icon(), 10, y + 2);
            Component name = row.flag(HubPage.FLAG_C)
                    ? Component.translatable("gui.neroeconomy.hub.modified", s.itemName(row.icon()))
                    : s.itemName(row.icon());
            if (vault) {
                s.text(g, name, 30, y + 6, 160, picked ? TexturedContainerScreen.SELECTED : TexturedContainerScreen.TITLE);
                s.textRight(g, Component.translatable("gui.neroeconomy.hub.vault.count", row.num(0)), 246, y + 6,
                        TexturedContainerScreen.SUBTLE);
                continue;
            }
            Component unit = Component.translatable("gui.neroeconomy.hub.market.each", row.num(0));
            int unitWidth = s.width(unit);
            s.text(g, name, 30, y + 2, 212 - unitWidth, picked ? TexturedContainerScreen.SELECTED
                    : TexturedContainerScreen.TITLE);
            s.textRight(g, unit, 246, y + 2, TexturedContainerScreen.CREDIT);
            s.text(g, Component.translatable(row.flag(HubPage.FLAG_A) ? "gui.neroeconomy.hub.market.row_own"
                    : "gui.neroeconomy.hub.market.row", EconomyHubScreen.arg(row.title()), row.num(1)), 30, y + 11,
                    214, TexturedContainerScreen.SUBTLE);
        }
        drawPageText(g, 54, 143);
        if (this.mode == Mode.BUY) s.text(g, Component.translatable("gui.neroeconomy.hub.market.quantity"), 60, 161,
                TexturedContainerScreen.SUBTLE);
        boolean shown = quoteShown();
        s.text(g, shown ? quoteLine() : hint(), 8, 192, 240, shown
                ? (quoteOk() ? TexturedContainerScreen.CREDIT : EconomyHubScreen.BAD) : TexturedContainerScreen.SUBTLE);
        s.text(g, detail(), 8, 202, 240, TexturedContainerScreen.SUBTLE);
        if (armed(inputs()) && quoteOk()) s.text(g, confirmLine(), 8, 212, 240, EconomyHubScreen.WARNING);
    }

    @Override
    Component tooltipAt(int x, int y) {
        HubPage.Row row = rowAt(x, y);
        if (row == null) return null;
        if (vaultList()) return row.flag(HubPage.FLAG_C) ? Component.translatable("gui.neroeconomy.hub.modified.tooltip")
                : Component.translatable("gui.neroeconomy.hub.market.vault_row.tooltip");
        Component seller = Component.translatable("gui.neroeconomy.hub.market.row.tooltip", EconomyHubScreen.arg(row.title()),
                row.num(0), row.num(1));
        if (!row.flag(HubPage.FLAG_B)) return seller;
        return Component.translatable("gui.neroeconomy.hub.market.row.tooltip_commodity", seller, price(row.num(2)),
                price(row.num(3)), row.num(4), row.num(5));
    }
}
