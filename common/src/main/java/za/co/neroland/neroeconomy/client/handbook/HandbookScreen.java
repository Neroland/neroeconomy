package za.co.neroland.neroeconomy.client.handbook;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.client.EconomyHubScreen;
import za.co.neroland.neroeconomy.handbook.Handbook;
import za.co.neroland.neroeconomy.handbook.HandbookOpener;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * The Trader's Handbook: the chapter list down the left, the open chapter's page beside it, paging
 * under the page and an Open demo button that runs the matching hub tab's "Show me" walkthrough.
 * Everything is drawn from lang keys on the client: the book has no menu, reads nothing from the
 * server and sends nothing to it. The demo is the hub's own walkthrough on sample pages, opened with
 * {@link EconomyHubScreen#demoOnly}, which never reaches the server either; leaving it comes back
 * here, on the same page.
 *
 * <p>Layout (panel-relative, panel {@value #PANEL_W}x{@value #PANEL_H}, sheet made by
 * {@code tools/gen_gui.py}):</p>
 * <pre>
 *   header      the book's title at (8, 5)
 *   list        twelve 100x14 chapter buttons from (8, 20), 16px apart
 *   page        heading at (118, 22), cut to 174; words wrapped to 174 from (118, 37), at most
 *               {@value #TEXT_LINES} lines; Open demo (118, 172, 90x14)
 *   paging      Back (118, 194, 44x14), "Page n of m" in the 78 between, Next (248, 194, 44x14)
 * </pre>
 */
public final class HandbookScreen extends Screen {
    private static final Identifier SHEET =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "textures/gui/handbook.png");
    static final int PANEL_W = 304;
    static final int PANEL_H = 220;
    static final int LIST_X = 8;
    static final int LIST_Y = 20;
    static final int LIST_W = 100;
    static final int ROW_H = 14;
    static final int ROW_STEP = 16;
    static final int PAGE_X = 118;
    static final int PAGE_W = 174;
    static final int HEADING_Y = 22;
    static final int TEXT_Y = 37;
    /** Lines of page words the page has room for, 9 pixels apart, above the Open demo button. */
    static final int TEXT_LINES = 14;
    static final int DEMO_Y = 172;
    static final int DEMO_W = 90;
    static final int PAGING_Y = 194;
    static final int PAGING_W = 44;
    static final int COUNTER_W = PAGE_W - 2 * PAGING_W - 8;
    private static final int TITLE = 0xFFD6ECFF;
    private static final int BODY = 0xFFC4D4E4;
    private static final int SUBTLE = 0xFF8DA0B4;
    private static final int HEADING = 0xFF38E6A1;
    private static final String ELLIPSIS = "gui.neroeconomy.ellipsis";

    /** How a screen is shown on this game version; handed over by the loader's client setup. */
    private static volatile Consumer<Screen> shower = screen -> { };
    /** Where the reader was, for this game session only: never saved and never sent. */
    private static int chapter;
    private static int page;

    private int left;
    private int top;

    private HandbookScreen() {
        super(Component.translatable(Handbook.TITLE));
    }

    /**
     * Called once from each loader's client setup with how to show a screen there: the item then
     * opens the book through {@link HandbookOpener}.
     */
    public static void install(Consumer<Screen> show) {
        if (show == null) return;
        shower = show;
        HandbookOpener.set(HandbookScreen::open);
    }

    /** Opens the book where the reader left it. */
    public static void open() {
        if (Minecraft.getInstance().player == null) return;
        shower.accept(new HandbookScreen());
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
        chapter = Math.max(0, Math.min(chapter, Handbook.CHAPTERS_IN_ORDER.size() - 1));
        page = Handbook.clampPage(chapter, page);
        for (int index = 0; index < Handbook.CHAPTERS_IN_ORDER.size(); index++) {
            Handbook.Chapter shown = Handbook.CHAPTERS_IN_ORDER.get(index);
            int picked = index;
            Button button = addRenderableWidget(Button.builder(Component.translatable(shown.nameKey()), ignored -> {
                chapter = picked;
                page = 0;
                rebuildWidgets();
            }).bounds(this.left + LIST_X, this.top + LIST_Y + index * ROW_STEP, LIST_W, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable(shown.titleKey()))).build());
            button.active = index != chapter;
        }
        HubTab demo = Handbook.chapter(chapter).demo(page);
        addRenderableWidget(Button.builder(Component.translatable(Handbook.DEMO), ignored -> openDemo())
                .bounds(this.left + PAGE_X, this.top + DEMO_Y, DEMO_W, ROW_H)
                .tooltip(Tooltip.create(Component.translatable(Handbook.DEMO + ".tooltip",
                        Component.translatable(demo.key())))).build());
        Button previous = addRenderableWidget(Button.builder(Component.translatable(Handbook.PREVIOUS), ignored -> {
            page = Handbook.clampPage(chapter, page - 1);
            rebuildWidgets();
        }).bounds(this.left + PAGE_X, this.top + PAGING_Y, PAGING_W, ROW_H)
                .tooltip(Tooltip.create(Component.translatable(Handbook.PREVIOUS + ".tooltip"))).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.translatable(Handbook.NEXT), ignored -> {
            page = Handbook.clampPage(chapter, page + 1);
            rebuildWidgets();
        }).bounds(this.left + PAGE_X + PAGE_W - PAGING_W, this.top + PAGING_Y, PAGING_W, ROW_H)
                .tooltip(Tooltip.create(Component.translatable(Handbook.NEXT + ".tooltip"))).build());
        next.active = page < Handbook.chapter(chapter).pages() - 1;
    }

    /** Runs the walkthrough of this page's tab on sample data; leaving it shows this page again. */
    private void openDemo() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        HandbookScreen back = this;
        shower.accept(EconomyHubScreen.demoOnly(Handbook.chapter(chapter).demo(page),
                minecraft.player.getInventory(), () -> shower.accept(back)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blit(RenderPipelines.GUI_TEXTURED, SHEET, this.left, this.top, 0.0F, 0.0F, PANEL_W, PANEL_H, 512, 256);
        g.text(this.font, this.title, this.left + 8, this.top + 5, TITLE, false);
        Handbook.Chapter open = Handbook.chapter(chapter);
        g.text(this.font, cut(Component.translatable(open.titleKey()), PAGE_W), this.left + PAGE_X,
                this.top + HEADING_Y, HEADING, false);
        g.textWithWordWrap(this.font, Component.translatable(open.pageKey(page)), this.left + PAGE_X,
                this.top + TEXT_Y, PAGE_W, BODY, false);
        Component counter = Component.translatable(Handbook.PAGE, page + 1, open.pages());
        int counterX = this.left + PAGE_X + PAGING_W + 4 + (COUNTER_W - this.font.width(counter)) / 2;
        g.text(this.font, counter, counterX, this.top + PAGING_Y + 3, SUBTLE, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** {@code text} shortened to {@code width} pixels with an ellipsis; text that fits is kept as it is. */
    private Component cut(Component text, int width) {
        String raw = text.getString();
        if (this.font.width(raw) <= width) return text;
        int budget = Math.max(0, width - this.font.width(Component.translatable(ELLIPSIS, "")));
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int index = 0; index < raw.length(); index++) {
            int glyph = this.font.width(String.valueOf(raw.charAt(index)));
            if (used + glyph > budget) break;
            kept.append(raw.charAt(index));
            used += glyph;
        }
        return Component.translatable(ELLIPSIS, kept.toString());
    }
}
