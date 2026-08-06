package za.co.neroland.neroeconomy.client;

import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.network.ShopBuyPayload;

public final class PlayerShopScreen extends AbstractContainerScreen<PlayerShopMenu> {
    private final java.util.List<Button> ownerButtons = new java.util.ArrayList<>();
    private Button buyButton;
    public PlayerShopScreen(PlayerShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166);
        inventoryLabelY = 72;
    }

    @Override protected void init() {
        super.init();
        // Data slots arrive after screen construction; show both control groups and let the server
        // enforce ownership. The role label below makes the applicable group explicit.
        ownerButtons.add(button(2, Component.translatable("gui.neroeconomy.shop.price_down_1"), 92, 46, 28));
        ownerButtons.add(button(1, Component.translatable("gui.neroeconomy.shop.price_up_1"), 122, 46, 28));
        ownerButtons.add(button(4, Component.translatable("gui.neroeconomy.shop.price_down_10"), 92, 64, 28));
        ownerButtons.add(button(3, Component.translatable("gui.neroeconomy.shop.price_up_10"), 122, 64, 28));
        // The single offer slot sits at (8, 34), so the buy control starts clear of it.
        buyButton = addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.shop.buy"),
                ignored -> NeroEconomyNetwork.sendToServer(new ShopBuyPayload(menu.containerId, UUID.randomUUID(),
                        Math.max(1L, menu.price()))))
                .bounds(leftPos + 30, topPos + 42, 56, 18).build());
    }

    private Button button(int id, Component label, int x, int y, int width) {
        return addRenderableWidget(Button.builder(label, button -> {
            if (minecraft != null && minecraft.gameMode != null)
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }).bounds(leftPos + x, topPos + y, width, 18).build());
    }

    @Override protected void containerTick() {
        super.containerTick();
        boolean owner = menu.owner();
        ownerButtons.forEach(button -> button.active = owner);
        if (buyButton != null) buyButton.active = !owner;
    }

    @Override public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF304553);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFE7EFF3);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + 18, 0xFF173444);
        g.text(font, Component.translatable("gui.neroeconomy.shop.price", menu.price()), leftPos + 92, topPos + 22,
                0xFF173444, false);
        g.text(font, Component.translatable("gui.neroeconomy.shop.stock", menu.stock()), leftPos + 92, topPos + 34,
                0xFF536975, false);
        g.text(font, Component.translatable(menu.owner() ? "gui.neroeconomy.shop.role.owner"
                : "gui.neroeconomy.shop.role.buyer"), leftPos + 8, topPos + 20, 0xFF536975, false);
        g.text(font, Component.translatable("gui.neroeconomy.balance", menu.balance()), leftPos + 8, topPos + 62,
                0xFF173444, false);
        super.extractContents(g, mouseX, mouseY, partialTick);
    }

    @Override protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, 8, 6, 0xFFFFFFFF, false);
        g.text(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF173444, false);
    }
}
