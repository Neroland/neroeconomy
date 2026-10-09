package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.ShopPages;

/**
 * The Shop tab: the player's own Player Shops — where each stands, what it sells, its stock, its
 * price and how long it has been idle — with a new price for a shop whose chunk is loaded, and the
 * shop's own window for one within reach.
 *
 * <pre>
 *   20   how many shops, and after how many idle days one closes (two lines)
 *   44   six rows of 20px | 168: page buttons | price (92, 168, 54) | Set price (150, 168, 98x14)
 *   186  Open shop (150, 186, 98x14)      204: the confirm line
 * </pre>
 */
final class HubShopView extends HubTabView {
    private EditBox price;
    private Button setPrice;
    private Button open;

    HubShopView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listY = 44;
        this.listW = 240;
        this.rowH = 20;
        this.visibleRows = ShopPages.SHOP_ROWS;
    }

    @Override
    void init() {
        pageButtons(8, 168);
        this.price = this.screen.box(this.price, "gui.neroeconomy.hub.shop.price", 92, 168, 54, 10);
        this.setPrice = this.screen.button(Component.empty(), "gui.neroeconomy.hub.shop.set_price.tooltip", 150, 168, 98,
                14, () -> {
                    HubPage.Row row = selectedRow();
                    long value = number(this.price);
                    if (row == null || value <= 0L) return;
                    press("price|" + row.id() + "|" + value,
                            () -> this.intents.send(HubAction.SHOP_PRICE, row.id(), value, 0L, ""));
                });
        this.open = this.screen.button(Component.translatable("gui.neroeconomy.hub.shop.open"),
                "gui.neroeconomy.hub.shop.open.tooltip", 150, 186, 98, 14, () -> {
                    HubPage.Row row = selectedRow();
                    if (row != null) this.intents.send(HubAction.SHOP_OPEN, row.id(), 0L, 0L, "");
                });
    }

    @Override
    protected void onSelect() {
        HubPage.Row row = selectedRow();
        if (row != null && this.price != null && row.num(4) > 0L) this.price.setValue(Long.toString(row.num(4)));
    }

    @Override
    void tick() {
        HubPage.Row row = selectedRow();
        long value = number(this.price);
        String what = row == null ? "" : "price|" + row.id() + "|" + value;
        if (!this.armedFor.isEmpty() && !armed(what)) disarm();
        if (this.setPrice != null) {
            this.setPrice.active = row != null && row.flag(ShopPages.FLAG_LOADED) && value > 0L;
            label(this.setPrice, "gui.neroeconomy.hub.shop.set_price", !what.isEmpty() && armed(what));
        }
        if (this.open != null) this.open.active = row != null && row.flag(ShopPages.FLAG_IN_REACH);
    }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        long idle = header(ShopPages.MAX_IDLE);
        s.wrapped(g, idle > 0L ? Component.translatable("gui.neroeconomy.hub.shop.summary", header(ShopPages.COUNT), idle)
                : Component.translatable("gui.neroeconomy.hub.shop.summary_no_idle", header(ShopPages.COUNT)), 8, 21, 240,
                TexturedContainerScreen.SUBTLE);
        drawRows(g, "gui.neroeconomy.hub.shop.empty", (row, top) -> {
            s.icon(g, row.icon(), 10, top + 2);
            boolean picked = row.id().equals(this.selected);
            boolean empty = row.flag(ShopPages.FLAG_EMPTY);
            Component right = row.num(4) > 0L ? Component.translatable("gui.neroeconomy.hub.market.each", row.num(4))
                    : Component.translatable("gui.neroeconomy.hub.shop.no_price");
            int rightWidth = s.width(right);
            s.text(g, empty ? Component.translatable("gui.neroeconomy.hub.shop.no_stock")
                    : Component.translatable("gui.neroeconomy.hub.trade.items", row.num(3), s.itemName(row.icon())), 30,
                    top + 2, 212 - rightWidth, picked ? TexturedContainerScreen.SELECTED : TexturedContainerScreen.TITLE);
            s.textRight(g, right, 246, top + 2, TexturedContainerScreen.CREDIT);
            String state = row.flag(ShopPages.FLAG_IN_REACH) ? "gui.neroeconomy.hub.shop.here"
                    : row.flag(ShopPages.FLAG_LOADED) ? "gui.neroeconomy.hub.shop.loaded" : "gui.neroeconomy.hub.shop.unloaded";
            s.text(g, Component.translatable("gui.neroeconomy.hub.shop.where", row.num(0), row.num(1), row.num(2),
                    Component.literal(row.arg(0)), row.num(5), Component.translatable(state)), 30, top + 11, 218,
                    idle > 0L && row.num(5) * 4L >= idle * 3L ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
        });
        drawPageText(g, 54, 171);
        HubPage.Row row = selectedRow();
        long value = number(this.price);
        Component line;
        int colour = TexturedContainerScreen.SUBTLE;
        if (row == null) line = Component.translatable("gui.neroeconomy.hub.shop.pick");
        else if (armed("price|" + row.id() + "|" + value)) {
            line = Component.translatable("gui.neroeconomy.hub.shop.confirm_price", value, row.num(4));
            colour = EconomyHubScreen.WARNING;
        } else if (!row.flag(ShopPages.FLAG_LOADED)) line = Component.translatable("gui.neroeconomy.hub.shop.visit");
        else line = Component.translatable("gui.neroeconomy.hub.shop.price_hint");
        s.wrapped(g, line, 8, 204, 240, colour);
    }

    @Override
    Component tooltipAt(int x, int y) {
        HubPage.Row row = rowAt(x, y);
        if (row == null) return y >= 20 && y < 40 ? Component.translatable("gui.neroeconomy.hub.shop.summary.tooltip") : null;
        return Component.translatable("gui.neroeconomy.hub.shop.row.tooltip");
    }
}
