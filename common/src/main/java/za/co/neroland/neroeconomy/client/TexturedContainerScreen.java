package za.co.neroland.neroeconomy.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Base class for NeroEconomy's container screens (the Economy Hub, the vault, the player shop,
 * direct trade and the Auto-Trader). Draws a sci-fi hull panel (a 256x256 PNG under
 * {@code assets/neroeconomy/textures/gui/}, whose top-left {@code imageWidth x imageHeight} is the
 * panel) and themes the title/inventory labels for a dark background. Subclasses paint their
 * readouts in {@link #extractForeground} using the panel-relative helpers below.
 *
 * <p>26.x renders container screens through {@code extract*(GuiGraphicsExtractor, ...)}: the panel is
 * blitted in {@link #extractContents} before the vanilla slot/item pass, custom drawing goes on top.</p>
 */
public abstract class TexturedContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    /** Shared palette (kept byte-identical in spirit with the rest of the ecosystem's screens). */
    protected static final int TROUGH = 0xFF0B1119;    // gauge / list backing
    protected static final int TITLE = 0xFFD6ECFF;     // bright label
    protected static final int SUBTLE = 0xFF8DA0B4;    // dim label
    /** Credits are always rendered in the economy's own green so money reads at a glance. */
    protected static final int CREDIT = 0xFF38E6A1;
    /** Selected list row text. */
    protected static final int SELECTED = 0xFFFFD166;
    /** Lang key that wraps a shortened text with its ellipsis. */
    private static final String ELLIPSIS = "gui.neroeconomy.ellipsis";

    private final Identifier background;

    protected TexturedContainerScreen(T menu, Inventory playerInventory, Component title, Identifier background,
                                      int width, int height) {
        super(menu, playerInventory, title, width, height);
        this.background = background;
    }

    /**
     * While a text box has focus, keys belong to it. Without this the container screen closes on
     * the inventory key and fires its hotbar, drop and pick hotkeys for letters typed into a
     * search, recipient, amount or price field. Escape still closes and Tab still moves between
     * fields; everything else goes to the box and nowhere else.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox typing = getFocused() instanceof EditBox box && box.canConsumeInput() ? box : null;
        TextInputKeys.Route route = TextInputKeys.route(event.isEscape(), typing != null,
                event.key() == InputConstants.KEY_TAB);
        if (route == TextInputKeys.Route.SCREEN || typing == null) {
            return super.keyPressed(event);
        }
        if (route == TextInputKeys.Route.FOCUS) {
            moveFocus(!event.hasShiftDown());
        } else {
            typing.keyPressed(event);
        }
        return true;
    }

    /** Tab traversal, as the base screen does it, without the container hotkeys that follow it there. */
    private void moveFocus(boolean forward) {
        FocusNavigationEvent.TabNavigation navigation = new FocusNavigationEvent.TabNavigation(forward);
        ComponentPath path = nextFocusPath(navigation);
        if (path == null) {
            clearFocus();
            path = nextFocusPath(navigation);
        }
        if (path != null) {
            changeFocus(path);
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        drawPanel(extractor);
        super.extractContents(extractor, mouseX, mouseY, partialTick);
        extractForeground(extractor);
    }

    /** The sheet to blit. Overridable so one screen class can serve two blocks (terminal vs board). */
    protected Identifier background() {
        return this.background;
    }

    /** Blits the backing panel: the top-left {@code imageWidth x imageHeight} of a 256x256 sheet. */
    protected void drawPanel(GuiGraphicsExtractor extractor) {
        extractor.blit(RenderPipelines.GUI_TEXTURED, background(), this.leftPos, this.topPos,
                0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
    }

    /** Subclass hook: draw readouts on top of the panel (helpers take panel-relative coords). */
    protected void extractForeground(GuiGraphicsExtractor extractor) {
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        extractor.text(this.font, this.title, this.titleLabelX, this.titleLabelY, TITLE, false);
        extractor.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, SUBTLE, false);
    }

    // --- Drawing helpers (panel-relative dx/dy) -----------------------------

    /** Left-aligned label text at a panel-relative position. */
    protected void label(GuiGraphicsExtractor g, Component text, int dx, int dy, int color) {
        g.text(this.font, text, this.leftPos + dx, this.topPos + dy, color, false);
    }

    /** Right-aligned label text whose right edge sits at the panel-relative {@code dxRight}. */
    protected void labelRight(GuiGraphicsExtractor g, Component text, int dxRight, int dy, int color) {
        g.text(this.font, text, this.leftPos + dxRight - this.font.width(text), this.topPos + dy, color, false);
    }

    /** A flat filled band (selection highlights, dividers) in panel-relative coords. */
    protected void band(GuiGraphicsExtractor g, int dx, int dy, int w, int h, int color) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x, y, x + w, y + h, color);
    }

    /**
     * Shortens {@code text} to fit {@code maxWidth} pixels, appending an ellipsis. List rows carry
     * server-supplied item ids of unbounded length, so every row is trimmed before it is drawn and can
     * never bleed past its frame. Text that already fits is returned as it is.
     */
    protected Component trim(Component text, int maxWidth) {
        String raw = text.getString();
        if (this.font.width(raw) <= maxWidth) {
            return text;
        }
        int budget = Math.max(0, maxWidth - this.font.width(Component.translatable(ELLIPSIS, "")));
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < raw.length(); i++) {
            int charWidth = this.font.width(String.valueOf(raw.charAt(i)));
            if (used + charWidth > budget) {
                break;
            }
            kept.append(raw.charAt(i));
            used += charWidth;
        }
        return Component.translatable(ELLIPSIS, kept.toString());
    }
}
