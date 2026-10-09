package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The Admin tab's pages: aggregates and data-pack ids only. The one exception is the account an
 * operator typed into the player data tools, which appears as typed on that view's target row —
 * the row the confirm steps act on — and nowhere else, and whose UUID never leaves the server.
 */
class AdminPagesTest {
    private static final AdminPages.State STATE = new AdminPages.State(true, false, false);
    private static final UUID SUBJECT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String TYPED = "SellerAlex";

    static AdminPages.Supply supply() {
        return new AdminPages.Supply(1000L, 250_000L, 300L, 400L, 500L, 300L, 400L, 20L, 5L, 260_000L, 1000L, true, true,
                false, List.of(new AdminPages.Budget("npc", 10L, 20_000L), new AdminPages.Budget("exchange", 0L, 15_000L),
                        new AdminPages.Budget("sibling", 1L, 5000L), new AdminPages.Budget("system_job", 2L, 5000L),
                        new AdminPages.Budget("unbudgeted", 9L, 0L)),
                List.of(new AdminPages.Reason("command.neroeconomy.transaction_type.reserve_mint", 255_000L, 0L),
                        new AdminPages.Reason("command.neroeconomy.transaction_type.admin_take", 0L, 1000L)));
    }

    static AdminPages.Check check() {
        List<AdminPages.CheckLine> lines = new ArrayList<>();
        lines.add(new AdminPages.CheckLine(AdminPages.CHECK_FILE, List.of("neroeconomy:stocks/bad.json", "1.5"), null));
        lines.add(new AdminPages.CheckLine(AdminPages.CHECK_CHAIN, List.of("minecraft:iron_ingot",
                "minecraft:iron_block", "9.0", "12.0"), new long[] { 2L }));
        return new AdminPages.Check(true, true, true, Map.of("stocks", 4L, "commodities", 30L), lines, 1L, 0L);
    }

    /** Every Admin view, built from typical data; the player tools before any lookup. */
    static List<HubPages.Built> every() {
        List<HubPages.Built> built = new ArrayList<>();
        built.add(AdminPages.supply(supply(), STATE, 0));
        built.add(AdminPages.sim(new AdminPages.Sim(300_000L, 41L, 2L, false, true, true,
                List.of(new AdminPages.ActionCount("gui.neroeconomy.hub.admin.action.grant", 3L))), STATE));
        built.add(AdminPages.commodities(List.of(new AdminPages.Commodity("minecraft:wheat", "minecraft:wheat", 2500L, 3L,
                2L, 10L, 4L, false, false, true, true), new AdminPages.Commodity("#minecraft:logs", "minecraft:oak_log",
                        4000L, 5L, 0L, 0L, 0L, true, false, true, false)), STATE, 0));
        built.add(AdminPages.stocks(List.of(new AdminPages.Stock("stock.neroeconomy.acme", 12_345L, -150L, true, false,
                1L)), STATE, 0));
        built.add(AdminPages.houses(List.of(new AdminPages.House("house.neroeconomy.smiths", false, 9000L, 10_000L,
                100L, 50L), new AdminPages.House("neroeconomy:old_house", true, 0L, 0L, 0L, 0L)), STATE, 0));
        built.add(AdminPages.check(check(), STATE, 0));
        built.add(AdminPages.check(AdminPages.Check.NONE, STATE, 0));
        built.add(AdminPages.health(new AdminPages.Health(1, 1, true, true, true, List.of("ledger", "market", "book",
                "vault", "admin")), STATE));
        built.add(AdminPages.player(new AdminPages.Player("", null, null, false, "", 0L), STATE));
        built.add(AdminPages.player(new AdminPages.Player("", null, null, false,
                "gui.neroeconomy.hub.admin.player.not_found", 0L), STATE));
        return built;
    }

    @Test void everyViewIsBuiltAndSaysWhichViewItIs() {
        java.util.Set<Long> views = new java.util.HashSet<>();
        for (HubPages.Built built : every()) {
            assertEquals(HubTab.ADMIN, built.page().tab());
            views.add(built.page().number(AdminPages.VIEW, -1L));
            assertEquals(1L, built.page().number(AdminPages.MUTATIONS, -1L));
        }
        assertEquals(HubAction.AdminView.values().length, views.size());
    }

    @Test void theSupplyPageCarriesTotalsBudgetsAndReasonsOnly() {
        HubPage page = AdminPages.supply(supply(), STATE, 0).page();
        assertEquals(250_000L, page.number(AdminPages.RESERVE, -1L));
        assertEquals(0L, page.number(AdminPages.CHECK_EXCHANGE, -1L));
        assertEquals(10L, page.number(AdminPages.USED + "npc", -1L));
        assertEquals(5000L, page.number(AdminPages.LIMIT + "system_job", -1L));
        assertEquals(-1L, page.number(AdminPages.USED + "unbudgeted", -1L));
        assertEquals(2, page.rows().size());
        assertEquals(1000L, page.rows().get(1).num(1));
    }

    @Test void tablesArePagedAndReadOnly() {
        List<AdminPages.Stock> many = new ArrayList<>();
        for (int index = 0; index < 20; index++)
            many.add(new AdminPages.Stock("stock.neroeconomy.s" + index, 100L, 0L, false, false, 0L));
        HubPages.Built last = AdminPages.stocks(many, STATE, 99);
        assertEquals(2, last.page().page());
        assertEquals(20 - 2 * AdminPages.TABLE_ROWS, last.page().rows().size());
        for (HubPages.RowRef ref : last.refs().values()) assertEquals(HubPages.RowRef.Kind.ADMIN_LINE, ref.kind());
        for (HubPages.Built built : every())
            for (HubPages.RowRef ref : built.refs().values()) assertNull(ref.id(), "an admin table row stands for nothing");
    }

    @Test void theTypedAccountIsOnlyOnItsOwnConfirmRowAndItsIdNeverLeavesTheServer() {
        UUID rowId = UUID.randomUUID();
        HubPages.Built built = AdminPages.player(new AdminPages.Player(TYPED, rowId, SUBJECT, true,
                "gui.neroeconomy.hub.admin.player.found", 0L), STATE);
        HubPage page = built.page();
        assertEquals(1, page.rows().size());
        HubPage.Row row = page.rows().get(0);
        assertEquals(AdminPages.TARGET_ROW, row.title());
        assertEquals(List.of(TYPED), row.args());
        assertEquals(rowId, row.id());
        assertTrue(row.flag(AdminPages.FLAG_KNOWN) && row.flag(AdminPages.FLAG_ERASABLE));
        // The UUID is the server's only: on the row's reference, never on the page.
        assertEquals(SUBJECT, built.refs().get(rowId).id());
        assertEquals(HubPages.RowRef.Kind.ADMIN_TARGET, built.refs().get(rowId).kind());
        String all = page.toString() + page.keys() + page.numbers();
        assertFalse(all.contains(SUBJECT.toString()));
        for (String value : page.keys().values()) assertFalse(value.contains(TYPED));
        // An id that is not a known player can still be exported and erased, but not paid.
        HubPage idOnly = AdminPages.player(new AdminPages.Player(SUBJECT.toString(), rowId, SUBJECT, false, "", 0L),
                STATE).page();
        assertFalse(idOnly.rows().get(0).flag(AdminPages.FLAG_KNOWN));
        assertTrue(idOnly.rows().get(0).flag(AdminPages.FLAG_ERASABLE));
        // Nothing found, nothing shown.
        assertTrue(AdminPages.player(new AdminPages.Player(TYPED, rowId, null, false, "", 0L), STATE).page().rows()
                .isEmpty());
    }

    @Test void theAdminViewsHaveNoFieldThatCouldHoldAPlayerButTheTypedTarget() {
        Pattern suspicious = Pattern.compile("owner|player|seller|buyer|counterpart|account|uuid|who|balance",
                Pattern.CASE_INSENSITIVE);
        for (Class<?> type : List.of(AdminPages.State.class, AdminPages.Supply.class, AdminPages.Budget.class,
                AdminPages.Reason.class, AdminPages.Sim.class, AdminPages.ActionCount.class, AdminPages.Commodity.class,
                AdminPages.Stock.class, AdminPages.House.class, AdminPages.Check.class, AdminPages.CheckLine.class,
                AdminPages.Health.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertFalse(suspicious.matcher(component.getName()).find(), type.getSimpleName() + "." + component.getName());
                assertTrue(component.getType() != UUID.class, type.getSimpleName() + "." + component.getName());
            }
        }
    }

    @Test void healthListsEverySectionOnOnePage() {
        List<String> sections = AdminPages.SECTIONS;
        HubPage page = AdminPages.health(new AdminPages.Health(1, 1, true, true, true, sections), STATE).page();
        List<String> shown = new ArrayList<>();
        for (HubPage.Row row : page.rows()) shown.addAll(row.args());
        assertEquals(sections.stream().map(name -> AdminPages.SECTION_KEY + name).toList(), shown);
        assertEquals(1, page.pageCount());
    }
}
