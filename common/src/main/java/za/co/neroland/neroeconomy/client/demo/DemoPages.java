package za.co.neroland.neroeconomy.client.demo;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.hub.AuctionsPages;
import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubPages;
import za.co.neroland.neroeconomy.hub.HubQuotes;
import za.co.neroland.neroeconomy.hub.InvestPages;
import za.co.neroland.neroeconomy.hub.JobsPages;
import za.co.neroland.neroeconomy.hub.ShopPages;
import za.co.neroland.neroeconomy.hub.TradePages;

/**
 * The sample pages the walkthroughs show, built on the client with the server's own page builders
 * from fixed, plainly made-up values: round balances, sample names from lang, a fixed date in 2031.
 * Nothing here is read from the player, the world, a file or the server. Pure Java.
 */
public final class DemoPages {
    /** Lang keys of the made-up names a demo types or lists. */
    public static final String NAME_A = "gui.neroeconomy.hub.demo.name.a";
    public static final String NAME_B = "gui.neroeconomy.hub.demo.name.b";
    public static final String NAME_C = "gui.neroeconomy.hub.demo.name.c";
    public static final List<String> NAMES = List.of(NAME_A, NAME_B, NAME_C);
    /** Lang keys of text a demo types: a search, an item id. */
    public static final String SEARCH = "gui.neroeconomy.hub.demo.search";
    public static final String RULE_ITEM = "gui.neroeconomy.hub.demo.rule_item";
    /** Lang keys of the made-up companies and bond the Invest demo lists. */
    public static final String COMPANY_A = "gui.neroeconomy.hub.demo.company.a";
    public static final String COMPANY_B = "gui.neroeconomy.hub.demo.company.b";
    public static final String COMPANY_INDEX = "gui.neroeconomy.hub.demo.company.index";
    public static final String BOND = "gui.neroeconomy.hub.demo.bond";
    /** Every lang key the sample pages and actions use that is the demo's own. */
    public static final List<String> KEYS = List.of(NAME_A, NAME_B, NAME_C, SEARCH, RULE_ITEM, COMPANY_A, COMPANY_B,
            COMPANY_INDEX, BOND);
    /** 1 January 2031, 09:00 UTC: the made-up date every sample time is counted from. */
    static final long SAMPLE_TIME = 1_925_024_400_000L;
    private static final long MINUTE = 60_000L;
    private static final String TYPE = "command.neroeconomy.transaction_type.";

    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;

    private DemoPages() { }

    /** A fixed made-up row id. */
    private static UUID id(long n) { return new UUID(0x0DE0DE0DE0L, n); }

    static HubPage wallet() {
        return HubPages.wallet(new HubPages.WalletView(1_000L, 0L, 0L, 40L, false, List.of(
                new HubPages.HistoryLine(TYPE + "market_payout", 120L, true, SAMPLE_TIME + 50 * MINUTE),
                new HubPages.HistoryLine(TYPE + "market_purchase", 64L, false, SAMPLE_TIME + 40 * MINUTE),
                new HubPages.HistoryLine(TYPE + "fee_market", 2L, false, SAMPLE_TIME + 40 * MINUTE),
                new HubPages.HistoryLine(TYPE + "player_payment", 50L, true, SAMPLE_TIME + 25 * MINUTE),
                new HubPages.HistoryLine(TYPE + "starting_balance", 1_000L, true, SAMPLE_TIME))), 0L).page();
    }

    static HubPage vault() {
        return HubPages.vault(new HubPages.VaultView(4, 54, List.of(
                new HubPages.VaultLine("minecraft:oak_log", 128L, 0),
                new HubPages.VaultLine("minecraft:iron_pickaxe", 1L, 1),
                new HubPages.VaultLine("minecraft:wheat", 64L, 0),
                new HubPages.VaultLine("minecraft:cobblestone", 256L, 0))), 0L).page();
    }

    // --- market ------------------------------------------------------------------------------

    private static HubPages.MarketLine line(long id, String item, HubPages.SellerKind kind, long unit, long stock) {
        return new HubPages.MarketLine(id(id), item, kind, "", unit, stock, 0L, 0L, -1L, -1L, false, false);
    }

    private static HubPage market(List<HubPages.MarketLine> lines, HubQuotes.Quote quote) {
        return HubPages.market(new HubPages.MarketView(HubAction.MarketMode.BROWSE, lines, lines.size(), List.of(),
                quote, quote == null ? null : HubAction.MARKET_QUOTE), 0L).page();
    }

    private static List<HubPages.MarketLine> wheat() {
        return List.of(line(11, "minecraft:wheat", HubPages.SellerKind.PLAYER, 4L, 128L),
                line(12, "minecraft:wheat", HubPages.SellerKind.SHOP, 5L, 300L),
                line(13, "minecraft:wheat_seeds", HubPages.SellerKind.PLAYER, 1L, 64L));
    }

    /** 0: everything on offer; 1: the search for wheat; 2: the same with a quote for 16 of the first row. */
    static List<HubPage> market() {
        HubPage all = market(List.of(line(1, "minecraft:oak_log", HubPages.SellerKind.PLAYER, 6L, 64L),
                line(2, "minecraft:iron_ingot", HubPages.SellerKind.SHOP, 20L, 32L),
                line(3, "minecraft:bread", HubPages.SellerKind.PLAYER, 3L, 40L),
                line(4, "minecraft:cobblestone", HubPages.SellerKind.SHOP, 1L, 500L)), null);
        HubQuotes.Quote quote = HubQuotes.buy(4L, 4L, 128L, false, 16L, 2.0, true, true);
        return List.of(all, market(wheat(), null), market(wheat(), quote));
    }

    // --- my orders ---------------------------------------------------------------------------

    static HubPage orders() {
        return HubPages.orders(new HubPages.OrdersView(List.of(
                new HubPages.OrderLine(HubPages.OrderKind.SELL, id(21), "minecraft:oak_log", "minecraft:oak_log", false,
                        false, false, "", 64L, 6L, 2L * DAY, 0L, 0L, 0L),
                new HubPages.OrderLine(HubPages.OrderKind.BUY, id(22), "minecraft:wheat", "minecraft:wheat", true, false,
                        false, "", 128L, 3L, 384L, DAY, 0L, 0L),
                new HubPages.OrderLine(HubPages.OrderKind.SHOP, id(23), "minecraft:cobblestone", "minecraft:cobblestone",
                        false, false, false, "", 200L, 1L, 0L, 0L, 0L, 0L),
                new HubPages.OrderLine(HubPages.OrderKind.RULE, id(24), "minecraft:bread", "minecraft:bread", true, false,
                        false, "message.neroeconomy.rule.status.running", 3L, 8L, 16L, 40L, 160L, 360L)),
                1, 5, 120L, 5_000L, false), 0L).page();
    }

    // --- invest ------------------------------------------------------------------------------

    private static final String RISKY = "invest.neroeconomy.risk.risky";
    private static final String STEADY = "invest.neroeconomy.risk.steady";
    private static final String INDEX = "invest.neroeconomy.risk.index";
    private static final String BOND_RISK = "invest.neroeconomy.risk.bond";

    private static List<InvestPages.ProductLine> products() {
        return List.of(new InvestPages.ProductLine("demo_a", COMPANY_A, RISKY, "", false, 1_100L, 1_090L, 120, 0),
                new InvestPages.ProductLine("demo_b", COMPANY_B, STEADY, "", false, 2_500L, 2_490L, -40, 150),
                new InvestPages.ProductLine("demo_index", COMPANY_INDEX, INDEX, "", true, 1_000L, 995L, 20, 100));
    }

    private static List<InvestPages.BondTermLine> bonds() {
        return List.of(new InvestPages.BondTermLine("demo_30", BOND, BOND_RISK, 30, 300));
    }

    /** A made-up price line: a gentle wave around {@code base}, in hundredths of a credit. */
    private static long[] wave(int points, long base, long swing) {
        long[] out = new long[points];
        for (int index = 0; index < points; index++) out[index] = base + swing * ((index * 7L) % 11L - 5L) / 5L + index;
        return out;
    }

    private static HubPage invest(boolean practice, boolean picked, InvestPages.Quote quote) {
        InvestPages.Picked pick = picked ? new InvestPages.Picked("demo_a", COMPANY_A, RISKY, "", false, false) : null;
        InvestPages.Chart chart = picked ? new InvestPages.Chart(wave(48, 1_080L, 20L), wave(30, 1_000L, 60L), -1, false)
                : null;
        InvestPages.Outlook outlook = quote == null ? null : new InvestPages.Outlook(92L, 101L, 112L, 7);
        return InvestPages.build(new InvestPages.InvestView(practice, HubAction.InvestView.PRODUCTS, true, products(),
                bonds(), List.of(), List.of(), null, practice ? 10_000L : 0L, practice, pick, chart, quote, outlook),
                0L).page();
    }

    /** 0: the list; 1: company A picked; 2: a quote for 100 credits of it; 3: practice mode. */
    static List<HubPage> invest() {
        InvestPages.Quote quote = new InvestPages.Quote(HubAction.INVEST_QUOTE, true, "", 100L, 9L, 1_100L, 99L, 1L,
                100L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false);
        return List.of(invest(false, false, null), invest(false, true, null), invest(false, true, quote),
                invest(true, false, null));
    }

    // --- trade -------------------------------------------------------------------------------

    private static HubPage trade(HubAction.TradeView view) {
        List<TradePages.OfferLine> incoming = List.of(
                new TradePages.OfferLine(id(31), "minecraft:oak_log", 32L, 1, 0L, "", 0L, 40L, 20L * HOUR),
                new TradePages.OfferLine(id(32), "", 0L, 0, 25L, "minecraft:wheat", 16L, 0L, 6L * HOUR));
        List<TradePages.OfferLine> outgoing = List.of(
                new TradePages.OfferLine(id(33), "minecraft:cobblestone", 64L, 1, 0L, "", 0L, 10L, 12L * HOUR));
        List<HubPages.VaultLine> vault = List.of(new HubPages.VaultLine("minecraft:oak_log", 128L, 0),
                new HubPages.VaultLine("minecraft:wheat", 64L, 0), new HubPages.VaultLine("minecraft:cobblestone", 256L, 0));
        return TradePages.build(new TradePages.TradeView(view, incoming, outgoing, vault, false, 10, 16, 200L, 24),
                0L).page();
    }

    /** 0: offers waiting for an answer; 1: the "Send offer" form; 2: trade nearby. */
    static List<HubPage> trade() {
        return List.of(trade(HubAction.TradeView.INBOX), trade(HubAction.TradeView.SEND),
                trade(HubAction.TradeView.DIRECT));
    }

    // --- jobs --------------------------------------------------------------------------------

    private static final String JOB_TYPE = "command.neroeconomy.job_type.";
    private static final String JOB_STATE = "command.neroeconomy.job_state.";

    private static HubPages.Built jobsBuilt(HubAction.JobsView view, boolean claimed) {
        List<JobsPages.JobLine> jobs = List.of(
                new JobsPages.JobLine(id(41), JOB_TYPE + "contract", "minecraft:wheat", "minecraft:wheat", true, "",
                        JOB_STATE + (claimed ? "claimed" : "open"), 64L, 200L, 2L * DAY, false, claimed, "", 0L, 0L, 0L),
                new JobsPages.JobLine(id(42), JOB_TYPE + "contract", "minecraft:iron_ingot", "minecraft:iron_ingot", true,
                        "", JOB_STATE + "open", 32L, 400L, DAY, false, false, "", 0L, 0L, 0L),
                new JobsPages.JobLine(id(43), JOB_TYPE + "contract", "minecraft:bread", "minecraft:bread", true, "",
                        JOB_STATE + "open", 20L, 90L, 5L * HOUR, false, false, "", 0L, 0L, 0L));
        return JobsPages.build(new JobsPages.JobsView(view, view == HubAction.JobsView.POST ? List.of() : jobs, 1, 5,
                48, false, "minecraft:overworld", 100L, 64L, -200L), 0L);
    }

    /** 0: contracts on offer; 1: the first one claimed by the viewer; 2: the "Post" form. */
    static List<HubPage> jobs() {
        return List.of(jobsBuilt(HubAction.JobsView.CONTRACTS, false).page(),
                jobsBuilt(HubAction.JobsView.CONTRACTS, true).page(), jobsBuilt(HubAction.JobsView.POST, false).page());
    }

    // --- auctions ----------------------------------------------------------------------------

    private static HubPage auctions(HubAction.AuctionsView view) {
        List<AuctionsPages.AuctionLine> lots = List.of(
                new AuctionsPages.AuctionLine(id(51), "minecraft:diamond_pickaxe", 1L, "", 300L, 315L, 3L * HOUR, false,
                        false, false),
                new AuctionsPages.AuctionLine(id(52), "minecraft:emerald", 16L, "", 0L, 50L, DAY, false, false, false),
                new AuctionsPages.AuctionLine(id(53), "minecraft:saddle", 1L, "", 120L, 126L, 40L * MINUTE, false, true,
                        false));
        return AuctionsPages.build(new AuctionsPages.AuctionsView(view, view == HubAction.AuctionsView.CREATE
                ? List.of() : lots, 500L, 5, 0, 3, false), 0L).page();
    }

    /** 0: every auction; 1: the "Create" form. */
    static List<HubPage> auctions() {
        return List.of(auctions(HubAction.AuctionsView.ALL), auctions(HubAction.AuctionsView.CREATE));
    }

    // --- shop --------------------------------------------------------------------------------

    static HubPage shop() {
        return ShopPages.build(new ShopPages.ShopView(List.of(
                new ShopPages.ShopLine("demo|1", "minecraft:bread", "minecraft:overworld", 100L, 64L, -200L, 48L, 3L, 0L,
                        true, true),
                new ShopPages.ShopLine("demo|2", "minecraft:torch", "minecraft:overworld", 340L, 70L, 15L, 120L, 1L, 25L,
                        false, false),
                new ShopPages.ShopLine("demo|3", "", "minecraft:overworld", -50L, 63L, 80L, 0L, 0L, 2L, false, true)), 30),
                0L).page();
    }
}
