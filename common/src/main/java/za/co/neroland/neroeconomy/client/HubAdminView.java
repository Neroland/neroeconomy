package za.co.neroland.neroeconomy.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import za.co.neroland.neroeconomy.exchange.PriceText;
import za.co.neroland.neroeconomy.hub.AdminPages;
import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubRouter;

/**
 * The Admin tab, for level-3 operators: the credit supply, the simulation and the market freeze,
 * the commodity, company and House tables, the data-pack check, the store's health, and the player
 * data tools for one account the operator types. Everything shown is what the server sent;
 * everything that changes the economy is a two-press confirm whose first press restates what will
 * happen, and accepting a fresh start takes three presses.
 *
 * <pre>
 *   20, 33  eight view buttons, four a row (60x12 each, 61px apart)
 *   47      the state line: market closed, economy frozen, changes switched off
 *   58..    the view (see each draw method); tables page at (8, 208)
 * </pre>
 */
final class HubAdminView extends HubTabView {
    private static final String[] VIEW_KEYS = { "supply", "sim", "commodities", "stocks", "houses", "check", "health",
            "player" };

    private EditBox steps;
    private EditBox account;
    private EditBox amount;
    private Button runSteps;
    private Button marketSwitch;
    private Button accept;
    private Button export;
    private Button erase;
    private Button grant;
    private Button take;
    private Button set;
    private final List<Button> viewButtons = new ArrayList<>();
    private long shownView = -1L;

    HubAdminView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        layout();
    }

    private HubAction.AdminView view() { return HubAction.AdminView.byOrdinal(header(AdminPages.VIEW)); }

    private boolean on(String name) { return header(name) == 1L; }

    /** Where the view's list is, so a click picks nothing on a view without one. */
    private void layout() {
        this.listX = 8;
        this.listW = 240;
        switch (view()) {
            case SUPPLY -> { this.listY = 118; this.rowH = 10; this.visibleRows = AdminPages.TABLE_ROWS; }
            case SIM -> { this.listY = 142; this.rowH = 9; this.visibleRows = AdminPages.TABLE_ROWS; }
            case COMMODITIES, STOCKS, HOUSES -> { this.listY = 58; this.rowH = 16; this.visibleRows = AdminPages.TABLE_ROWS; }
            case CHECK -> { this.listY = 124; this.rowH = 9; this.visibleRows = AdminPages.TABLE_ROWS; }
            default -> this.visibleRows = 0;
        }
    }

    @Override
    void accept(HubPage next) {
        super.accept(next);
        layout();
        if (header(AdminPages.VIEW) != this.shownView) {
            disarm();
            this.screen.rebuild();
        }
    }

    @Override
    void init() {
        this.shownView = header(AdminPages.VIEW);
        this.viewButtons.clear();
        for (int index = 0; index < VIEW_KEYS.length; index++) {
            int ordinal = index;
            String key = "gui.neroeconomy.hub.admin.view." + VIEW_KEYS[index];
            Button button = this.screen.button(Component.translatable(key), key + ".tooltip", 8 + (index % 4) * 61,
                    20 + (index / 4) * 13, 60, 12, () -> this.intents.send(HubAction.ADMIN_VIEW, null, ordinal, 0L, ""));
            button.active = ordinal != view().ordinal();
            this.viewButtons.add(button);
        }
        this.runSteps = this.marketSwitch = this.accept = this.export = this.erase = this.grant = this.take = this.set = null;
        switch (view()) {
            case SUPPLY, COMMODITIES, STOCKS, HOUSES -> pageButtons(8, 208);
            case CHECK -> {
                pageButtons(8, 208);
                this.screen.button(Component.translatable("gui.neroeconomy.hub.admin.check.run"),
                        "gui.neroeconomy.hub.admin.check.run.tooltip", 128, 208, 120, 14,
                        () -> this.intents.send(HubAction.ADMIN_RELOAD_CHECK, null, 0L, 0L, ""));
            }
            case SIM -> initSim();
            case HEALTH -> this.accept = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.health.accept.tooltip",
                    8, 150, 240, 14, () -> {
                        if (armed("accept2")) {
                            disarm();
                            this.intents.send(HubAction.ADMIN_ACCEPT_FRESH, null, 0L, 0L, "");
                        } else if (armed("accept1")) this.armedFor = "accept2";
                        else this.armedFor = "accept1";
                    });
            case PLAYER -> initPlayer();
        }
    }

    private void initSim() {
        this.steps = this.screen.box(this.steps, "gui.neroeconomy.hub.admin.sim.steps", 8, 110, 40, 3);
        this.runSteps = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.sim.run.tooltip", 52, 110, 92,
                14, () -> {
                    long count = number(this.steps);
                    if (count < 1L || count > HubRouter.MAX_ADMIN_STEPS) return;
                    press("steps|" + count, () -> this.intents.send(HubAction.ADMIN_SIM_STEP, null, count, 0L, ""));
                });
        this.marketSwitch = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.sim.market.tooltip", 148,
                110, 100, 14, () -> {
                    long close = on(AdminPages.MARKET_CLOSED) ? 0L : 1L;
                    press("market|" + close, () -> this.intents.send(HubAction.ADMIN_MARKET_FREEZE, null, close, 0L, ""));
                });
    }

    private void initPlayer() {
        this.account = this.screen.box(this.account, "gui.neroeconomy.hub.admin.player.account", 8, 72, 150,
                HubRouter.MAX_RECIPIENT);
        this.screen.button(Component.translatable("gui.neroeconomy.hub.admin.player.look_up"),
                "gui.neroeconomy.hub.admin.player.look_up.tooltip", 162, 72, 86, 14, () -> {
                    String typed = value(this.account);
                    if (!typed.isEmpty()) this.intents.send(HubAction.ADMIN_TARGET, null, 0L, 0L, typed);
                });
        this.export = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.player.export.tooltip", 8, 106,
                118, 14, () -> act("export", HubAction.ADMIN_EXPORT, 0L));
        this.erase = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.player.erase.tooltip", 130, 106,
                118, 14, () -> act("erase", HubAction.ADMIN_ERASE, 0L));
        this.amount = this.screen.box(this.amount, "gui.neroeconomy.hub.admin.player.amount", 8, 124, 60, 13);
        this.grant = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.player.grant.tooltip", 72, 124, 56,
                14, () -> act("grant", HubAction.ADMIN_GRANT, number(this.amount)));
        this.take = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.player.take.tooltip", 132, 124, 56,
                14, () -> act("take", HubAction.ADMIN_TAKE, number(this.amount)));
        this.set = this.screen.button(Component.empty(), "gui.neroeconomy.hub.admin.player.set_balance.tooltip", 192,
                124, 56, 14, () -> act("set", HubAction.ADMIN_SET, setAmount()));
    }

    /** The target row: the account the operator looked up, as they typed it. */
    private HubPage.Row target() { return this.page.rows().isEmpty() ? null : this.page.rows().get(0); }

    /** A balance to set: any whole number from 0; -1 when the box holds anything else. */
    private long setAmount() {
        try {
            long value = Long.parseLong(value(this.amount));
            return value < 0L ? -1L : value;
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    private String what(String kind, long value) {
        HubPage.Row row = target();
        return row == null ? "" : kind + "|" + row.id() + "|" + value;
    }

    private void act(String kind, HubAction action, long value) {
        HubPage.Row row = target();
        if (row == null || value < 0L || (value == 0L && (action == HubAction.ADMIN_GRANT || action == HubAction.ADMIN_TAKE)))
            return;
        press(what(kind, value), () -> this.intents.send(action, row.id(), value, 0L, ""));
    }

    @Override
    void tick() {
        boolean mutations = on(AdminPages.MUTATIONS);
        switch (view()) {
            case SIM -> {
                long count = number(this.steps);
                boolean closed = on(AdminPages.MARKET_CLOSED);
                if (!this.armedFor.isEmpty() && !armed("steps|" + count) && !armed("market|" + (closed ? 0 : 1))) disarm();
                if (this.runSteps != null) {
                    this.runSteps.active = mutations && !on(AdminPages.FROZEN) && count >= 1L
                            && count <= HubRouter.MAX_ADMIN_STEPS;
                    label(this.runSteps, "gui.neroeconomy.hub.admin.sim.run", armed("steps|" + count));
                }
                if (this.marketSwitch != null) {
                    this.marketSwitch.active = mutations;
                    label(this.marketSwitch, closed ? "gui.neroeconomy.hub.admin.sim.open" : "gui.neroeconomy.hub.admin.sim.close",
                            armed("market|" + (closed ? 0 : 1)));
                }
            }
            case HEALTH -> {
                if (this.accept != null) {
                    this.accept.active = mutations && on(AdminPages.FROZEN);
                    if (!this.accept.active) disarm();
                    this.accept.setMessage(Component.translatable(armed("accept2") ? "gui.neroeconomy.hub.admin.health.accept_last"
                            : armed("accept1") ? "gui.neroeconomy.hub.confirm" : "gui.neroeconomy.hub.admin.health.accept"));
                }
            }
            case PLAYER -> tickPlayer(mutations);
            default -> { }
        }
    }

    private void tickPlayer(boolean mutations) {
        HubPage.Row row = target();
        long value = number(this.amount);
        long balance = setAmount();
        if (!this.armedFor.isEmpty() && !armed(what("export", 0L)) && !armed(what("erase", 0L))
                && !armed(what("grant", value)) && !armed(what("take", value)) && !armed(what("set", balance))) disarm();
        boolean known = row != null && row.flag(AdminPages.FLAG_KNOWN);
        boolean erasable = row != null && row.flag(AdminPages.FLAG_ERASABLE);
        if (this.export != null) {
            this.export.active = erasable;
            label(this.export, "gui.neroeconomy.hub.admin.player.export", armed(what("export", 0L)));
        }
        if (this.erase != null) {
            this.erase.active = erasable;
            label(this.erase, "gui.neroeconomy.hub.admin.player.erase", armed(what("erase", 0L)));
        }
        if (this.grant != null) {
            this.grant.active = known && mutations && value >= 1L;
            label(this.grant, "gui.neroeconomy.hub.admin.player.grant", armed(what("grant", value)));
        }
        if (this.take != null) {
            this.take.active = known && mutations && value >= 1L;
            label(this.take, "gui.neroeconomy.hub.admin.player.take", armed(what("take", value)));
        }
        if (this.set != null) {
            this.set.active = known && mutations && balance >= 0L;
            label(this.set, "gui.neroeconomy.hub.admin.player.set_balance", armed(what("set", balance)));
        }
    }

    // --- drawing -----------------------------------------------------------------------------

    private static Component yesNo(boolean passed) {
        return Component.translatable(passed ? "command.neroeconomy.check.passed" : "command.neroeconomy.check.failed");
    }

    private static Component millis(long value) {
        long whole = value / 1000L;
        long part = Math.abs(value % 1000L);
        return Component.literal(whole + "." + (part < 10 ? "00" : part < 100 ? "0" : "") + part);
    }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        Component state;
        int colour = EconomyHubScreen.GOOD;
        if (on(AdminPages.FROZEN)) {
            state = Component.translatable("gui.neroeconomy.hub.admin.state.frozen");
            colour = EconomyHubScreen.BAD;
        } else if (on(AdminPages.MARKET_CLOSED)) {
            state = Component.translatable("gui.neroeconomy.hub.admin.state.closed");
            colour = EconomyHubScreen.WARNING;
        } else state = Component.translatable("gui.neroeconomy.hub.admin.state.open");
        if (!on(AdminPages.MUTATIONS))
            state = Component.translatable("gui.neroeconomy.hub.admin.state.no_mutations", state);
        s.text(g, state, 8, 47, 240, colour);
        switch (view()) {
            case SUPPLY -> drawSupply(g);
            case SIM -> drawSim(g);
            case COMMODITIES -> drawCommodities(g);
            case STOCKS -> drawStocks(g);
            case HOUSES -> drawHouses(g);
            case CHECK -> drawCheck(g);
            case HEALTH -> drawHealth(g);
            case PLAYER -> drawPlayer(g);
        }
    }

    private void drawSupply(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.admin.supply.balances", header(AdminPages.CIRCULATION),
                header(AdminPages.RESERVE), header(AdminPages.ESCROW), header(AdminPages.EXCHANGE),
                header(AdminPages.HOUSES)), 8, 58, 240, TexturedContainerScreen.TITLE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.admin.supply.minted", header(AdminPages.MINTED_TODAY),
                header(AdminPages.BURNED_TODAY), header(AdminPages.MINTED_ALL), header(AdminPages.BURNED_ALL)), 8, 78, 240,
                TexturedContainerScreen.SUBTLE);
        boolean healthy = on(AdminPages.CHECK_SUPPLY) && on(AdminPages.CHECK_ESCROW) && on(AdminPages.CHECK_EXCHANGE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.admin.checks", yesNo(on(AdminPages.CHECK_SUPPLY)),
                yesNo(on(AdminPages.CHECK_ESCROW)), yesNo(on(AdminPages.CHECK_EXCHANGE))), 8, 88, 240,
                healthy ? EconomyHubScreen.GOOD : EconomyHubScreen.BAD);
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.admin.supply.budgets",
                header(AdminPages.USED + "npc"), header(AdminPages.LIMIT + "npc"),
                header(AdminPages.USED + "exchange"), header(AdminPages.LIMIT + "exchange"),
                header(AdminPages.USED + "sibling"), header(AdminPages.LIMIT + "sibling"),
                header(AdminPages.USED + "system_job"), header(AdminPages.LIMIT + "system_job")), 8, 98, 240,
                TexturedContainerScreen.SUBTLE);
        drawRows(g, "gui.neroeconomy.hub.admin.supply.empty", (row, top) -> s.text(g, Component.translatable(
                AdminPages.REASON_ROW, EconomyHubScreen.arg(row.arg(0)), row.num(0), row.num(1)), 10, top + 1, 236,
                TexturedContainerScreen.TITLE));
        drawPageText(g, 54, 211);
    }

    private void drawSim(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        long step = header(AdminPages.STEP_MILLIS);
        s.text(g, on(AdminPages.FAST) ? Component.translatable("command.neroeconomy.sim.step_fast")
                : Component.translatable("command.neroeconomy.sim.step_minutes", step / 60_000L), 8, 58, 240,
                TexturedContainerScreen.TITLE);
        long last = header(AdminPages.LAST_STEP);
        s.text(g, last < 0L ? Component.translatable("command.neroeconomy.sim.not_started")
                : Component.translatable("command.neroeconomy.sim.last_step", last), 8, 68, 240, TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.admin.sim.behind", header(AdminPages.BEHIND),
                Component.translatable(on(AdminPages.CATCHING_UP) ? "gui.neroeconomy.hub.admin.sim.catching_up"
                        : "gui.neroeconomy.hub.admin.sim.up_to_date")), 8, 78, 240, TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable(on(AdminPages.AUTONOMOUS) ? "gui.neroeconomy.hub.admin.sim.autonomous_on"
                : "gui.neroeconomy.hub.admin.sim.autonomous_off"), 8, 88, 240, TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable(on(AdminPages.MARKET_CLOSED) ? "gui.neroeconomy.hub.admin.sim.market_closed"
                : "gui.neroeconomy.hub.admin.sim.market_open"), 8, 98, 240,
                on(AdminPages.MARKET_CLOSED) ? EconomyHubScreen.WARNING : TexturedContainerScreen.SUBTLE);
        long count = number(this.steps);
        boolean closed = on(AdminPages.MARKET_CLOSED);
        Component line;
        int colour = EconomyHubScreen.WARNING;
        if (armed("steps|" + count)) line = Component.translatable("gui.neroeconomy.hub.admin.sim.confirm_steps", count);
        else if (armed("market|" + (closed ? 0 : 1)))
            line = Component.translatable(closed ? "gui.neroeconomy.hub.admin.sim.confirm_open"
                    : "gui.neroeconomy.hub.admin.sim.confirm_close");
        else {
            line = Component.translatable("gui.neroeconomy.hub.admin.sim.hint", HubRouter.MAX_ADMIN_STEPS);
            colour = TexturedContainerScreen.SUBTLE;
        }
        s.wrapped(g, line, 8, 126, 240, colour);
        drawRows(g, "gui.neroeconomy.hub.admin.sim.empty", (row, top) -> s.text(g, Component.translatable(
                AdminPages.ACTION_ROW, EconomyHubScreen.arg(row.arg(0)), row.num(0)), 10, top, 236,
                TexturedContainerScreen.SUBTLE));
    }

    private void drawCommodities(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        drawRows(g, "gui.neroeconomy.hub.admin.commodities.empty", (row, top) -> {
            s.icon(g, row.icon(), 10, top);
            String key = row.arg(0);
            Component name = key.startsWith("#") ? Component.literal(key) : s.itemName(key);
            s.text(g, name, 30, top, 120, TexturedContainerScreen.TITLE);
            MutableComponent flags = Component.empty();
            if (row.flag(AdminPages.FLAG_BUY_OFF)) flags.append(Component.translatable("gui.neroeconomy.hub.admin.commodities.buy_off"));
            if (row.flag(AdminPages.FLAG_SELL_OFF)) flags.append(Component.translatable("gui.neroeconomy.hub.admin.commodities.sell_off"));
            s.textRight(g, flags, 246, top, EconomyHubScreen.BAD);
            s.text(g, Component.translatable(AdminPages.COMMODITY_ROW, millis(row.num(0)),
                    row.flag(AdminPages.FLAG_NO_SALES) ? Component.translatable("gui.neroeconomy.hub.admin.none") : Component.literal(Long.toString(row.num(1))),
                    row.flag(AdminPages.FLAG_NO_BUYING) ? Component.translatable("gui.neroeconomy.hub.admin.none") : Component.literal(Long.toString(row.num(2))),
                    row.num(3), row.num(4)), 30, top + 8, 216, TexturedContainerScreen.SUBTLE);
        });
        drawPageText(g, 54, 211);
    }

    private void drawStocks(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        drawRows(g, "gui.neroeconomy.hub.admin.stocks.empty", (row, top) -> {
            s.text(g, EconomyHubScreen.arg(row.arg(0)), 10, top, 160, TexturedContainerScreen.TITLE);
            Component flag = row.flag(AdminPages.FLAG_DELISTED) ? Component.translatable("gui.neroeconomy.hub.admin.stocks.delisted")
                    : row.flag(AdminPages.FLAG_HALTED) ? Component.translatable("gui.neroeconomy.hub.admin.stocks.halted")
                    : Component.empty();
            s.textRight(g, flag, 246, top, EconomyHubScreen.WARNING);
            s.text(g, Component.translatable(AdminPages.STOCK_ROW, PriceText.credits(row.num(0)),
                    PriceText.percent((int) row.num(1)), row.num(2)), 10, top + 8, 236,
                    row.num(1) < 0L ? EconomyHubScreen.BAD : TexturedContainerScreen.SUBTLE);
        });
        drawPageText(g, 54, 211);
    }

    private void drawHouses(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        drawRows(g, "gui.neroeconomy.hub.admin.houses.empty", (row, top) -> {
            Component name = row.flag(AdminPages.FLAG_RETIRED)
                    ? Component.translatable("command.neroeconomy.houses.retired", row.arg(0))
                    : EconomyHubScreen.arg(row.arg(0));
            s.text(g, name, 10, top, 236, TexturedContainerScreen.TITLE);
            s.text(g, Component.translatable(AdminPages.HOUSE_ROW, row.num(0), row.num(1), row.num(2), row.num(3)), 10,
                    top + 8, 236, TexturedContainerScreen.SUBTLE);
        });
        drawPageText(g, 54, 211);
    }

    private void drawCheck(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        if (!on(AdminPages.RAN)) {
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.admin.check.not_run"), 8, 58, 240,
                    TexturedContainerScreen.SUBTLE);
            return;
        }
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.admin.check.files", header("stocks"),
                header("stock_problems"), header("houses"), header("house_problems"), header("commodities"),
                header("files"), header("gated"), header("absent"), header("disabled")), 8, 58, 240,
                TexturedContainerScreen.SUBTLE);
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.admin.check.problems", header("commodity_problems"),
                header("clashes"), header("guard_problems")), 8, 80, 240, TexturedContainerScreen.SUBTLE);
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.admin.check.guard", header("recipes_read"),
                header("recipes_skipped"), header("chains"), header("depth")), 8, 92, 240, TexturedContainerScreen.SUBTLE);
        Component verdict;
        int colour = EconomyHubScreen.BAD;
        if (!on(AdminPages.AVAILABLE)) verdict = Component.translatable("command.neroeconomy.reload_check.unavailable");
        else if (!on(AdminPages.RECIPES_READABLE)) verdict = Component.translatable("command.neroeconomy.reload_check.no_recipes");
        else if (header(AdminPages.FAILURES) == 0L) {
            verdict = Component.translatable("gui.neroeconomy.hub.admin.check.passed");
            colour = EconomyHubScreen.GOOD;
        } else verdict = Component.translatable("gui.neroeconomy.hub.admin.check.failed", header(AdminPages.FAILURES),
                header("buy_off"), header("sell_off"));
        s.text(g, verdict, 8, 113, 240, colour);
        drawRows(g, "gui.neroeconomy.hub.admin.check.empty", (row, top) -> {
            Object[] args = new Object[row.args().size() + row.nums().length];
            for (int index = 0; index < row.args().size(); index++) args[index] = Component.literal(row.arg(index));
            for (int index = 0; index < row.nums().length; index++) args[row.args().size() + index] = row.num(index);
            s.text(g, Component.translatable(row.title(), args), 10, top, 236, TexturedContainerScreen.SUBTLE);
        });
        drawPageText(g, 54, 211);
        long more = header(AdminPages.MORE);
        if (more > 0L && this.page.page() + 1 >= this.page.pageCount())
            s.text(g, Component.translatable("command.neroeconomy.reload_check.more", more), 8, 198, 240,
                    TexturedContainerScreen.SUBTLE);
    }

    private void drawHealth(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        boolean frozen = on(AdminPages.FROZEN);
        s.wrapped(g, Component.translatable(frozen ? "gui.neroeconomy.hub.admin.health.frozen"
                : "gui.neroeconomy.hub.admin.health.healthy"), 8, 58, 240,
                frozen ? EconomyHubScreen.BAD : EconomyHubScreen.GOOD);
        s.text(g, Component.translatable("command.neroeconomy.health.schema", header(AdminPages.SCHEMA),
                header(AdminPages.SCHEMA_WRITES)), 8, 78, 240, TexturedContainerScreen.SUBTLE);
        s.text(g, Component.translatable("gui.neroeconomy.hub.admin.checks", yesNo(on(AdminPages.CHECK_SUPPLY)),
                yesNo(on(AdminPages.CHECK_ESCROW)), yesNo(on(AdminPages.CHECK_EXCHANGE))), 8, 88, 240,
                TexturedContainerScreen.SUBTLE);
        MutableComponent sections = Component.empty();
        boolean first = true;
        for (HubPage.Row row : this.page.rows())
            for (String section : row.args()) {
                if (!first) sections.append(", ");
                sections.append(EconomyHubScreen.arg(section));
                first = false;
            }
        s.wrapped(g, first ? Component.translatable("command.neroeconomy.health.no_sections")
                : Component.translatable("command.neroeconomy.health.sections", sections), 8, 98, 240,
                TexturedContainerScreen.SUBTLE);
        Component line;
        int colour = EconomyHubScreen.WARNING;
        if (armed("accept2")) line = Component.translatable("gui.neroeconomy.hub.admin.health.confirm_last");
        else if (armed("accept1")) line = Component.translatable("gui.neroeconomy.hub.admin.health.confirm_first");
        else {
            line = Component.translatable(frozen ? "gui.neroeconomy.hub.admin.health.accept_hint"
                    : "gui.neroeconomy.hub.admin.health.nothing_to_accept");
            colour = TexturedContainerScreen.SUBTLE;
        }
        s.wrapped(g, line, 8, 168, 240, colour);
    }

    private void drawPlayer(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.admin.player.intro"), 8, 58, 240,
                TexturedContainerScreen.SUBTLE);
        HubPage.Row row = target();
        if (row != null) {
            // The account as the operator typed it: the only place a page shows one.
            s.text(g, Component.translatable(AdminPages.TARGET_ROW, Component.literal(row.arg(0)), Component.translatable(
                    row.flag(AdminPages.FLAG_KNOWN) ? "gui.neroeconomy.hub.admin.player.known"
                            : "gui.neroeconomy.hub.admin.player.id_only")), 8, 92, 240, TexturedContainerScreen.TITLE);
        }
        long value = number(this.amount);
        long balance = setAmount();
        Component line = null;
        if (row != null) {
            Component who = Component.literal(row.arg(0));
            if (armed(what("export", 0L))) line = Component.translatable("gui.neroeconomy.hub.admin.player.confirm_export", who);
            else if (armed(what("erase", 0L))) line = Component.translatable("gui.neroeconomy.hub.admin.player.confirm_erase", who);
            else if (armed(what("grant", value))) line = Component.translatable("gui.neroeconomy.hub.admin.player.confirm_grant", who, value);
            else if (armed(what("take", value))) line = Component.translatable("gui.neroeconomy.hub.admin.player.confirm_take", who, value);
            else if (armed(what("set", balance))) line = Component.translatable("gui.neroeconomy.hub.admin.player.confirm_set", who, balance);
        }
        if (line != null) s.wrapped(g, line, 8, 144, 240, EconomyHubScreen.WARNING);
        else s.wrapped(g, Component.translatable(row == null ? "gui.neroeconomy.hub.admin.player.look_up_first"
                : "gui.neroeconomy.hub.admin.player.pick_action"), 8, 144, 240, TexturedContainerScreen.SUBTLE);
        String result = this.page.key(AdminPages.RESULT);
        if (!result.isEmpty())
            s.wrapped(g, Component.translatable(result, header(AdminPages.RESULT_AMOUNT)), 8, 194, 240,
                    TexturedContainerScreen.CREDIT);
    }

    @Override
    Component tooltipAt(int x, int y) {
        if (y >= 47 && y < 57) return Component.translatable("gui.neroeconomy.hub.admin.state.tooltip");
        HubPage.Row row = rowAt(x, y);
        return switch (view()) {
            case SUPPLY -> row != null ? Component.translatable("gui.neroeconomy.hub.admin.supply.reason.tooltip")
                    : y >= 58 && y < 118 ? Component.translatable("gui.neroeconomy.hub.admin.supply.tooltip") : null;
            case SIM -> row != null ? Component.translatable("gui.neroeconomy.hub.admin.sim.action.tooltip")
                    : y >= 58 && y < 108 ? Component.translatable("gui.neroeconomy.hub.admin.sim.tooltip") : null;
            case COMMODITIES -> row != null ? Component.translatable("gui.neroeconomy.hub.admin.commodities.row.tooltip") : null;
            case STOCKS -> row != null ? Component.translatable("gui.neroeconomy.hub.admin.stocks.row.tooltip") : null;
            case HOUSES -> row != null ? Component.translatable("gui.neroeconomy.hub.admin.houses.row.tooltip") : null;
            case CHECK -> row != null ? Component.translatable("gui.neroeconomy.hub.admin.check.row.tooltip")
                    : y >= 58 && y < 124 ? Component.translatable("gui.neroeconomy.hub.admin.check.tooltip") : null;
            case HEALTH -> y >= 58 && y < 148 ? Component.translatable("gui.neroeconomy.hub.admin.health.tooltip") : null;
            case PLAYER -> y >= 90 && y < 102 && target() != null
                    ? Component.translatable("gui.neroeconomy.hub.admin.player.target.tooltip") : null;
        };
    }
}
