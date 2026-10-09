package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.VaultMenu;

/**
 * The vault: a six-row grid over the player's own vault, above their inventory. Everything shown
 * is what the server sent; the client only draws it.
 *
 * <p>Layout (panel-relative, panel 176x236, mirrored by {@code tools/gen_gui.py}):</p>
 * <pre>
 *   0..16     header strip: title at (8, 6), "stacks used / cap" right-aligned at y 6
 *   18..126   the grid: six rows of nine slots from (8, 18)
 *   128..141  status line at (11, 131): stacks waiting off screen, or what an empty vault is for
 *   143       "Inventory" caption
 *   153/171/189 inventory rows, 211 hotbar
 * </pre>
 */
public final class VaultScreen extends TexturedContainerScreen<VaultMenu> {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/vault.png");
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 236;
    /** Right edge used for the right-aligned counter (panel inner margin). */
    private static final int RIGHT = 168;
    private static final int COUNT_Y = 6;
    private static final int STATUS_X = 11;
    private static final int STATUS_Y = 131;
    private static final int STATUS_W = 154;
    private static final int LINE_H = 9;
    /** Drawn over a grid slot that this server's vault size does not include. */
    private static final int LOCKED = 0xC0070A10;
    /** A vault past its cap, where nothing can be put in until it is back under. */
    private static final int WARNING = 0xFFFF8A65;

    public VaultScreen(VaultMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, PANEL_W, PANEL_H);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 143;
    }

    @Override
    protected void init() {
        super.init();
        // Back to the Economy Hub, on its Vault tab: the server opens it, at the same terminal.
        addRenderableWidget(Button.builder(Component.translatable("gui.neroeconomy.hub.back"), ignored -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, VaultMenu.BACK_BUTTON);
            }
        }).bounds(this.leftPos + 44, this.topPos + 2, 36, 12)
                .tooltip(Tooltip.create(Component.translatable("gui.neroeconomy.hub.back.tooltip"))).build());
    }

    private Component count() {
        return Component.translatable("gui.neroeconomy.vault.count", this.menu.stackCount(), this.menu.cap());
    }

    /** The one line under the grid: what needs saying about the vault right now. */
    private Component status() {
        int hidden = this.menu.hidden();
        if (hidden > 0) return Component.translatable("gui.neroeconomy.vault.hidden", hidden);
        if (this.menu.overCap()) return Component.translatable("gui.neroeconomy.vault.over_cap");
        if (this.menu.stackCount() == 0) return Component.translatable("gui.neroeconomy.vault.empty");
        return Component.translatable("gui.neroeconomy.vault.hint");
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        boolean over = this.menu.overCap();
        labelRight(g, count(), RIGHT, COUNT_Y, over ? WARNING : CREDIT);
        label(g, trim(status(), STATUS_W), STATUS_X, STATUS_Y, over ? WARNING : SUBTLE);
        // Slots past the server's vault size are switched off; shade them so the grid reads as smaller.
        for (int slot = this.menu.cap(); slot < VaultMenu.SLOTS; slot++) {
            band(g, VaultMenu.GRID_X - 1 + slot % VaultMenu.COLUMNS * 18,
                    VaultMenu.GRID_Y - 1 + slot / VaultMenu.COLUMNS * 18, 18, 18, LOCKED);
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractContents(extractor, mouseX, mouseY, partialTick);
        Component tip = tooltipAt(mouseX - this.leftPos, mouseY - this.topPos);
        if (tip != null && this.minecraft != null) {
            extractor.setTooltipForNextFrame(this.font, Tooltip.splitTooltip(this.minecraft, tip), mouseX, mouseY);
        }
    }

    /** The explanation for whatever readout the pointer is over, in panel-relative coordinates. */
    private Component tooltipAt(int dx, int dy) {
        int countWidth = this.font.width(count());
        if (dx >= RIGHT - countWidth && dx < RIGHT && dy >= COUNT_Y - 1 && dy < COUNT_Y + LINE_H) {
            return Component.translatable("gui.neroeconomy.vault.count.tooltip");
        }
        if (dx >= STATUS_X && dx < STATUS_X + STATUS_W && dy >= STATUS_Y - 1 && dy < STATUS_Y + LINE_H) {
            if (this.menu.hidden() > 0) return Component.translatable("gui.neroeconomy.vault.hidden.tooltip");
            if (this.menu.overCap()) return Component.translatable("gui.neroeconomy.vault.over_cap.tooltip");
            return Component.translatable("gui.neroeconomy.vault.status.tooltip");
        }
        int col = Math.floorDiv(dx - (VaultMenu.GRID_X - 1), 18);
        int row = Math.floorDiv(dy - (VaultMenu.GRID_Y - 1), 18);
        if (col >= 0 && col < VaultMenu.COLUMNS && row >= 0 && row < VaultMenu.ROWS
                && col + row * VaultMenu.COLUMNS >= this.menu.cap()) {
            return Component.translatable("gui.neroeconomy.vault.locked.tooltip", this.menu.cap());
        }
        return null;
    }
}
