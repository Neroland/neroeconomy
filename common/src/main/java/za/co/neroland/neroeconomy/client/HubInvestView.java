package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import za.co.neroland.neroeconomy.exchange.PriceText;
import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.InvestPages;

/**
 * The Invest tab. By default three steps — <b>Pick → How much → Confirm</b> — with plain risk
 * labels and the "what could happen in a week" strip; "Advanced" adds units, ask and bid, spread,
 * fee, sellable units and the earliest sell time. "My investments" lists the portfolio with one-tap
 * Sell (a reduced gain is its own confirm with the exact numbers) and cashing in Savings Bonds.
 * "Practice" switches to play credits under a banner that never goes away while it is on. Every
 * number is the server's; this view only draws it.
 *
 * <pre>
 *   20   Invest (8, 46) | My investments (56, 76) | Practice (134, 56) | Advanced (192, 56), all 14 high
 *   36   the practice banner (play credits and Reset at the right), or the exchange's state
 *   PICK      50: eight rows of 12px | 150: page buttons, page text | How much? (184, 150, 64x14)
 *             166..206: the picked product's price line
 *   AMOUNT    50: the product and its risk | 76..112: the price line | 118: credits box, See what I get,
 *             Back | 138, 148: the quote | 160..180: the week strip | 186: the confirm line | 202: Confirm
 *   PORTFOLIO 50, 60, 70: totals and dividend | 82: six rows of 16px | 182: page buttons | Sell or Cash in
 *             (150, 182, 98x14) | 200, 210: the confirm lines
 * </pre>
 */
final class HubInvestView extends HubTabView {
    private enum Step { PICK, AMOUNT }

    private static final int CHART_LINE = 0xFF38E6A1;
    private static final int CHART_BACK = 0x40000000;
    private static final int BANNER = 0xC0A0302A;

    private Step step = Step.PICK;
    private boolean advanced;
    private EditBox credits;
    private Button quoteButton;
    private Button confirmButton;
    private Button sellButton;
    /** The credits a quote was asked for; the page's quote only counts while this still matches. */
    private long quotedCredits = -1L;
    /** Whether the reduced-gain confirm is showing for the selected holding. */
    private boolean sellAsked;

    HubInvestView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listW = 240;
        layout();
    }

    private boolean practice() { return header(InvestPages.PRACTICE) == 1L; }

    private boolean portfolio() { return header(InvestPages.VIEW) == HubAction.InvestView.PORTFOLIO.ordinal(); }

    private void layout() {
        if (portfolio()) {
            this.listY = 82;
            this.rowH = 16;
            this.visibleRows = InvestPages.PORTFOLIO_ROWS;
        } else {
            this.listY = 50;
            this.rowH = 12;
            this.visibleRows = this.step == Step.PICK ? InvestPages.PRODUCT_ROWS : 0;
        }
    }

    @Override
    void accept(HubPage next) {
        boolean wasPortfolio = portfolio();
        boolean wasPractice = practice();
        super.accept(next);
        if (wasPortfolio != portfolio() || wasPractice != practice()) {
            this.step = Step.PICK;
            this.selected = null;
            this.sellAsked = false;
            disarm();
            layout();
            this.screen.rebuild();
        }
    }

    @Override
    void init() {
        layout();
        Button products = this.screen.button(Component.translatable("gui.neroeconomy.hub.invest.products"),
                "gui.neroeconomy.hub.invest.products.tooltip", 8, 20, 36, 14,
                () -> this.intents.send(HubAction.INVEST_VIEW, null, HubAction.InvestView.PRODUCTS.ordinal(), 0L, ""));
        products.active = portfolio();
        Button mine = this.screen.button(Component.translatable("gui.neroeconomy.hub.invest.portfolio"),
                "gui.neroeconomy.hub.invest.portfolio.tooltip", 46, 20, 62, 14,
                () -> this.intents.send(HubAction.INVEST_VIEW, null, HubAction.InvestView.PORTFOLIO.ordinal(), 0L, ""));
        mine.active = !portfolio();
        this.screen.button(Component.translatable(practice() ? "gui.neroeconomy.hub.invest.practice.on"
                : "gui.neroeconomy.hub.invest.practice.off"), "gui.neroeconomy.hub.invest.practice.tooltip", 110, 20, 70,
                14, () -> this.intents.send(HubAction.INVEST_PRACTICE, null, practice() ? 0L : 1L, 0L, ""));
        this.screen.button(Component.translatable(this.advanced ? "gui.neroeconomy.hub.invest.advanced.on"
                : "gui.neroeconomy.hub.invest.advanced.off"), "gui.neroeconomy.hub.invest.advanced.tooltip", 182, 20, 66,
                14, () -> {
                    this.advanced = !this.advanced;
                    this.screen.rebuild();
                });
        if (practice()) {
            Button reset = this.screen.button(Component.translatable("gui.neroeconomy.hub.invest.reset"),
                    "gui.neroeconomy.hub.invest.reset.tooltip", 200, 35, 46, 12,
                    () -> press("reset", () -> this.intents.send(HubAction.INVEST_RESET, null, 0L, 0L, "")));
            reset.active = header(InvestPages.CAN_RESET) == 1L;
        }
        this.quoteButton = null;
        this.confirmButton = null;
        this.sellButton = null;
        if (portfolio()) {
            pageButtons(8, 182);
            this.sellButton = this.screen.button(Component.empty(), "gui.neroeconomy.hub.invest.sell.tooltip", 150, 182,
                    98, 14, this::sellOrCashIn);
            return;
        }
        if (this.step == Step.PICK) {
            pageButtons(8, 150);
            this.screen.button(Component.translatable("gui.neroeconomy.hub.invest.next"),
                    "gui.neroeconomy.hub.invest.next.tooltip", 184, 150, 64, 14, () -> {
                        if (header(InvestPages.PICKED) != 1L || selectedRow() == null) return;
                        this.step = Step.AMOUNT;
                        this.quotedCredits = -1L;
                        disarm();
                        layout();
                        this.screen.rebuild();
                    });
            return;
        }
        this.credits = this.screen.box(this.credits, "gui.neroeconomy.hub.invest.credits", 8, 118, 70, 13);
        this.quoteButton = this.screen.button(Component.translatable("gui.neroeconomy.hub.invest.quote"),
                "gui.neroeconomy.hub.invest.quote.tooltip", 82, 118, 96, 14, this::quote);
        this.screen.button(Component.translatable("gui.neroeconomy.hub.invest.back"),
                "gui.neroeconomy.hub.invest.back.tooltip", 182, 118, 66, 14, () -> {
                    this.step = Step.PICK;
                    disarm();
                    layout();
                    this.screen.rebuild();
                });
        this.confirmButton = this.screen.button(Component.empty(), "gui.neroeconomy.hub.invest.confirm.tooltip", 8, 202,
                120, 14, this::confirm);
    }

    @Override
    protected void onSelect() {
        HubPage.Row row = selectedRow();
        this.sellAsked = false;
        // Picking a product asks the server for its price line; picking a holding asks for nothing yet.
        if (row != null && !portfolio()) this.intents.send(HubAction.INVEST_PICK, row.id(), 0L, 0L, "");
    }

    // --- the steps ---------------------------------------------------------------------------

    private boolean quoteFor(HubAction action) {
        return this.page.number(InvestPages.Q_FOR, -1L) == action.ordinal();
    }

    /** Whether the page's quote is for exactly the credits typed now. */
    private boolean quoteShown() {
        return quoteFor(HubAction.INVEST_QUOTE) && this.quotedCredits > 0L
                && this.quotedCredits == number(this.credits) && header(InvestPages.Q_ASKED) == this.quotedCredits;
    }

    private boolean quoteOk() { return quoteShown() && header(InvestPages.Q_OK) == 1L; }

    private void quote() {
        long amount = number(this.credits);
        if (amount <= 0L) return;
        this.quotedCredits = amount;
        disarm();
        this.intents.send(HubAction.INVEST_QUOTE, null, amount, 0L, "");
    }

    private void confirm() {
        if (!quoteOk()) return;
        long amount = this.quotedCredits;
        press("buy|" + amount, () -> {
            this.intents.send(HubAction.INVEST_BUY, null, amount, 0L, "");
            this.quotedCredits = -1L;
        });
    }

    // --- the portfolio -----------------------------------------------------------------------

    private boolean sellQuoteShown(HubPage.Row row) {
        return row != null && this.sellAsked && quoteFor(HubAction.INVEST_SELL_QUOTE) && header(InvestPages.Q_ASKED) == 0L;
    }

    private boolean reduced() { return header(InvestPages.Q_REDUCED) == 1L; }

    private void sellOrCashIn() {
        HubPage.Row row = selectedRow();
        if (row == null) return;
        if (row.flag(InvestPages.FLAG_BOND)) {
            press("redeem|" + row.id(), () -> this.intents.send(HubAction.INVEST_REDEEM, row.id(), 0L, 0L, ""));
            return;
        }
        if (!sellQuoteShown(row)) {
            // The first tap asks the server what selling all of it comes to; the confirm follows.
            this.sellAsked = true;
            disarm();
            this.intents.send(HubAction.INVEST_SELL_QUOTE, row.id(), 0L, 0L, "");
            return;
        }
        if (header(InvestPages.Q_OK) != 1L) return;
        long accepted = reduced() ? header(InvestPages.Q_PAYABLE) : -1L;
        press("sell|" + row.id() + "|" + accepted, () -> {
            this.intents.send(HubAction.INVEST_SELL, row.id(), 0L, accepted, "");
            this.sellAsked = false;
        });
    }

    @Override
    void tick() {
        if (this.confirmButton != null) {
            if (armed("buy|" + this.quotedCredits) && !quoteOk()) disarm();
            this.confirmButton.active = quoteOk();
            label(this.confirmButton, "gui.neroeconomy.hub.invest.confirm", armed("buy|" + this.quotedCredits));
        }
        if (this.quoteButton != null) this.quoteButton.active = number(this.credits) > 0L;
        if (this.sellButton != null) {
            HubPage.Row row = selectedRow();
            if (row == null) {
                this.sellButton.active = false;
                this.sellButton.setMessage(Component.translatable("gui.neroeconomy.hub.invest.sell"));
            } else if (row.flag(InvestPages.FLAG_BOND)) {
                this.sellButton.active = true;
                label(this.sellButton, "gui.neroeconomy.hub.invest.cash_in", armed("redeem|" + row.id()));
            } else {
                boolean shown = sellQuoteShown(row);
                this.sellButton.active = !shown || header(InvestPages.Q_OK) == 1L;
                String key = !shown ? "gui.neroeconomy.hub.invest.sell"
                        : reduced() ? "gui.neroeconomy.hub.invest.accept_less" : "gui.neroeconomy.hub.invest.sell_confirm";
                long accepted = reduced() ? header(InvestPages.Q_PAYABLE) : -1L;
                label(this.sellButton, key, armed("sell|" + row.id() + "|" + accepted));
            }
        }
    }

    // --- drawing -----------------------------------------------------------------------------

    private static Component credits(long value) { return Component.translatable("gui.neroeconomy.hub.credits", value); }

    private static Component price(long cents) { return Component.literal(PriceText.credits(cents)); }

    private static Component signed(long value) {
        return Component.literal(value > 0L ? "+" + value : Long.toString(value));
    }

    /** The short plain label of a risk ("Steady", "Risky", ...). */
    private static Component risk(String riskKey) {
        return riskKey == null || riskKey.isEmpty() ? Component.empty() : Component.translatable(riskKey + ".short");
    }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        if (practice()) {
            s.fill(g, 8, 35, 240, 12, BANNER);
            s.text(g, Component.translatable(this.page.key(InvestPages.BANNER),
                    header(InvestPages.PLAY_BALANCE)), 11, 37, 186, TexturedContainerScreen.TITLE);
        } else if (header(InvestPages.EXCHANGE_ON) != 1L) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.closed"), 8, 37, 240, EconomyHubScreen.WARNING);
        } else {
            s.text(g, Component.translatable(portfolio() ? "gui.neroeconomy.hub.invest.portfolio_hint"
                    : this.step == Step.PICK ? "gui.neroeconomy.hub.invest.step1" : "gui.neroeconomy.hub.invest.step2"),
                    8, 37, 240, TexturedContainerScreen.SUBTLE);
        }
        if (portfolio()) drawPortfolio(g);
        else if (this.step == Step.PICK) drawPick(g);
        else drawAmount(g);
        if (armed("reset")) s.text(g, Component.translatable("gui.neroeconomy.hub.invest.reset_confirm"), 8, 212, 240,
                EconomyHubScreen.WARNING);
    }

    private void drawPick(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        drawRows(g, "gui.neroeconomy.hub.invest.none", (row, top) -> {
            int y = top + 2;
            boolean picked = row.id().equals(this.selected);
            boolean bond = row.flag(InvestPages.FLAG_BOND);
            s.text(g, Component.translatable(row.title()), 10, y, 104, picked ? TexturedContainerScreen.SELECTED
                    : TexturedContainerScreen.TITLE);
            s.text(g, risk(row.arg(0)), 116, y, 64, row.flag(InvestPages.FLAG_STOPPED) ? EconomyHubScreen.BAD
                    : TexturedContainerScreen.SUBTLE);
            Component right;
            if (bond) right = Component.translatable("gui.neroeconomy.hub.invest.bond_rate",
                    PriceText.percent((int) row.num(0)), row.num(1));
            else if (row.flag(InvestPages.FLAG_STOPPED)) right = Component.translatable("gui.neroeconomy.hub.invest.paused");
            else if (this.advanced) right = Component.translatable("gui.neroeconomy.hub.invest.ask_bid", price(row.num(0)),
                    price(row.num(1)));
            else right = Component.translatable("gui.neroeconomy.hub.invest.each", price(row.num(0)));
            s.textRight(g, right, 246, y, bond ? EconomyHubScreen.GOOD : TexturedContainerScreen.CREDIT);
        });
        drawPageText(g, 54, 153);
        if (header(InvestPages.PICKED) == 1L) {
            if (header(InvestPages.PICK_BOND) == 1L) {
                s.wrapped(g, Component.translatable(this.page.key(InvestPages.PICK_NAME) + ".description"), 8, 170, 240,
                        TexturedContainerScreen.SUBTLE);
            } else {
                drawChart(g, 8, 168, 240, 38);
            }
        } else {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.pick_hint"), 8, 170, 240,
                    TexturedContainerScreen.SUBTLE);
        }
    }

    private void drawAmount(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        String name = this.page.key(InvestPages.PICK_NAME);
        s.text(g, name.isEmpty() ? Component.empty() : Component.translatable(name), 8, 50, 240,
                TexturedContainerScreen.TITLE);
        String riskKey = this.page.key(InvestPages.PICK_RISK);
        s.text(g, riskKey.isEmpty() ? Component.empty() : Component.translatable(riskKey), 8, 59, 240,
                TexturedContainerScreen.SUBTLE);
        String status = this.page.key(InvestPages.PICK_STATUS);
        if (!status.isEmpty()) s.text(g, Component.translatable(status, 0L, 0L), 8, 68, 240, EconomyHubScreen.BAD);
        if (header(InvestPages.PICK_BOND) != 1L) drawChart(g, 8, 78, 240, 34);
        else s.wrapped(g, Component.translatable(name + ".description"), 8, 80, 240, TexturedContainerScreen.SUBTLE);
        boolean bond = header(InvestPages.PICK_BOND) == 1L;
        if (!quoteShown()) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.amount_hint"), 8, 138, 240,
                    TexturedContainerScreen.SUBTLE);
            return;
        }
        if (!quoteOk()) {
            String reason = this.page.key(InvestPages.Q_REASON);
            s.wrapped(g, reason.isEmpty() ? Component.translatable("gui.neroeconomy.hub.invest.cannot")
                    : Component.translatable(reason, 0L, 0L), 8, 138, 240, EconomyHubScreen.BAD);
            return;
        }
        if (bond) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.quote_bond", header(InvestPages.Q_TOTAL),
                    header(InvestPages.Q_GAIN), header(InvestPages.Q_UNITS)), 8, 138, 240, TexturedContainerScreen.CREDIT);
        } else {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.quote_buy", header(InvestPages.Q_NET),
                    header(InvestPages.Q_FEE)), 8, 138, 240, TexturedContainerScreen.CREDIT);
            s.text(g, this.advanced
                    ? Component.translatable("gui.neroeconomy.hub.invest.quote_units", header(InvestPages.Q_UNITS),
                            price(header(InvestPages.Q_UNIT)), header(InvestPages.Q_TOTAL), header(InvestPages.Q_FEE))
                    : Component.translatable("gui.neroeconomy.hub.invest.quote_units_plain", header(InvestPages.Q_UNITS)),
                    8, 148, 240, TexturedContainerScreen.SUBTLE);
        }
        if (header(InvestPages.O_SHOWN) == 1L) drawWeek(g, 8, 160);
        if (armed("buy|" + this.quotedCredits)) {
            Component line = bond
                    ? Component.translatable("gui.neroeconomy.hub.invest.confirm_bond", header(InvestPages.Q_TOTAL),
                            Component.translatable(name), header(InvestPages.Q_GAIN))
                    : Component.translatable("gui.neroeconomy.hub.invest.confirm_buy", header(InvestPages.Q_NET),
                            header(InvestPages.Q_UNITS), Component.translatable(name));
            s.text(g, line, 8, 190, 240, EconomyHubScreen.WARNING);
        }
    }

    /** The "what could happen in a week" strip: bad, typical and good, in credits back. */
    private void drawWeek(GuiGraphicsExtractor g, int x, int y) {
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable("gui.neroeconomy.hub.invest.week", header(InvestPages.O_DAYS)), x, y, 240,
                TexturedContainerScreen.SUBTLE);
        String[] labels = { "gui.neroeconomy.hub.invest.week.bad", "gui.neroeconomy.hub.invest.week.typical",
                "gui.neroeconomy.hub.invest.week.good" };
        long[] values = { header(InvestPages.O_BAD), header(InvestPages.O_TYPICAL), header(InvestPages.O_GOOD) };
        int[] colours = { EconomyHubScreen.BAD, TexturedContainerScreen.TITLE, EconomyHubScreen.GOOD };
        for (int index = 0; index < 3; index++) {
            int left = x + index * 80;
            s.fill(g, left, y + 10, 78, 18, CHART_BACK);
            s.text(g, Component.translatable(labels[index]), left + 3, y + 11, 72, TexturedContainerScreen.SUBTLE);
            s.text(g, credits(values[index]), left + 3, y + 20, 72, colours[index]);
        }
    }

    /**
     * The price line: the recent prices on the left, the daily history on the right, each drawn as
     * one-pixel fills scaled to its own range. A split is marked with a yellow line on the day it
     * happened; a halted share with a red end mark.
     */
    private void drawChart(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        EconomyHubScreen s = this.screen;
        long[] series = this.page.series();
        int recent = (int) Math.max(0L, Math.min(series.length, header(InvestPages.CHART_RECENT_COUNT)));
        if (series.length == 0) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.no_chart"), x, y + 2, w,
                    TexturedContainerScreen.SUBTLE);
            return;
        }
        int recentW = w * 3 / 5 - 2;
        int dailyW = w - recentW - 4;
        long[] first = java.util.Arrays.copyOfRange(series, 0, recent);
        long[] second = java.util.Arrays.copyOfRange(series, recent, series.length);
        line(g, first, x, y, recentW, h, -1);
        line(g, second, x + recentW + 4, y, dailyW, h, (int) header(InvestPages.CHART_SPLIT));
        s.text(g, Component.translatable("gui.neroeconomy.hub.invest.chart_recent"), x + 2, y + 1, recentW,
                TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.invest.chart_daily"), x + recentW + 6, y + 1, dailyW,
                TexturedContainerScreen.SUBTLE);
        if (header(InvestPages.CHART_HALTED) == 1L) {
            s.fill(g, x + recentW - 3, y, 3, h, EconomyHubScreen.BAD);
            s.textRight(g, Component.translatable("gui.neroeconomy.hub.invest.paused"), x + recentW - 5, y + h - 9,
                    EconomyHubScreen.BAD);
        }
    }

    private void line(GuiGraphicsExtractor g, long[] points, int x, int y, int w, int h, int marker) {
        EconomyHubScreen s = this.screen;
        s.fill(g, x, y, w, h, CHART_BACK);
        if (points.length == 0 || w < 2) return;
        long low = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        for (long point : points) {
            low = Math.min(low, point);
            high = Math.max(high, point);
        }
        long range = Math.max(1L, high - low);
        int top = y + 10;
        int height = Math.max(2, h - 12);
        int previous = -1;
        for (int column = 0; column < w; column++) {
            int index = points.length == 1 ? 0 : (int) ((long) column * (points.length - 1) / Math.max(1, w - 1));
            int py = top + height - 1 - (int) ((points[index] - low) * (height - 1) / range);
            int from = previous < 0 ? py : Math.min(previous, py);
            int to = previous < 0 ? py : Math.max(previous, py);
            s.fill(g, x + column, from, 1, to - from + 1, CHART_LINE);
            previous = py;
        }
        if (marker >= 0 && marker < points.length && points.length > 1) {
            int mx = x + (int) ((long) marker * (w - 1) / (points.length - 1));
            s.fill(g, mx, y + 9, 1, h - 9, EconomyHubScreen.WARNING);
        }
    }

    private void drawPortfolio(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        long difference = header(InvestPages.P_DIFFERENCE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.invest.totals", header(InvestPages.P_COST),
                header(InvestPages.P_VALUE), signed(difference)), 8, 50, 240,
                difference < 0L ? EconomyHubScreen.BAD : difference > 0L ? EconomyHubScreen.GOOD : TexturedContainerScreen.TITLE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.invest.limits", header(InvestPages.P_BONDS),
                header(InvestPages.P_INVESTED), header(InvestPages.P_LIMIT)), 8, 60, 240, TexturedContainerScreen.SUBTLE);
        Component dividend = header(InvestPages.P_LAST_DIVIDEND) <= 0L
                ? Component.translatable("gui.neroeconomy.hub.invest.dividend_none")
                : header(InvestPages.P_DIVIDEND_SCALED) == 1L
                        ? Component.translatable("gui.neroeconomy.hub.invest.dividend_scaled",
                                header(InvestPages.P_LAST_DIVIDEND), header(InvestPages.P_DIVIDEND_PERCENT))
                        : Component.translatable("gui.neroeconomy.hub.invest.dividend", header(InvestPages.P_LAST_DIVIDEND));
        s.text(g, dividend, 8, 70, 240, TexturedContainerScreen.SUBTLE);
        drawRows(g, "gui.neroeconomy.hub.invest.portfolio_empty", (row, top) -> {
            boolean picked = row.id().equals(this.selected);
            int colour = picked ? TexturedContainerScreen.SELECTED : TexturedContainerScreen.TITLE;
            if (row.flag(InvestPages.FLAG_BOND)) {
                s.text(g, Component.translatable(row.title()), 10, top + 1, 120, colour);
                s.textRight(g, Component.translatable("gui.neroeconomy.hub.invest.bond_held", row.num(0), row.num(1)), 246,
                        top + 1, EconomyHubScreen.GOOD);
                s.text(g, row.flag(InvestPages.FLAG_MATURED) ? Component.translatable("gui.neroeconomy.hub.invest.matured")
                        : Component.translatable("gui.neroeconomy.hub.invest.matures", timeLeft(row.num(2))), 10, top + 8, 236,
                        TexturedContainerScreen.SUBTLE);
                return;
            }
            s.text(g, Component.translatable(row.title()), 10, top + 1, 110, colour);
            long diff = row.num(3);
            s.textRight(g, Component.translatable("gui.neroeconomy.hub.invest.worth", row.num(2), signed(diff)), 246,
                    top + 1, diff < 0L ? EconomyHubScreen.BAD : diff > 0L ? EconomyHubScreen.GOOD : TexturedContainerScreen.TITLE);
            Component second;
            if (!row.arg(0).isEmpty()) second = Component.translatable(row.arg(0), 0L, 0L);
            else if (this.advanced) second = row.num(5) > 0L
                    ? Component.translatable("gui.neroeconomy.hub.invest.units_wait", row.num(0), row.num(4), timeLeft(row.num(5)))
                    : Component.translatable("gui.neroeconomy.hub.invest.units", row.num(0), row.num(4));
            else second = Component.translatable("gui.neroeconomy.hub.invest.put_in", row.num(1));
            s.text(g, second, 10, top + 8, 236, row.arg(0).isEmpty() ? TexturedContainerScreen.SUBTLE : EconomyHubScreen.BAD);
        });
        drawPageText(g, 54, 185);
        HubPage.Row row = selectedRow();
        if (row == null) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.portfolio_pick"), 8, 200, 240,
                    TexturedContainerScreen.SUBTLE);
            return;
        }
        if (row.flag(InvestPages.FLAG_BOND)) {
            Component line = row.flag(InvestPages.FLAG_MATURED)
                    ? Component.translatable("gui.neroeconomy.hub.invest.cash_in_matured", row.num(0) + row.num(1))
                    : Component.translatable("gui.neroeconomy.hub.invest.cash_in_early", row.num(0), row.num(1));
            s.wrapped(g, line, 8, 200, 240, armed("redeem|" + row.id()) ? EconomyHubScreen.WARNING
                    : TexturedContainerScreen.SUBTLE);
            return;
        }
        if (!sellQuoteShown(row)) {
            s.text(g, Component.translatable("gui.neroeconomy.hub.invest.sell_hint"), 8, 200, 240,
                    TexturedContainerScreen.SUBTLE);
            return;
        }
        if (header(InvestPages.Q_OK) != 1L) {
            String reason = this.page.key(InvestPages.Q_REASON);
            Component why = reason.isEmpty() ? Component.translatable("gui.neroeconomy.hub.invest.cannot")
                    : Component.translatable(reason, header(InvestPages.Q_PAYABLE), header(InvestPages.Q_GAIN));
            s.wrapped(g, why, 8, 200, 240, EconomyHubScreen.BAD);
            return;
        }
        long accepted = reduced() ? header(InvestPages.Q_PAYABLE) : -1L;
        int colour = armed("sell|" + row.id() + "|" + accepted) ? EconomyHubScreen.WARNING : TexturedContainerScreen.TITLE;
        if (reduced()) {
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.invest.reduced", header(InvestPages.Q_PAYABLE),
                    header(InvestPages.Q_GAIN), header(InvestPages.Q_FORFEIT), header(InvestPages.Q_NET)), 8, 200, 240,
                    colour);
        } else if (header(InvestPages.Q_LOSS) > 0L) {
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.invest.sell_loss", header(InvestPages.Q_UNITS),
                    header(InvestPages.Q_NET), header(InvestPages.Q_LOSS), header(InvestPages.Q_FEE)), 8, 200, 240, colour);
        } else {
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.invest.sell_gain", header(InvestPages.Q_UNITS),
                    header(InvestPages.Q_NET), header(InvestPages.Q_GAIN), header(InvestPages.Q_FEE)), 8, 200, 240, colour);
        }
    }

    @Override
    Component tooltipAt(int x, int y) {
        if (practice() && y >= 35 && y < 47 && x < 198) return Component.translatable("gui.neroeconomy.hub.invest.practice_banner.tooltip");
        HubPage.Row row = rowAt(x, y);
        if (row == null) {
            if (!portfolio() && this.step == Step.AMOUNT && y >= 160 && y < 190)
                return Component.translatable("gui.neroeconomy.hub.invest.week.tooltip");
            if (portfolio() && y >= 50 && y < 80) return Component.translatable("gui.neroeconomy.hub.invest.totals.tooltip");
            return null;
        }
        if (portfolio()) return row.flag(InvestPages.FLAG_BOND)
                ? Component.translatable("gui.neroeconomy.hub.invest.bond_held.tooltip")
                : Component.translatable("gui.neroeconomy.hub.invest.holding.tooltip", row.num(0), row.num(1), row.num(2),
                        row.num(4));
        if (row.flag(InvestPages.FLAG_BOND)) return Component.translatable(row.title() + ".description");
        Component risk = row.arg(0).isEmpty() ? Component.empty() : Component.translatable(row.arg(0));
        return this.advanced ? Component.translatable("gui.neroeconomy.hub.invest.product.tooltip_advanced", risk,
                price(row.num(0)), price(row.num(1)), price(row.num(2)), PriceText.percent((int) row.num(3)),
                PriceText.percent((int) row.num(4)))
                : Component.translatable("gui.neroeconomy.hub.invest.product.tooltip", risk);
    }
}
