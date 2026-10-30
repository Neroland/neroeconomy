package za.co.neroland.neroeconomy.client;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * Version seam for the few GUI drawing calls whose name or shape changed across the Minecraft lines this
 * mod builds for (26.x's {@code GuiGraphicsExtractor} was {@code GuiGraphics} before 26.1, the textured
 * blit took a render type before 1.21.6 and no pipeline at all on 1.21.1, tooltips were drawn at once
 * before 1.21.6 instead of deferred to the end of the frame). Screens call these instead of the graphics
 * object directly, so their source stays the same on every line. On 26.x each method is the plain call.
 */
public final class GuiCompat {

    private GuiCompat() {
    }

    /** Blits a {@code w x h} region at {@code (u, v)} of a {@code texW x texH} GUI texture. */
    public static void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y, float u, float v, int w, int h,
            int texW, int texH) {
        //? if >=1.21.6 {
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, texW, texH);
        //?} else if >=1.21.2 {
        /*g.blit(net.minecraft.client.renderer.RenderType::guiTextured, texture, x, y, u, v, w, h, texW, texH);
        *///?} else {
        /*g.blit(texture, x, y, u, v, w, h, texW, texH);
        *///?}
    }

    /** A one-pixel frame around the given rectangle. */
    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        //? if >=26.1 {
        g.outline(x, y, w, h, color);
        //?} else if >=1.21.11 {
        /*g.renderOutline(x, y, w, h, color);
        *///?} else if >=1.21.9 {
        /*g.submitOutline(x, y, w, h, color);
        *///?} else {
        /*g.renderOutline(x, y, w, h, color);
        *///?}
    }

    /** Word-wrapped text without a shadow. */
    public static void wrapped(GuiGraphicsExtractor g, Font font, FormattedText text, int x, int y, int width,
            int color) {
        //? if >=26.1 {
        g.textWithWordWrap(font, text, x, y, width, color, false);
        //?} else if >=1.21.6 {
        /*g.drawWordWrap(font, text, x, y, width, color, false);
        *///?} else {
        /*g.drawWordWrap(font, text, x, y, width, color);
        *///?}
    }

    /** An item icon (no count or durability decorations). */
    public static void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        //? if >=26.1 {
        g.item(stack, x, y);
        //?} else {
        /*g.renderItem(stack, x, y);
        *///?}
    }

    /** Starts a new layer, so what follows draws over everything before it (a no-op before 1.21.6). */
    public static void nextStratum(GuiGraphicsExtractor g) {
        //? if >=1.21.6 {
        g.nextStratum();
        //?}
    }

    /** A tooltip at the pointer; deferred to the end of the frame from 1.21.6, drawn at once before. */
    public static void tooltip(GuiGraphicsExtractor g, Font font, List<FormattedCharSequence> lines, int x, int y) {
        //? if >=1.21.6 {
        g.setTooltipForNextFrame(font, lines, x, y);
        //?} else {
        /*g.renderTooltip(font, lines, x, y);
        *///?}
    }

    /** Draws a widget the screen manages itself (not through its renderables). */
    public static void widget(AbstractWidget widget, GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        //? if >=26.1 {
        widget.extractRenderState(g, mouseX, mouseY, partialTick);
        //?} else {
        /*widget.render(g, mouseX, mouseY, partialTick);
        *///?}
    }
}
