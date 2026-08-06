package za.co.neroland.neroeconomy.client;

import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import za.co.neroland.neroeconomy.menu.BankTerminalMenu;
import za.co.neroland.neroeconomy.network.BankTransferPayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;

/** Accessible bank transfer form; the client sends only an idempotent intent. */
public final class BankTerminalScreen extends AbstractContainerScreen<BankTerminalMenu> {
    private EditBox target;
    private EditBox amount;
    public BankTerminalScreen(BankTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 126);
    }
    @Override protected void init() {
        super.init();
        target = new EditBox(font, leftPos + 8, topPos + 48, 100, 18,
                Component.translatable("gui.neroeconomy.bank.target"));
        target.setMaxLength(64); target.setHint(Component.translatable("gui.neroeconomy.bank.target_hint"));
        amount = new EditBox(font, leftPos + 112, topPos + 48, 56, 18,
                Component.translatable("gui.neroeconomy.bank.amount"));
        amount.setMaxLength(19); amount.setHint(Component.translatable("gui.neroeconomy.bank.amount"));
        addRenderableWidget(target); addRenderableWidget(amount);
        addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.bank.send"), ignored -> send())
                .bounds(leftPos + 8, topPos + 72, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.market.collect"), ignored -> {
            if (minecraft != null && minecraft.gameMode != null)
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 3);
        }).bounds(leftPos + 92, topPos + 72, 76, 20).build());
    }
    private void send() {
        try {
            long value = Long.parseLong(amount.getValue());
            NeroEconomyNetwork.sendToServer(new BankTransferPayload(menu.containerId, UUID.randomUUID(),
                    target.getValue(), value));
        } catch (NumberFormatException ignored) { }
    }
    @Override public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF203849);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF0D1620);
        g.text(font, Component.translatable("gui.neroeconomy.balance", menu.balance()), leftPos + 8, topPos + 30,
                0xFF63D7FF, false);
        super.extractContents(g, mouseX, mouseY, partialTick);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, 8, 6, 0xFFD6ECFF, false);
    }
}
