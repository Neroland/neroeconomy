package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import za.co.neroland.neroeconomy.hub.DataPages;
import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubPages;
import za.co.neroland.neroeconomy.text.TimeText;

/**
 * The Wallet tab: balance, locked starting credits and when they unlock, credits waiting to be
 * collected, paying another player, the player's own recent activity (never naming the other
 * side), and the activity-log switch.
 *
 * <pre>
 *   20   balance                                   31   locked credits, or "all spendable"
 *   44   waiting to collect | Collect (192, 40, 56x14)
 *   58   "Pay a player"; 68: recipient (8, 68, 110x14) | amount (122, 68, 58x14) | Pay (184, 68, 64x14)
 *   86   the confirm line
 *   98   "Recent activity"; rows from 108, seven of 11px
 *   188  previous / next (8, 188) | page text (54, 191) | log switch (150, 188, 98x14)
 *   208  where to ask for an export or erasure
 * </pre>
 */
final class HubWalletView extends HubTabView {
    private EditBox recipient;
    private EditBox amount;
    private Button collect;
    private Button pay;
    private Button logging;

    HubWalletView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listY = 108;
        this.listW = 240;
        this.rowH = 11;
        this.visibleRows = HubPages.WALLET_ROWS;
    }

    /** Whether the page on screen is the "My data" summary. */
    private boolean myData() { return header(DataPages.MY_DATA) == 1L; }

    @Override
    void accept(HubPage next) {
        boolean was = myData();
        super.accept(next);
        if (was != myData()) this.screen.rebuild();
    }

    @Override
    void init() {
        if (myData()) {
            this.screen.button(Component.translatable("gui.neroeconomy.hub.data.back"),
                    "gui.neroeconomy.hub.data.back.tooltip", 8, 204, 90, 14,
                    () -> this.intents.send(HubAction.WALLET_MY_DATA, null, 0L, 0L, ""));
            return;
        }
        this.screen.button(Component.translatable("gui.neroeconomy.hub.data.open"),
                "gui.neroeconomy.hub.data.open.tooltip", 8, 204, 60, 14,
                () -> this.intents.send(HubAction.WALLET_MY_DATA, null, 1L, 0L, ""));
        this.collect = this.screen.button(Component.translatable("gui.neroeconomy.hub.wallet.collect"),
                "gui.neroeconomy.hub.wallet.collect.tooltip", 192, 40, 56, 14,
                () -> this.intents.send(HubAction.WALLET_COLLECT, null, 0L, 0L, ""));
        this.recipient = this.screen.box(this.recipient, "gui.neroeconomy.hub.wallet.recipient", 8, 68, 110, 64);
        this.amount = this.screen.box(this.amount, "gui.neroeconomy.hub.wallet.amount", 122, 68, 58, 19);
        this.pay = this.screen.button(Component.translatable("gui.neroeconomy.hub.wallet.pay"),
                "gui.neroeconomy.hub.wallet.pay.tooltip", 184, 68, 64, 14, this::pay);
        pageButtons(8, 188);
        this.logging = this.screen.button(Component.empty(), "gui.neroeconomy.hub.wallet.logging.tooltip", 150, 188,
                98, 14, () -> this.intents.send(HubAction.WALLET_LOGGING, null, header(HubPages.OPT_OUT) == 1L ? 0L : 1L,
                        0L, ""));
    }

    private String payment() { return value(this.recipient) + "|" + number(this.amount); }

    private boolean payable() { return !value(this.recipient).isEmpty() && number(this.amount) > 0L; }

    private void pay() {
        if (!payable()) return;
        String what = payment();
        press(what, () -> this.intents.send(HubAction.WALLET_PAY, null, number(this.amount), 0L, value(this.recipient)));
    }

    @Override
    void tick() {
        if (!armedFor.isEmpty() && !armed(payment())) disarm();
        if (this.collect != null) this.collect.active = header(HubPages.CLAIM) > 0L;
        if (this.pay != null) {
            this.pay.active = payable();
            label(this.pay, "gui.neroeconomy.hub.wallet.pay", armed(payment()));
        }
        if (this.logging != null) this.logging.setMessage(Component.translatable(header(HubPages.OPT_OUT) == 1L
                ? "gui.neroeconomy.hub.wallet.logging.off" : "gui.neroeconomy.hub.wallet.logging.on"));
    }

    /** The "My data" summary: one count per kind of thing kept, and how to export or erase it. */
    private void drawData(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable("gui.neroeconomy.hub.data.title"), 8, 20, 240, TexturedContainerScreen.TITLE);
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.data.intro"), 8, 31, 240, TexturedContainerScreen.SUBTLE);
        for (int index = 0; index < this.page.rows().size(); index++) {
            HubPage.Row row = this.page.rows().get(index);
            int y = DATA_TOP + index * DATA_ROW;
            s.text(g, Component.translatable(row.title()), 10, y, 196, TexturedContainerScreen.TITLE);
            s.textRight(g, Component.literal(Long.toString(row.num(0))), 246, y, TexturedContainerScreen.CREDIT);
        }
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.data.how"), 8, 176, 240, TexturedContainerScreen.SUBTLE);
    }

    private static final int DATA_TOP = 52;
    private static final int DATA_ROW = 10;

    @Override
    void draw(GuiGraphicsExtractor g) {
        if (myData()) {
            drawData(g);
            return;
        }
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable("gui.neroeconomy.hub.wallet.balance", header(HubPages.BALANCE)), 8, 20, 240,
                TexturedContainerScreen.CREDIT);
        long locked = header(HubPages.LOCKED);
        s.text(g, locked > 0L
                ? Component.translatable("gui.neroeconomy.hub.wallet.locked", locked, header(HubPages.UNLOCK_MINUTES))
                : Component.translatable("gui.neroeconomy.hub.wallet.unlocked"), 8, 31, 240,
                locked > 0L ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
        long claim = header(HubPages.CLAIM);
        s.text(g, claim > 0L ? Component.translatable("gui.neroeconomy.hub.wallet.claim", claim)
                : Component.translatable("gui.neroeconomy.hub.wallet.claim_none"), 8, 44, 180,
                claim > 0L ? TexturedContainerScreen.CREDIT : TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.wallet.pay_caption"), 8, 58, 240,
                TexturedContainerScreen.SUBTLE);
        Component confirm = armed(payment())
                ? Component.translatable("gui.neroeconomy.hub.wallet.pay_confirm", number(this.amount),
                        value(this.recipient))
                : Component.translatable("gui.neroeconomy.hub.wallet.pay_hint");
        s.text(g, confirm, 8, 86, 240, armed(payment()) ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);

        s.text(g, Component.translatable("gui.neroeconomy.hub.wallet.history"), 8, 98, 240,
                TexturedContainerScreen.TITLE);
        if (this.page.rows().isEmpty()) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.wallet.history_empty"), 10, 110, 236,
                    TexturedContainerScreen.SUBTLE);
        }
        for (int index = 0; index < this.page.rows().size() && index < this.visibleRows; index++) {
            HubPage.Row row = this.page.rows().get(index);
            int y = this.listY + index * this.rowH + 1;
            boolean incoming = row.flag(HubPage.FLAG_A);
            s.text(g, Component.literal(TimeText.utcMinutes(row.num(0))), 10, y, TexturedContainerScreen.SUBTLE);
            s.text(g, Component.translatable(row.title()), 98, y, 100, TexturedContainerScreen.TITLE);
            s.textRight(g, Component.translatable(incoming ? "gui.neroeconomy.hub.wallet.in"
                    : "gui.neroeconomy.hub.wallet.out", row.num(1)), 246, y,
                    incoming ? EconomyHubScreen.GOOD : TexturedContainerScreen.SUBTLE);
        }
        drawPageText(g, 54, 191);
        s.text(g, Component.translatable("gui.neroeconomy.hub.wallet.your_data"), 72, 208, 176,
                TexturedContainerScreen.SUBTLE);
    }

    @Override
    Component tooltipAt(int x, int y) {
        if (myData()) {
            int index = (y - DATA_TOP) / DATA_ROW;
            if (y >= DATA_TOP && x >= 8 && x < 248 && index < this.page.rows().size())
                return Component.translatable(this.page.rows().get(index).title() + ".tooltip");
            return null;
        }
        if (y >= 19 && y < 29 && x >= 8 && x < 248) return Component.translatable("gui.neroeconomy.hub.wallet.balance.tooltip");
        if (y >= 30 && y < 40 && x >= 8 && x < 248) return Component.translatable("gui.neroeconomy.hub.wallet.locked.tooltip");
        if (y >= 43 && y < 53 && x >= 8 && x < 188) return Component.translatable("gui.neroeconomy.hub.wallet.claim.tooltip");
        if (y >= 207 && y < 217 && x >= 72 && x < 248) return Component.translatable("gui.neroeconomy.hub.wallet.your_data.tooltip");
        if (y >= 108 && y < 108 + 7 * 11 && x >= 8 && x < 248) return Component.translatable("gui.neroeconomy.hub.wallet.history.tooltip");
        return null;
    }

    /** History rows are not picked; nothing can be done to them. */
    @Override
    boolean click(int x, int y) { return false; }
}
