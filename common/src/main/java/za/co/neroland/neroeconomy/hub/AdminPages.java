package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Admin tab's pages, for level-3 operators only. Every page is aggregates or data-pack ids:
 * credit totals, invariant results, budgets, commodity and company prices, House totals, the
 * reload check's counts and failures, the store's state and how often each operator action ran.
 * No account, balance or name of anyone is on any of them — with one exception: the account an
 * operator typed into the player data tools appears, as typed, on that view's one target row, the
 * row its confirm steps act on. Its id there is made up for the lookup; the account's UUID stays on
 * the server ({@link HubPages.RowRef}). Pure Java.
 */
public final class AdminPages {
    /** Rows a table page shows. */
    public static final int TABLE_ROWS = 9;

    // Header numbers every admin page carries.
    public static final String VIEW = "view";
    public static final String MUTATIONS = "mutations";
    public static final String MARKET_CLOSED = "market_closed";
    public static final String FROZEN = "frozen";
    public static final String COUNT = "count";

    // Supply.
    public static final String CIRCULATION = "circulation";
    public static final String RESERVE = "reserve";
    public static final String ESCROW = "escrow";
    public static final String EXCHANGE = "exchange";
    public static final String HOUSES = "houses";
    public static final String OBLIGATIONS = "obligations";
    public static final String EXCHANGE_HELD = "exchange_held";
    public static final String MINTED_TODAY = "minted_today";
    public static final String BURNED_TODAY = "burned_today";
    public static final String MINTED_ALL = "minted_all";
    public static final String BURNED_ALL = "burned_all";
    public static final String CHECK_SUPPLY = "check_supply";
    public static final String CHECK_ESCROW = "check_escrow";
    public static final String CHECK_EXCHANGE = "check_exchange";
    /** Prefixes of a budget's numbers: {@code used_npc}, {@code limit_npc}, ... */
    public static final String USED = "used_";
    public static final String LIMIT = "limit_";
    /** The budgets the supply page shows, by the lower-case name of their kind. */
    public static final List<String> BUDGETS = List.of("npc", "exchange", "sibling", "system_job");

    // Simulation.
    public static final String STEP_MILLIS = "step_millis";
    public static final String LAST_STEP = "last_step";
    public static final String BEHIND = "behind";
    public static final String FAST = "fast";
    public static final String AUTONOMOUS = "autonomous";
    public static final String CATCHING_UP = "catching_up";

    // Health.
    public static final String SCHEMA = "schema";
    public static final String SCHEMA_WRITES = "schema_writes";

    // The reload check.
    public static final String RAN = "ran";
    public static final String AVAILABLE = "available";
    public static final String RECIPES_READABLE = "recipes_ok";
    public static final String FAILURES = "failures";
    public static final String MORE = "more";
    /** The check's counts, in the order the page lists them; each has a lang key {@code ...admin.check.<name>}. */
    public static final List<String> CHECK_COUNTS = List.of("stocks", "stock_problems", "houses", "house_problems",
            "commodities", "files", "gated", "absent", "disabled", "commodity_problems", "clashes", "guard_problems",
            "recipes_read", "recipes_skipped", "chains", "depth", "buy_off", "sell_off");

    // Player data tools.
    public static final String RESULT = "result";
    public static final String RESULT_AMOUNT = "result_amount";
    public static final String KNOWN = "known";

    // Row titles.
    public static final String REASON_ROW = "gui.neroeconomy.hub.admin.supply.reason";
    public static final String ACTION_ROW = "gui.neroeconomy.hub.admin.sim.action";
    public static final String COMMODITY_ROW = "gui.neroeconomy.hub.admin.commodities.row";
    public static final String STOCK_ROW = "gui.neroeconomy.hub.admin.stocks.row";
    public static final String HOUSE_ROW = "gui.neroeconomy.hub.admin.houses.row";
    public static final String SECTION_ROW = "gui.neroeconomy.hub.admin.health.section";
    /** A store section's name on the health view is this prefix and the section's stored name. */
    public static final String SECTION_KEY = "gui.neroeconomy.hub.admin.section.";
    /** Every section the store has, for the lang check. */
    public static final List<String> SECTIONS = List.of("ledger", "market", "obligations", "shop_owners", "goods",
            "vault", "simulation", "book", "offers", "rules", "trade_escrow", "auto_traders", "exchange", "houses",
            "admin");
    public static final String TARGET_ROW = "gui.neroeconomy.hub.admin.player.target";
    public static final String CHECK_ENTRY = "gui.neroeconomy.hub.admin.check.entry_problem";
    public static final String CHECK_CLASH = "gui.neroeconomy.hub.admin.check.clash";
    public static final String CHECK_FILE = "command.neroeconomy.reload_check.file_problem";
    public static final String CHECK_SPREAD = "command.neroeconomy.reload_check.failure.spread";
    public static final String CHECK_CHAIN = "command.neroeconomy.reload_check.failure.chain";
    public static final String CHECK_REDEEM = "command.neroeconomy.reload_check.failure.redeem";
    public static final List<String> KEYS = List.of(REASON_ROW, ACTION_ROW, COMMODITY_ROW, STOCK_ROW, HOUSE_ROW,
            SECTION_ROW, TARGET_ROW, CHECK_ENTRY, CHECK_CLASH, CHECK_FILE, CHECK_SPREAD, CHECK_CHAIN, CHECK_REDEEM);

    /** Row flags. */
    public static final int FLAG_BUY_OFF = HubPage.FLAG_A;
    public static final int FLAG_SELL_OFF = HubPage.FLAG_B;
    public static final int FLAG_NO_SALES = HubPage.FLAG_C;
    public static final int FLAG_NO_BUYING = HubPage.FLAG_D;
    public static final int FLAG_HALTED = HubPage.FLAG_A;
    public static final int FLAG_DELISTED = HubPage.FLAG_B;
    public static final int FLAG_RETIRED = HubPage.FLAG_A;
    /** On the target row: a known account, so credits can be granted, taken or set. */
    public static final int FLAG_KNOWN = HubPage.FLAG_A;
    /** On the target row: an id that export and erasure can act on. */
    public static final int FLAG_ERASABLE = HubPage.FLAG_B;

    private AdminPages() { }

    /** What every admin page says about the switches: mutations allowed, market closed, economy frozen. */
    public record State(boolean mutations, boolean marketClosed, boolean frozen) { }

    // --- views -------------------------------------------------------------------------------

    /** Minted and burned all time under one reason. */
    public record Reason(String key, long minted, long burned) { }

    /** One Reserve budget today. @param kind lower-case kind, one of {@link #BUDGETS} */
    public record Budget(String kind, long used, long limit) { }

    public record Supply(long circulation, long reserve, long escrow, long exchange, long houses, long obligations,
            long exchangeHeld, long mintedToday, long burnedToday, long mintedAll, long burnedAll, boolean supplyOk,
            boolean escrowOk, boolean exchangeOk, List<Budget> budgets, List<Reason> reasons) {
        public Supply {
            budgets = budgets == null ? List.of() : List.copyOf(budgets);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /** One count of an operator action. */
    public record ActionCount(String key, long count) { }

    public record Sim(long stepMillis, long lastStep, long behind, boolean fast, boolean autonomous, boolean catchingUp,
            List<ActionCount> counts) {
        public Sim {
            counts = counts == null ? List.of() : List.copyOf(counts);
        }
    }

    /**
     * One commodity.
     *
     * @param key       its commodity key: an item id, or a tag written with its {@code #}
     * @param icon      an item that shows it
     * @param reference its reference price now, in milli-credits
     * @param ask       what its House sells one for now; 0 when it sells none
     * @param bid       what its House pays for one now; 0 when it buys none
     */
    public record Commodity(String key, String icon, long reference, long ask, long bid, long soldToday,
            long boughtToday, boolean buyOff, boolean sellOff, boolean sells, boolean buys) { }

    /** One listed company. @param price in hundredths of a credit; @param change in basis points */
    public record Stock(String nameKey, long price, long change, boolean halted, boolean delisted, long splits) { }

    /** One Merchant House. @param name its name key, or its id when it is no longer in the data */
    public record House(String name, boolean retired, long capital, long cap, long paidToday, long receivedToday) { }

    /** One line of the reload check: a lang key, its data-pack words, then its numbers. */
    public record CheckLine(String key, List<String> args, long[] nums) {
        public CheckLine {
            args = args == null ? List.of() : List.copyOf(args);
            nums = nums == null ? new long[0] : nums.clone();
        }
        @Override public long[] nums() { return nums.clone(); }
    }

    /** The reload check's result. @param counts by the names of {@link #CHECK_COUNTS} */
    public record Check(boolean ran, boolean available, boolean recipesReadable, Map<String, Long> counts,
            List<CheckLine> lines, long failures, long more) {
        public static final Check NONE = new Check(false, true, true, Map.of(), List.of(), 0L, 0L);

        public Check {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record Health(int schema, int schemaWrites, boolean supplyOk, boolean escrowOk, boolean exchangeOk,
            List<String> sections) {
        public Health {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /**
     * The player data tools.
     *
     * @param typed     what the operator typed, as typed; empty before a lookup
     * @param rowId     the id made up for this lookup's target row
     * @param target    the account it resolved to; null when it resolved to nothing. Never sent.
     * @param known     whether it is an account this world knows (credits can be changed)
     * @param result    lang key of the last result line; empty for none
     * @param amount    the number the result line shows
     */
    public record Player(String typed, UUID rowId, UUID target, boolean known, String result, long amount) {
        public Player {
            typed = typed == null ? "" : typed;
            result = result == null ? "" : result;
        }
    }

    // --- builders ----------------------------------------------------------------------------

    private static Map<String, Long> header(HubAction.AdminView view, State state) {
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(VIEW, (long) view.ordinal());
        numbers.put(MUTATIONS, state.mutations() ? 1L : 0L);
        numbers.put(MARKET_CLOSED, state.marketClosed() ? 1L : 0L);
        numbers.put(FROZEN, state.frozen() ? 1L : 0L);
        return numbers;
    }

    private static long bit(boolean value) { return value ? 1L : 0L; }

    private static HubPages.RowRef line() { return new HubPages.RowRef(HubPages.RowRef.Kind.ADMIN_LINE, null, "", 0); }

    /** Pages {@code all} and turns each row it keeps into a read-only table line. */
    private static HubPages.Built table(HubAction.AdminView view, State state, List<HubPage.Row> all, long requestedPage,
            Map<String, Long> extra) {
        int pages = HubPage.pages(all.size(), TABLE_ROWS);
        int page = HubPage.clampPage(requestedPage, pages);
        int from = page * TABLE_ROWS;
        List<HubPage.Row> rows = all.subList(Math.min(all.size(), from), Math.min(all.size(), from + TABLE_ROWS));
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        for (HubPage.Row row : rows) refs.put(row.id(), line());
        Map<String, Long> numbers = header(view, state);
        numbers.put(COUNT, (long) all.size());
        if (extra != null) numbers.putAll(extra);
        return new HubPages.Built(new HubPage(0, 0, HubTab.ADMIN, page, pages, rows, numbers, Map.of()), refs);
    }

    private static UUID id(String kind, String key, int index) { return HubPages.lineId("admin-" + kind, key, 0, index); }

    public static HubPages.Built supply(Supply view, State state, long page) {
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(CIRCULATION, view.circulation());
        numbers.put(RESERVE, view.reserve());
        numbers.put(ESCROW, view.escrow());
        numbers.put(EXCHANGE, view.exchange());
        numbers.put(HOUSES, view.houses());
        numbers.put(OBLIGATIONS, view.obligations());
        numbers.put(EXCHANGE_HELD, view.exchangeHeld());
        numbers.put(MINTED_TODAY, view.mintedToday());
        numbers.put(BURNED_TODAY, view.burnedToday());
        numbers.put(MINTED_ALL, view.mintedAll());
        numbers.put(BURNED_ALL, view.burnedAll());
        numbers.put(CHECK_SUPPLY, bit(view.supplyOk()));
        numbers.put(CHECK_ESCROW, bit(view.escrowOk()));
        numbers.put(CHECK_EXCHANGE, bit(view.exchangeOk()));
        for (Budget budget : view.budgets()) {
            if (!BUDGETS.contains(budget.kind())) continue;
            numbers.put(USED + budget.kind(), budget.used());
            numbers.put(LIMIT + budget.kind(), budget.limit());
        }
        List<HubPage.Row> rows = new ArrayList<>();
        int index = 0;
        for (Reason reason : view.reasons())
            rows.add(new HubPage.Row(id("reason", reason.key(), index++), "", REASON_ROW, List.of(reason.key()),
                    new long[] { reason.minted(), reason.burned() }, 0));
        return table(HubAction.AdminView.SUPPLY, state, rows, page, numbers);
    }

    public static HubPages.Built sim(Sim view, State state) {
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(STEP_MILLIS, view.stepMillis());
        numbers.put(LAST_STEP, view.lastStep());
        numbers.put(BEHIND, view.behind());
        numbers.put(FAST, bit(view.fast()));
        numbers.put(AUTONOMOUS, bit(view.autonomous()));
        numbers.put(CATCHING_UP, bit(view.catchingUp()));
        List<HubPage.Row> rows = new ArrayList<>();
        int index = 0;
        for (ActionCount count : view.counts())
            rows.add(new HubPage.Row(id("action", count.key(), index++), "", ACTION_ROW, List.of(count.key()),
                    new long[] { count.count() }, 0));
        return table(HubAction.AdminView.SIM, state, rows, 0L, numbers);
    }

    public static HubPages.Built commodities(List<Commodity> all, State state, long page) {
        List<HubPage.Row> rows = new ArrayList<>();
        int index = 0;
        for (Commodity commodity : all) {
            int flags = (commodity.buyOff() ? FLAG_BUY_OFF : 0) | (commodity.sellOff() ? FLAG_SELL_OFF : 0)
                    | (commodity.sells() ? 0 : FLAG_NO_SALES) | (commodity.buys() ? 0 : FLAG_NO_BUYING);
            rows.add(new HubPage.Row(id("commodity", commodity.key(), index++), commodity.icon(), COMMODITY_ROW,
                    List.of(commodity.key()), new long[] { commodity.reference(), commodity.ask(), commodity.bid(),
                            commodity.soldToday(), commodity.boughtToday() }, flags));
        }
        return table(HubAction.AdminView.COMMODITIES, state, rows, page, null);
    }

    public static HubPages.Built stocks(List<Stock> all, State state, long page) {
        List<HubPage.Row> rows = new ArrayList<>();
        int index = 0;
        for (Stock stock : all)
            rows.add(new HubPage.Row(id("stock", stock.nameKey(), index++), "", STOCK_ROW, List.of(stock.nameKey()),
                    new long[] { stock.price(), stock.change(), stock.splits() },
                    (stock.halted() ? FLAG_HALTED : 0) | (stock.delisted() ? FLAG_DELISTED : 0)));
        return table(HubAction.AdminView.STOCKS, state, rows, page, null);
    }

    public static HubPages.Built houses(List<House> all, State state, long page) {
        List<HubPage.Row> rows = new ArrayList<>();
        int index = 0;
        for (House house : all)
            rows.add(new HubPage.Row(id("house", house.name(), index++), "", HOUSE_ROW, List.of(house.name()),
                    new long[] { house.capital(), house.cap(), house.paidToday(), house.receivedToday() },
                    house.retired() ? FLAG_RETIRED : 0));
        return table(HubAction.AdminView.HOUSES, state, rows, page, null);
    }

    public static HubPages.Built check(Check check, State state, long page) {
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(RAN, bit(check.ran()));
        numbers.put(AVAILABLE, bit(check.available()));
        numbers.put(RECIPES_READABLE, bit(check.recipesReadable()));
        numbers.put(FAILURES, check.failures());
        numbers.put(MORE, check.more());
        for (String name : CHECK_COUNTS) numbers.put(name, check.counts().getOrDefault(name, 0L));
        List<HubPage.Row> rows = new ArrayList<>();
        int index = 0;
        for (CheckLine line : check.lines()) {
            rows.add(new HubPage.Row(id("check", line.key(), index), "", line.key(), line.args(), line.nums(), 0));
            index++;
        }
        return table(HubAction.AdminView.CHECK, state, rows, page, numbers);
    }

    public static HubPages.Built health(Health health, State state) {
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(SCHEMA, (long) health.schema());
        numbers.put(SCHEMA_WRITES, (long) health.schemaWrites());
        numbers.put(CHECK_SUPPLY, bit(health.supplyOk()));
        numbers.put(CHECK_ESCROW, bit(health.escrowOk()));
        numbers.put(CHECK_EXCHANGE, bit(health.exchangeOk()));
        // The section names, a row's worth of arguments at a time: every one fits on the one page.
        List<HubPage.Row> rows = new ArrayList<>();
        List<String> sections = new ArrayList<>();
        for (String name : health.sections()) sections.add(SECTION_KEY + name);
        for (int from = 0; from < sections.size(); from += HubPage.MAX_ARGS) {
            List<String> some = sections.subList(from, Math.min(sections.size(), from + HubPage.MAX_ARGS));
            rows.add(new HubPage.Row(id("section", String.join(",", some), from), "", SECTION_ROW, some, null, 0));
        }
        return table(HubAction.AdminView.HEALTH, state, rows, 0L, numbers);
    }

    /**
     * The player data tools. The one row, when there is one, is the account the operator typed, as
     * typed: the row their confirm steps restate and act on. Nothing else about the account is sent.
     */
    public static HubPages.Built player(Player view, State state) {
        Map<String, Long> numbers = header(HubAction.AdminView.PLAYER, state);
        numbers.put(RESULT_AMOUNT, view.amount());
        numbers.put(KNOWN, bit(view.known()));
        Map<String, String> keys = new LinkedHashMap<>();
        if (!view.result().isEmpty()) keys.put(RESULT, view.result());
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        if (view.target() != null && view.rowId() != null && !view.typed().isEmpty()) {
            rows.add(new HubPage.Row(view.rowId(), "", TARGET_ROW, List.of(view.typed()), null,
                    (view.known() ? FLAG_KNOWN : 0) | FLAG_ERASABLE));
            refs.put(view.rowId(), new HubPages.RowRef(HubPages.RowRef.Kind.ADMIN_TARGET, view.target(), "",
                    view.known() ? 1 : 0));
        }
        return new HubPages.Built(new HubPage(0, 0, HubTab.ADMIN, 0, 1, rows, numbers, keys), refs);
    }
}
