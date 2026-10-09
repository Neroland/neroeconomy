package za.co.neroland.neroeconomy.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.client.demo.DemoAction;
import za.co.neroland.neroeconomy.client.demo.DemoIntents;
import za.co.neroland.neroeconomy.client.demo.DemoScript;
import za.co.neroland.neroeconomy.client.demo.DemoStep;
import za.co.neroland.neroeconomy.client.demo.DemoTarget;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * A running "Show me" walkthrough of one hub tab. Each step is shown on a fresh view of the tab
 * fed the script's sample pages, with every step up to it replayed, so Back shows exactly what it
 * showed before. The demo view is handed {@link DemoIntents#NONE}, a sender that drops everything:
 * nothing a demo does reaches the server. The hub draws the frame on top of everything: a box
 * around what the step talks about, the step's words with Back / Next / Close, and a DEMO banner
 * that is drawn last, every frame, and cannot be hidden.
 *
 * <p>Caption layout (body coordinates): a 248x{@value #CAPTION_H} panel at x 4, below the highlight
 * when there is room and above it otherwise; three lines of words from its top, then Back (4, 36,
 * 44x14), Next (52, 36, 44x14), the step counter, and Close (200, 36, 44x14) inside it.</p>
 */
final class HubDemo {
    static final int CAPTION_X = 4;
    static final int CAPTION_W = 248;
    static final int CAPTION_H = 54;
    static final int TEXT_W = 240;
    private static final int CAPTION_TOP = 18;
    private static final int CAPTION_BOTTOM = EconomyHubScreen.PANEL_H - CAPTION_H - 2;
    private static final int CAPTION_MIDDLE = 80;
    /** Client ticks run after typing, so a view's typing delay (the market search) has passed. */
    private static final int SETTLE_TICKS = 10;
    private static final int PANEL = 0xF0101820;
    private static final int FRAME = 0xFF38E6A1;
    private static final int HIGHLIGHT = 0xFFFFD166;
    private static final int HIGHLIGHT_FILL = 0x30FFD166;
    private static final int BANNER = 0xFFFFD166;
    private static final int BANNER_TEXT = 0xFF101820;
    /** How many of the made-up stack (diamonds) a demo view shows as held in the hand. */
    private static final int SAMPLE_HELD_COUNT = 5;

    private final EconomyHubScreen screen;
    private final DemoScript script;
    private final List<Button> controls = new ArrayList<>();
    private int step;
    private int captionTop = CAPTION_BOTTOM;
    /** The sample view of the step on screen; null for a words-only walkthrough. */
    private HubTabView view;

    HubDemo(EconomyHubScreen screen, DemoScript script) {
        this.screen = screen;
        this.script = script;
    }

    HubTab tab() { return this.script.tab(); }

    HubTabView view() { return this.view; }

    int step() { return this.step; }

    /** Shows step {@code index} on a fresh sample view. */
    void show(int index) {
        this.step = Math.max(0, Math.min(index, this.script.steps().size() - 1));
        DemoTarget target = this.script.steps().get(this.step).target();
        this.captionTop = target == null ? CAPTION_MIDDLE : captionTopFor(target);
        this.view = this.script.textOnly() ? null
                : this.screen.newView(this.script.tab(), DemoIntents.NONE, this.script.pages().get(0));
        if (this.view != null) this.view.sampleHeld(new ItemStack(Items.DIAMOND, SAMPLE_HELD_COUNT));
        if (this.view instanceof HubTradeView trade) {
            List<String> names = new ArrayList<>();
            for (String key : this.script.sampleNames()) names.add(Component.translatable(key).getString());
            trade.sampleNearby(names);
        }
        this.screen.rebuild();
        if (this.view != null) {
            for (int k = 0; k <= this.step; k++) {
                DemoStep shown = this.script.steps().get(k);
                if (shown.page() >= 0) this.view.accept(this.script.pages().get(shown.page()));
                for (DemoAction action : shown.actions()) apply(action);
            }
            this.view.tick();
        }
        hideUnderCaption();
    }

    /** Below the highlight when it is in the top part of the body, above it otherwise. */
    private static int captionTopFor(DemoTarget target) {
        int overlapBottom = overlap(target, CAPTION_BOTTOM);
        int overlapTop = overlap(target, CAPTION_TOP);
        return overlapTop < overlapBottom ? CAPTION_TOP : CAPTION_BOTTOM;
    }

    private static int overlap(DemoTarget target, int top) {
        int from = Math.max(target.y(), top);
        int to = Math.min(target.y() + target.h(), top + CAPTION_H);
        return Math.max(0, to - from);
    }

    private void apply(DemoAction action) {
        DemoTarget at = action.target();
        switch (action.kind()) {
            case TYPE, TYPE_TEXT -> {
                String text = action.kind() == DemoAction.Kind.TYPE ? action.text()
                        : Component.translatable(action.text()).getString();
                this.screen.typeAt(at.pointX(), at.pointY(), text);
                for (int tick = 0; tick < SETTLE_TICKS; tick++) this.view.tick();
            }
            case PRESS -> {
                this.view.tick();
                this.screen.pressAt(at.pointX(), at.pointY());
                this.view.tick();
            }
            case CLICK -> {
                this.view.tick();
                this.view.click(at.x(), at.y());
                this.view.tick();
            }
        }
    }

    /** Adds the sample view's widgets and the demo's own buttons; called on every widget rebuild. */
    void init() {
        this.controls.clear();
        if (this.view != null) this.view.init();
        int y = this.captionTop + 36;
        boolean last = this.step == this.script.steps().size() - 1;
        Button back = this.screen.demoButton(Component.translatable(DemoScript.BACK), DemoScript.BACK + ".tooltip",
                CAPTION_X + 4, y, 44, 14, () -> show(this.step - 1));
        back.active = this.step > 0;
        this.controls.add(back);
        String next = last ? DemoScript.DONE : DemoScript.NEXT;
        this.controls.add(this.screen.demoButton(Component.translatable(next), next + ".tooltip", CAPTION_X + 48, y,
                44, 14, () -> {
                    if (last) this.screen.closeDemo();
                    else show(this.step + 1);
                }));
        this.controls.add(this.screen.demoButton(Component.translatable(DemoScript.CLOSE), DemoScript.CLOSE + ".tooltip",
                CAPTION_X + CAPTION_W - 48, y, 44, 14, this.screen::closeDemo));
        hideUnderCaption();
    }

    /** Hides the sample view's widgets the caption covers, so they cannot be seen or pressed through it. */
    void hideUnderCaption() {
        this.screen.hideWidgetsIn(CAPTION_X, this.captionTop, CAPTION_W, CAPTION_H, this.controls);
    }

    List<Button> controls() { return this.controls; }

    /** Whether a body-relative point is on the caption. */
    boolean onCaption(int x, int y) {
        return x >= CAPTION_X && x < CAPTION_X + CAPTION_W && y >= this.captionTop && y < this.captionTop + CAPTION_H;
    }

    /** Draws the frame over the sample view: highlight, caption with its buttons, then the banner. */
    void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EconomyHubScreen s = this.screen;
        g.nextStratum();
        DemoTarget target = this.script.steps().get(this.step).target();
        if (target != null) {
            s.fill(g, target.x() - 2, target.y() - 2, target.w() + 4, target.h() + 4, HIGHLIGHT_FILL);
            g.outline(s.bodyLeft() + target.x() - 2, s.bodyTop() + target.y() - 2, target.w() + 4, target.h() + 4,
                    HIGHLIGHT);
            g.outline(s.bodyLeft() + target.x() - 3, s.bodyTop() + target.y() - 3, target.w() + 6, target.h() + 6,
                    HIGHLIGHT);
        }
        s.fill(g, CAPTION_X, this.captionTop, CAPTION_W, CAPTION_H, PANEL);
        g.outline(s.bodyLeft() + CAPTION_X, s.bodyTop() + this.captionTop, CAPTION_W, CAPTION_H, FRAME);
        s.wrapped(g, Component.translatable(this.script.stepKey(this.step)), CAPTION_X + 4, this.captionTop + 4,
                TEXT_W, TexturedContainerScreen.TITLE);
        s.text(g, Component.translatable(DemoScript.COUNTER, this.step + 1, this.script.steps().size()),
                CAPTION_X + 98, this.captionTop + 39, 96, TexturedContainerScreen.SUBTLE);
        for (Button control : this.controls) control.extractRenderState(g, mouseX, mouseY, partialTick);
        drawBanner(g);
    }

    /** The DEMO banner across the hub's header: drawn last, every frame the demo runs. */
    private void drawBanner(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.fill(g, 0, 1, EconomyHubScreen.BODY_W, 15, BANNER);
        Component banner = s.cut(Component.translatable(DemoScript.BANNER), EconomyHubScreen.BODY_W - 8);
        s.text(g, banner, (EconomyHubScreen.BODY_W - s.width(banner)) / 2, 5, BANNER_TEXT);
    }
}
