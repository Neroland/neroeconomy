package za.co.neroland.neroeconomy.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.text.TimeText;

/**
 * One tab of the Economy Hub on the client. A view draws the page the server sent and turns what
 * the player does into intents; it never works out a price, a balance or a fee. A new tab is one
 * subclass plus its lang keys. Coordinates are relative to the hub's body (see
 * {@link EconomyHubScreen}).
 *
 * <p>Helpers for the parts every tab shares: a list of rows that is selected by clicking, page
 * buttons, and the two-press confirm: the first press of a confirm button only arms it and the
 * view restates what will be spent and received; the second press sends. Any change to what was
 * typed or selected disarms it.
 *
 * <p>A view reaches the server only through its {@link HubIntents}: the hub hands the live one to
 * the views it shows, and a "Show me" demo ({@link HubDemo}) hands the demo's own, which sends
 * nothing. A view has no other way to send.
 */
abstract class HubTabView {
    protected final EconomyHubScreen screen;
    /** Where this view's intents go: the server, or nowhere for a demo. */
    protected final HubIntents intents;
    protected HubPage page;
    /** The row picked, by id, so a refreshed page keeps the same row picked. */
    protected UUID selected;
    /** What the confirm was armed for; empty when it is not armed. */
    protected String armedFor = "";

    // The list geometry; a view that has a list sets these in its constructor.
    protected int listX = 8;
    protected int listY = 40;
    protected int listW = 240;
    protected int rowH = 18;
    protected int visibleRows;

    /** For a demo: the made-up stack shown as "in the hand" instead of the player's own; null for a live view. */
    private ItemStack sampleHeld;

    /** When the page on screen arrived, on the client's own monotonic clock, for countdowns. */
    protected long receivedNanos = System.nanoTime();

    protected HubTabView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        this.screen = screen;
        this.intents = intents;
        this.page = page;
    }

    /** A new page for this tab has arrived. */
    void accept(HubPage next) {
        this.page = next;
        this.receivedNanos = System.nanoTime();
        if (this.selected != null && selectedRow() == null) this.selected = null;
    }

    /** A countdown the server sent as milliseconds left when the page was built, counted down since. */
    protected Component timeLeft(long millisAtReceipt) {
        TimeText.Remaining left = TimeText.remaining(TimeText.remainingNow(millisAtReceipt, this.receivedNanos,
                System.nanoTime()));
        return Component.translatable(left.key(), left.first(), left.second());
    }

    /** For a demo: the made-up stack a view shows and uses as "in the hand", so no real item is read. */
    void sampleHeld(ItemStack stack) { this.sampleHeld = stack; }

    /** What is in the player's main hand, or the demo's made-up stack. */
    protected ItemStack held() {
        if (this.sampleHeld != null) return this.sampleHeld;
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getMainHandItem();
    }

    /** A header number of the page on screen, or 0. */
    protected long header(String name) { return this.page.number(name, 0L); }

    /** A row argument that is an item id, shown by the item's own name; anything else as {@link EconomyHubScreen#arg}. */
    protected Component itemOrKey(String value, boolean item) {
        if (value == null || value.isEmpty()) return Component.empty();
        return item ? this.screen.itemName(value) : EconomyHubScreen.arg(value);
    }

    /** Draws the list's rows with {@code drawer}, and "nothing here" when there are none. */
    protected void drawRows(GuiGraphicsExtractor g, String emptyKey, RowDrawer drawer) {
        if (this.page.rows().isEmpty()) {
            // Wrapped: with no rows the list's whole area is free for it.
            this.screen.wrapped(g, Component.translatable(emptyKey), this.listX + 2, this.listY + 2, this.listW - 4,
                    TexturedContainerScreen.SUBTLE);
            return;
        }
        drawSelection(g);
        for (int index = 0; index < this.page.rows().size() && index < this.visibleRows; index++)
            drawer.draw(this.page.rows().get(index), this.listY + index * this.rowH);
    }

    /** Draws one row at a body-relative top. */
    @FunctionalInterface
    protected interface RowDrawer {
        void draw(HubPage.Row row, int top);
    }

    /** Adds the view's widgets; called whenever the hub's widgets are rebuilt. */
    abstract void init();

    /** Called once a client tick while the view is on screen. */
    void tick() { }

    abstract void draw(GuiGraphicsExtractor g);

    /** The explanation for the readout at a body-relative position, or null. */
    Component tooltipAt(int x, int y) { return null; }


    // --- the list ----------------------------------------------------------------------------

    protected HubPage.Row selectedRow() {
        if (this.selected == null) return null;
        for (HubPage.Row row : this.page.rows()) if (row.id().equals(this.selected)) return row;
        return null;
    }

    /** Picks the row under a click in the list. @return whether the click was on a row */
    boolean click(int x, int y) {
        if (this.visibleRows <= 0 || x < this.listX || x >= this.listX + this.listW || y < this.listY) return false;
        int index = (y - this.listY) / this.rowH;
        if (index < 0 || index >= this.visibleRows || index >= this.page.rows().size()) return false;
        UUID picked = this.page.rows().get(index).id();
        this.selected = picked.equals(this.selected) ? null : picked;
        disarm();
        onSelect();
        return true;
    }

    /** Called after the selection changed. */
    protected void onSelect() { }

    /** The row under a body-relative point, or null. */
    protected HubPage.Row rowAt(int x, int y) {
        if (this.visibleRows <= 0 || x < this.listX || x >= this.listX + this.listW || y < this.listY) return null;
        int index = (y - this.listY) / this.rowH;
        return index >= 0 && index < this.visibleRows && index < this.page.rows().size()
                ? this.page.rows().get(index) : null;
    }

    /** Draws the selection band behind the picked row. */
    protected void drawSelection(GuiGraphicsExtractor g) {
        for (int index = 0; index < this.page.rows().size() && index < this.visibleRows; index++) {
            if (!this.page.rows().get(index).id().equals(this.selected)) continue;
            int y = this.listY + index * this.rowH;
            this.screen.fill(g, this.listX, y, this.listW, this.rowH - 1, 0x4030D6BE);
            this.screen.fill(g, this.listX, y, 2, this.rowH - 1, TexturedContainerScreen.SELECTED);
        }
    }

    /** The previous / next page buttons, and the "page n of m" line, at a body-relative row. */
    protected void pageButtons(int x, int y) {
        this.screen.button(Component.translatable("gui.neroeconomy.hub.previous"), "gui.neroeconomy.hub.previous.tooltip",
                x, y, 20, 14, () -> {
                    if (this.page.page() > 0) this.intents.send(HubAction.PAGE, null, this.page.page() - 1L, 0L, "");
                });
        this.screen.button(Component.translatable("gui.neroeconomy.hub.next"), "gui.neroeconomy.hub.next.tooltip",
                x + 22, y, 20, 14, () -> {
                    if (this.page.page() + 1 < this.page.pageCount())
                        this.intents.send(HubAction.PAGE, null, this.page.page() + 1L, 0L, "");
                });
    }

    protected void drawPageText(GuiGraphicsExtractor g, int x, int y) {
        this.screen.text(g, Component.translatable("gui.neroeconomy.hub.page", this.page.page() + 1,
                this.page.pageCount()), x, y, TexturedContainerScreen.SUBTLE);
    }

    // --- the two-press confirm ---------------------------------------------------------------

    protected boolean armed(String what) { return !this.armedFor.isEmpty() && this.armedFor.equals(what); }

    protected void disarm() { this.armedFor = ""; }

    /**
     * One press of a confirm button for {@code what}: the first arms it, the second runs
     * {@code send} and disarms.
     */
    protected void press(String what, Runnable send) {
        if (armed(what)) {
            disarm();
            send.run();
        } else {
            this.armedFor = what;
        }
    }

    /** Labels a confirm button: its own word, or "Confirm" while armed for {@code what}. */
    protected static void label(Button button, String key, boolean armed) {
        if (button != null) button.setMessage(Component.translatable(armed ? "gui.neroeconomy.hub.confirm" : key));
    }

    // --- typed numbers -----------------------------------------------------------------------

    /** A whole number of at least 1 from a box, or -1 when it holds anything else. */
    protected static long number(EditBox box) {
        if (box == null) return -1L;
        try {
            long value = Long.parseLong(box.getValue().trim());
            return value < 1L ? -1L : value;
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    protected static String value(EditBox box) { return box == null ? "" : box.getValue().trim(); }
}
