package za.co.neroland.neroeconomy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.network.ShopBuyPayload;

/**
 * The owned offline shop: one escrowed offer slot, a buyer region and a separately framed owner
 * region, above the player inventory.
 *
 * <p>Layout (panel-relative, panel 176x214; the menu's slot coordinates below match the sockets
 * painted into {@code textures/gui/player_shop.png}):</p>
 * <pre>
 *   0..16     header: title (8, 6), role badge right-aligned at 166
 *   16..76    buyer well: caption (11, 19) + wallet right-aligned at 165
 *             offer slot (12, 32), price (36, 32), stock (36, 44), Buy (36, 56, 64x18)
 *   78..114   owner well: caption (11, 81), price steps at y 92 (36x18 each)
 *   120       "Inventory" caption
 *   132/150/168 inventory rows, 190 hotbar
 * </pre>
 */
public final class PlayerShopScreen extends TexturedContainerScreen<PlayerShopMenu> {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/player_shop.png");
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 214;
    /** Right edge used for right-aligned readouts (panel inner margin). */
    private static final int RIGHT = 165;
    private static final int BUY_X = 36;
    private static final int BUY_Y = 56;
    private static final int BUY_W = 64;
    private static final int BUY_H = 18;
    private static final int STEP_Y = 92;
    private static final int STEP_W = 36;
    private static final int STEP_H = 18;

    private final List<Button> ownerButtons = new ArrayList<>();
    private Button buyButton;

    public PlayerShopScreen(PlayerShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, PANEL_W, PANEL_H);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 120;
    }

    @Override
    protected void init() {
        super.init();
        this.ownerButtons.clear();
        // Data slots arrive after screen construction, so both control groups are built and the server
        // stays authoritative; containerTick() enables the group that applies to this viewer.
        this.ownerButtons.add(stepButton(4, "gui.neroeconomy.shop.price_down_10", 10));
        this.ownerButtons.add(stepButton(2, "gui.neroeconomy.shop.price_down_1", 50));
        this.ownerButtons.add(stepButton(1, "gui.neroeconomy.shop.price_up_1", 90));
        this.ownerButtons.add(stepButton(3, "gui.neroeconomy.shop.price_up_10", 130));
        this.buyButton = addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.shop.buy"),
                        ignored -> NeroEconomyNetwork.sendToServer(new ShopBuyPayload(this.menu.containerId,
                                UUID.randomUUID(), Math.max(1L, this.menu.price()),
                                // The item on show right now; the server refuses the sale if it has changed.
                                BuiltInRegistries.ITEM.getKey(this.menu.getSlot(0).getItem().getItem()))))
                .bounds(this.leftPos + BUY_X, this.topPos + BUY_Y, BUY_W, BUY_H)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.shop.buy.tooltip"))).build());
    }

    private Button stepButton(int id, String key, int dx) {
        return addRenderableWidget(Button.builder(Component.translatable(key), ignored -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
            }
        }).bounds(this.leftPos + dx, this.topPos + STEP_Y, STEP_W, STEP_H)
                .tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean owner = this.menu.owner();
        this.ownerButtons.forEach(button -> button.active = owner);
        if (this.buyButton != null) {
            this.buyButton.active = !owner;
        }
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        boolean owner = this.menu.owner();
        labelRight(g, Component.translatable(owner ? "gui.neroeconomy.shop.role.owner"
                : "gui.neroeconomy.shop.role.buyer"), RIGHT + 1, 6, SUBTLE);

        label(g, Component.translatable("gui.neroeconomy.shop.section.buyer"), 11, 19, SUBTLE);
        labelRight(g, Component.translatable("gui.neroeconomy.wallet", this.menu.balance()), RIGHT, 19, CREDIT);
        label(g, trim(Component.translatable("gui.neroeconomy.shop.price", this.menu.price()), RIGHT - 36), 36, 32,
                TITLE);
        label(g, Component.translatable("gui.neroeconomy.shop.stock", this.menu.stock()), 36, 44, SUBTLE);

        label(g, Component.translatable("gui.neroeconomy.shop.section.owner"), 11, 81, SUBTLE);
    }
}
