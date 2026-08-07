package za.co.neroland.neroeconomy.client;

import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.BankTerminalMenu;
import za.co.neroland.neroeconomy.network.BankTransferPayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;

/**
 * Accessible bank transfer form; the client only ever sends an idempotent intent.
 *
 * <p>Layout (panel-relative, panel 176x134, no menu slots):</p>
 * <pre>
 *   0..16    header strip, title at (8, 6)
 *   16..32   balance readout well, text at (11, 20)
 *   38       "Recipient" caption          48..66  recipient field  (10, 48, 156x18)
 *   72       "Amount" caption             82..100 amount field     (10, 82, 156x18)
 *   106..126 action row: Send (10, 106, 74x20) | Collect (92, 106, 74x20)
 * </pre>
 */
public final class BankTerminalScreen extends TexturedContainerScreen<BankTerminalMenu> {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/bank_terminal.png");
    /** Credit-green: the bank's accent. */
    private static final int ACCENT = 0xFF38E6A1;

    private static final int PANEL_W = 176;
    private static final int PANEL_H = 134;
    private static final int FIELD_X = 10;
    private static final int FIELD_W = 156;
    private static final int FIELD_H = 18;
    private static final int TARGET_Y = 48;
    private static final int AMOUNT_Y = 82;
    private static final int ACTION_Y = 106;
    private static final int ACTION_W = 74;
    private static final int ACTION_H = 20;
    /** Menu button id for "collect everything owed to me". */
    private static final int COLLECT_BUTTON = 3;

    private EditBox target;
    private EditBox amount;

    public BankTerminalScreen(BankTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, ACCENT, PANEL_W, PANEL_H);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        this.target = new EditBox(this.font, this.leftPos + FIELD_X, this.topPos + TARGET_Y, FIELD_W, FIELD_H,
                Component.translatable("gui.neroeconomy.bank.target"));
        this.target.setMaxLength(64);
        this.target.setHint(Component.translatable("gui.neroeconomy.bank.target_hint"));
        this.amount = new EditBox(this.font, this.leftPos + FIELD_X, this.topPos + AMOUNT_Y, FIELD_W, FIELD_H,
                Component.translatable("gui.neroeconomy.bank.amount"));
        this.amount.setMaxLength(19);
        this.amount.setHint(Component.translatable("gui.neroeconomy.bank.amount_hint"));
        addRenderableWidget(this.target);
        addRenderableWidget(this.amount);
        addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.bank.send"), ignored -> send())
                .bounds(this.leftPos + FIELD_X, this.topPos + ACTION_Y, ACTION_W, ACTION_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.market.collect"), ignored -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, COLLECT_BUTTON);
            }
        }).bounds(this.leftPos + 92, this.topPos + ACTION_Y, ACTION_W, ACTION_H).build());
    }

    private void send() {
        try {
            long value = Long.parseLong(this.amount.getValue());
            NeroEconomyNetwork.sendToServer(new BankTransferPayload(this.menu.containerId, UUID.randomUUID(),
                    this.target.getValue(), value));
        } catch (NumberFormatException ignored) {
            // The server is authoritative; a malformed amount simply sends nothing.
        }
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        label(g, Component.translatable("gui.neroeconomy.balance", this.menu.balance()), 11, 20, CREDIT);
        label(g, Component.translatable("gui.neroeconomy.bank.target"), 11, 38, SUBTLE);
        label(g, Component.translatable("gui.neroeconomy.bank.amount"), 11, 72, SUBTLE);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // No player inventory on this menu, so only the title is drawn.
        g.text(this.font, this.title, this.titleLabelX, this.titleLabelY, TITLE, false);
    }
}
