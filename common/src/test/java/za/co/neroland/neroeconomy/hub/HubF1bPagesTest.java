package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The page builders of the Invest, Trade, Jobs, Auctions and Shop tabs and the Wallet's "My data". */
class HubF1bPagesTest {
    static final String STEADY = "invest.neroeconomy.risk.steady";

    static InvestPages.InvestView invest(boolean practice, HubAction.InvestView view, InvestPages.Quote quote) {
        List<InvestPages.ProductLine> products = new ArrayList<>();
        for (int index = 0; index < 12; index++)
            products.add(new InvestPages.ProductLine("neroeconomy:company_" + index, "stock.neroeconomy.company_" + index,
                    STEADY, index == 3 ? "message.neroeconomy.invest.halted" : "", false, 1_250L + index, 1_200L + index,
                    -150, 40));
        products.add(new InvestPages.ProductLine("neroeconomy:index", "stock.neroeconomy.index",
                "invest.neroeconomy.risk.index", "", true, 10_000L, 9_900L, 20, 0));
        List<InvestPages.BondTermLine> terms = List.of(
                new InvestPages.BondTermLine("1d", "bond.neroeconomy.1d", "invest.neroeconomy.risk.bond", 1, 50),
                new InvestPages.BondTermLine("3d", "bond.neroeconomy.3d", "invest.neroeconomy.risk.bond", 3, 200),
                new InvestPages.BondTermLine("7d", "bond.neroeconomy.7d", "invest.neroeconomy.risk.bond", 7, 600));
        List<InvestPages.HoldingLine> holdings = List.of(new InvestPages.HoldingLine("neroeconomy:company_1",
                "stock.neroeconomy.company_1", "", 10L, 125L, 130L, 5L, 4L, 60_000L, 1_201L));
        List<InvestPages.BondLine> bonds = List.of(new InvestPages.BondLine(new UUID(8L, 1L), "bond.neroeconomy.3d", 3,
                500L, 10L, 3_600_000L, false));
        long[] recent = new long[144];
        for (int index = 0; index < recent.length; index++) recent[index] = 1_000L + index;
        long[] daily = new long[30];
        for (int index = 0; index < daily.length; index++) daily[index] = 900L + index * 3L;
        return new InvestPages.InvestView(practice, view, true, products, terms, holdings, bonds,
                new InvestPages.Totals(125L, 130L, 5L, 500L, 625L, 20_000L, 3L, 62, true), 9_000L, true,
                new InvestPages.Picked("neroeconomy:company_1", "stock.neroeconomy.company_1", STEADY, "", false, false),
                new InvestPages.Chart(recent, daily, 27, false), quote, new InvestPages.Outlook(90L, 101L, 112L, 7));
    }

    static InvestPages.Quote buyQuote() {
        return new InvestPages.Quote(HubAction.INVEST_QUOTE, true, "", 100L, 7L, 1_250L, 87L, 2L, 89L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, false);
    }

    static List<HubPages.Built> every() {
        List<HubPages.Built> built = new ArrayList<>();
        for (boolean practice : new boolean[] { false, true })
            for (HubAction.InvestView view : HubAction.InvestView.values())
                for (int page = 0; page < 3; page++) built.add(InvestPages.build(invest(practice, view, buyQuote()), page));
        List<TradePages.OfferLine> offers = List.of(
                new TradePages.OfferLine(new UUID(7L, 1L), "minecraft:diamond", 3L, 2, 50L, "minecraft:emerald", 4L, 0L,
                        3_600_000L),
                new TradePages.OfferLine(new UUID(7L, 2L), "", 0L, 0, 10L, "", 0L, 5L, 60_000L));
        List<HubPages.VaultLine> vault = List.of(new HubPages.VaultLine("minecraft:wheat", 64L, 0),
                new HubPages.VaultLine("minecraft:diamond_sword", 1L, 77));
        for (HubAction.TradeView view : HubAction.TradeView.values())
            built.add(TradePages.build(new TradePages.TradeView(view, offers, offers, vault, false, 10, 16, 250L, 48), 0));
        List<JobsPages.JobLine> jobs = List.of(
                new JobsPages.JobLine(new UUID(9L, 1L), "command.neroeconomy.job_type.contract", "minecraft:wheat",
                        "minecraft:wheat", true, "", "command.neroeconomy.job_state.open", 64L, 100L, 60_000L, false, false,
                        "", 0L, 0L, 0L),
                new JobsPages.JobLine(new UUID(9L, 2L), "command.neroeconomy.job_type.delivery", "minecraft:oak_log",
                        "#minecraft:logs", false, "house.neroeconomy.smiths", "command.neroeconomy.job_state.claimed", 32L,
                        80L, 120_000L, false, true, "minecraft:overworld", 10L, 64L, -20L),
                new JobsPages.JobLine(new UUID(9L, 3L), "command.neroeconomy.job_type.bounty", "minecraft:player_head",
                        "gui.neroeconomy.target.player", false, "", "command.neroeconomy.job_state.open", 1L, 500L, 1L,
                        true, false, "", 0L, 0L, 0L));
        for (HubAction.JobsView view : HubAction.JobsView.values())
            built.add(JobsPages.build(new JobsPages.JobsView(view, jobs, 1, 5, 72, false, "minecraft:overworld", 1L, 70L,
                    -3L), 0));
        List<AuctionsPages.AuctionLine> auctions = List.of(
                new AuctionsPages.AuctionLine(new UUID(10L, 1L), "minecraft:diamond", 3L, "", 0L, 10L, 600_000L, true,
                        false, false),
                new AuctionsPages.AuctionLine(new UUID(10L, 2L), "minecraft:iron_ingot", 64L, "house.neroeconomy.smiths",
                        40L, 42L, 60_000L, false, true, true));
        for (HubAction.AuctionsView view : HubAction.AuctionsView.values())
            built.add(AuctionsPages.build(new AuctionsPages.AuctionsView(view, auctions, 500L, 5, 1, 5, true), 0));
        built.add(ShopPages.build(new ShopPages.ShopView(List.of(
                new ShopPages.ShopLine("minecraft:overworld|123456", "minecraft:bread", "minecraft:overworld", 1L, 64L, 2L,
                        12L, 6L, 30L, true, true),
                new ShopPages.ShopLine("minecraft:the_nether|99", "", "minecraft:the_nether", -5L, 30L, 9L, 0L, 0L, 0L,
                        false, false)), 90), 0));
        List<DataPages.DataLine> data = new ArrayList<>();
        for (String key : DataPages.KEYS) data.add(new DataPages.DataLine(key, 3L));
        built.add(DataPages.build(data));
        return built;
    }

    @Test void everyPracticePageCarriesThePracticeBanner() {
        for (HubAction.InvestView view : HubAction.InvestView.values())
            for (int page = 0; page < 3; page++) {
                HubPage practice = InvestPages.build(invest(true, view, null), page).page();
                assertEquals(1L, practice.number(InvestPages.PRACTICE, 0L));
                assertEquals(InvestPages.PRACTICE_BANNER, practice.key(InvestPages.BANNER));
                // ... and survives the cut to the wire's bounds.
                assertEquals(InvestPages.PRACTICE_BANNER, practice.capped().key(InvestPages.BANNER));
                HubPage real = InvestPages.build(invest(false, view, null), page).page();
                assertEquals("", real.key(InvestPages.BANNER));
                assertEquals(0L, real.number(InvestPages.PLAY_BALANCE, -1L));
            }
    }

    @Test void theProductsPageListsCompaniesTheIndexAndBondTermsWithAPriceLine() {
        HubPages.Built first = InvestPages.build(invest(false, HubAction.InvestView.PRODUCTS, buyQuote()), 0);
        HubPage page = first.page();
        assertEquals(2, page.pageCount());
        assertEquals(InvestPages.PRODUCT_ROWS, page.rows().size());
        HubPage.Row row = page.rows().get(1);
        assertEquals(1_251L, row.num(0));
        assertEquals(1_201L, row.num(1));
        assertEquals(50L, row.num(2));
        assertEquals(HubPages.RowRef.Kind.STOCK, first.refs().get(row.id()).kind());
        assertTrue(page.rows().get(3).flag(InvestPages.FLAG_STOPPED));
        HubPage second = InvestPages.build(invest(false, HubAction.InvestView.PRODUCTS, null), 1).page();
        HubPage.Row bond = second.rows().get(second.rows().size() - 1);
        assertTrue(bond.flag(InvestPages.FLAG_BOND));
        assertEquals(600L, bond.num(0));
        long[] series = page.series();
        assertEquals(InvestPages.CHART_RECENT + 30, series.length);
        assertEquals((long) InvestPages.CHART_RECENT, page.number(InvestPages.CHART_RECENT_COUNT, 0L));
        assertEquals(1_000L + 143L, series[InvestPages.CHART_RECENT - 1], "the newest recent price is kept");
        assertEquals(27L, page.number(InvestPages.CHART_SPLIT, -2L));
        assertTrue(series.length <= HubPage.MAX_SERIES);
        assertArrayEquals(series, page.capped().series());
    }

    @Test void quoteAndOutlookNumbersAreKeyedForTheView() {
        HubPage page = InvestPages.build(invest(false, HubAction.InvestView.PRODUCTS, buyQuote()), 0).page();
        assertEquals(HubAction.INVEST_QUOTE.ordinal(), page.number(InvestPages.Q_FOR, -1L));
        assertEquals(1L, page.number(InvestPages.Q_OK, 0L));
        assertEquals(100L, page.number(InvestPages.Q_ASKED, 0L));
        assertEquals(7L, page.number(InvestPages.Q_UNITS, 0L));
        assertEquals(89L, page.number(InvestPages.Q_NET, 0L));
        assertEquals(2L, page.number(InvestPages.Q_FEE, 0L));
        assertEquals(90L, page.number(InvestPages.O_BAD, 0L));
        assertEquals(101L, page.number(InvestPages.O_TYPICAL, 0L));
        assertEquals(112L, page.number(InvestPages.O_GOOD, 0L));
        assertEquals(7L, page.number(InvestPages.O_DAYS, 0L));
        assertEquals("", page.key(InvestPages.Q_REASON));
        InvestPages.Quote refused = new InvestPages.Quote(HubAction.INVEST_SELL_QUOTE, true,
                "message.neroeconomy.invest.gain_reduced", 0L, 10L, 1_300L, 130L, 1L, 120L, 100L, 30L, 20L, 10L, 0L, 10L,
                0L, true);
        HubPage sell = InvestPages.build(invest(false, HubAction.InvestView.PORTFOLIO, refused), 0).page();
        assertEquals(1L, sell.number(InvestPages.Q_REDUCED, 0L));
        assertEquals(20L, sell.number(InvestPages.Q_PAYABLE, 0L));
        assertEquals(10L, sell.number(InvestPages.Q_FORFEIT, 0L));
        assertEquals("message.neroeconomy.invest.gain_reduced", sell.key(InvestPages.Q_REASON));
        // Every header survives the cut to the wire's bounds.
        assertEquals(sell.numbers(), sell.capped().numbers());
        assertEquals(page.numbers(), page.capped().numbers());
        assertEquals(page.keys(), page.capped().keys());
    }

    @Test void thePortfolioListsHoldingsThenBondsWithTotals() {
        HubPages.Built built = InvestPages.build(invest(false, HubAction.InvestView.PORTFOLIO, null), 0);
        HubPage page = built.page();
        assertEquals(2, page.rows().size());
        assertEquals(HubPages.RowRef.Kind.HOLDING, built.refs().get(page.rows().get(0).id()).kind());
        assertEquals("neroeconomy:company_1", built.refs().get(page.rows().get(0).id()).item());
        assertEquals(HubPages.RowRef.Kind.BOND, built.refs().get(page.rows().get(1).id()).kind());
        assertEquals(5L, page.number(InvestPages.P_DIFFERENCE, 0L));
        assertEquals(62L, page.number(InvestPages.P_DIVIDEND_PERCENT, 0L));
        assertEquals(1L, page.number(InvestPages.P_DIVIDEND_SCALED, 0L));
        assertEquals(0, page.series().length);
    }

    @Test void thinningKeepsTheEndsAndSplitDaysAreFound() {
        long[] all = new long[100];
        for (int index = 0; index < all.length; index++) all[index] = index;
        long[] thin = InvestPages.thin(all, 10);
        assertEquals(10, thin.length);
        assertEquals(0L, thin[0]);
        assertEquals(99L, thin[9]);
        assertArrayEquals(new long[] { 1L, 2L }, InvestPages.thin(new long[] { 1L, 2L }, 10));
        assertEquals(0, InvestPages.thin(null, 5).length);
        assertEquals(29, InvestPages.dayIndex(30, 100L, 100L));
        assertEquals(0, InvestPages.dayIndex(30, 100L, 71L));
        assertEquals(-1, InvestPages.dayIndex(30, 100L, 70L));
        assertEquals(-1, InvestPages.dayIndex(30, 100L, 101L));
    }

    @Test void tradeJobsAuctionsShopAndDataPagesCarryTheirRowsAndRefs() {
        for (HubPages.Built built : every()) {
            for (HubPage.Row row : built.page().rows())
                assertTrue(built.refs().containsKey(row.id()), built.page().tab() + " row without a ref");
            assertTrue(built.page().rows().size() <= HubPage.MAX_ROWS);
            assertTrue(built.page().numbers().size() <= HubPage.MAX_HEADER_NUMBERS, built.page().tab() + " numbers");
            assertTrue(built.page().keys().size() <= HubPage.MAX_HEADER_KEYS, built.page().tab() + " keys");
        }
        HubPages.Built inbox = TradePages.build(new TradePages.TradeView(HubAction.TradeView.INBOX, List.of(
                new TradePages.OfferLine(new UUID(7L, 1L), "minecraft:diamond", 3L, 2, 50L, "minecraft:emerald", 4L, 0L,
                        1L)), List.of(), List.of(), true, 10, 16, 0L, 48), 0);
        HubPage.Row offer = inbox.page().rows().get(0);
        assertEquals(HubPages.RowRef.Kind.OFFER_IN, inbox.refs().get(offer.id()).kind());
        assertTrue(offer.flag(TradePages.FLAG_ASKS_ITEMS));
        assertEquals("minecraft:emerald", offer.arg(0));
        assertEquals(1L, inbox.page().number(TradePages.MUTED, 0L));
        HubPages.Built direct = TradePages.build(new TradePages.TradeView(HubAction.TradeView.DIRECT, List.of(),
                List.of(), List.of(), false, 10, 16, 0L, 48), 0);
        assertTrue(direct.page().rows().isEmpty(), "nearby players are never on a page");
        assertEquals(16L, direct.page().number(TradePages.RANGE, 0L));
        HubPages.Built post = JobsPages.build(new JobsPages.JobsView(HubAction.JobsView.POST, List.of(), 0, 5, 72, true,
                "minecraft:overworld", 1L, 2L, 3L), 0);
        assertTrue(post.page().rows().isEmpty());
        assertEquals(1L, post.page().number(JobsPages.PLAYER_BOUNTIES, 0L));
        HubPages.Built shops = ShopPages.build(new ShopPages.ShopView(Collections.nCopies(14,
                new ShopPages.ShopLine("minecraft:overworld|1", "", "minecraft:overworld", 0, 0, 0, 0, 0, 0, false, false)),
                0), 9);
        assertEquals(2, shops.page().page());
        HubPage data = DataPages.build(List.of(new DataPages.DataLine(DataPages.VAULT, 4L),
                new DataPages.DataLine("somebody.else", 9L))).page();
        assertEquals(DataPages.KEYS.size(), data.rows().size());
        assertEquals(1L, data.number(DataPages.MY_DATA, 0L));
        assertEquals(4L, data.rows().get(DataPages.KEYS.indexOf(DataPages.VAULT)).num(0));
        for (HubPage.Row row : data.rows()) assertFalse(row.title().equals("somebody.else"));
    }

    @Test void offerJobAndAuctionFormsRoundTripAndRejectNonsense() {
        OfferForm offer = OfferForm.parse("Alex;V;3;10;minecraft:emerald;2;5");
        assertEquals(OfferForm.Source.VAULT, offer.source());
        assertEquals(offer, OfferForm.parse(offer.encode()));
        assertEquals(OfferForm.Source.NONE, OfferForm.parse("Alex;N;0;10;;0;0").source());
        assertEquals(null, OfferForm.parse("Alex;N;0;0;;0;0"), "an offer has to give something");
        assertEquals(null, OfferForm.parse("Alex;H;0;5;;0;0"), "giving from the hand needs a count");
        assertEquals(null, OfferForm.parse("Alex;N;2;5;;0;0"), "no goods means no count");
        assertEquals(null, OfferForm.parse("Alex;H;1;0;minecraft:emerald;0;0"), "an item asked needs a count");
        assertEquals(null, OfferForm.parse("Alex;H;1;0;;3;0"), "a count asked needs an item");
        assertEquals(null, OfferForm.parse("Alex;H;1;0;#minecraft:logs;3;0"));
        assertEquals(null, OfferForm.parse("Al ex;H;1;0;;0;0"));
        assertEquals(null, OfferForm.parse(";H;1;0;;0;0"));
        assertEquals(null, OfferForm.parse("Alex;H;-1;0;;0;0"));
        assertEquals(null, OfferForm.parse("Alex;H;1;" + (OfferForm.MAX_CREDITS + 1) + ";;0;0"));
        assertEquals(null, OfferForm.parse("Alex;X;1;0;;0;0"));
        assertEquals(null, OfferForm.parse("Alex;H;1;0;;0"));
        assertEquals(null, OfferForm.parse(null));

        JobForm here = JobForm.parse("D;minecraft:wheat;64;here");
        assertTrue(here.here());
        assertEquals(here, JobForm.parse(here.encode()));
        JobForm there = JobForm.parse("D;#minecraft:logs;8;minecraft:the_nether|10|64|-5");
        assertEquals(new JobForm.Place("minecraft:the_nether", 10, 64, -5), JobForm.destination(there.destination()));
        assertEquals(JobForm.Kind.BOUNTY, JobForm.parse("B;minecraft:zombie;3;").kind());
        assertEquals(JobForm.Kind.PLAYER_BOUNTY, JobForm.parse("P;Steve;1;").kind());
        assertEquals(null, JobForm.parse("C;minecraft:wheat;64;here"), "only a delivery has a destination");
        assertEquals(null, JobForm.parse("D;minecraft:wheat;64;"), "a delivery needs one");
        assertEquals(null, JobForm.parse("D;minecraft:wheat;64;minecraft:overworld|1|2"));
        assertEquals(null, JobForm.parse("D;minecraft:wheat;64;minecraft:overworld|99999999|2|3"));
        assertEquals(null, JobForm.parse("C;wheat;64;"));
        assertEquals(null, JobForm.parse("C;minecraft:wheat;0;"));
        assertEquals(null, JobForm.parse("C;minecraft:wheat;" + (JobForm.MAX_QUANTITY + 1) + ";"));
        assertEquals(null, JobForm.parse("B;#minecraft:logs;1;"));
        assertEquals(null, JobForm.parse("B;player:" + new UUID(1L, 2L) + ";1;"), "a stored player target is never typed");
        assertEquals(null, JobForm.parse("P;minecraft:zombie;1;"));
        assertEquals(null, JobForm.parse("X;minecraft:wheat;1;"));
        assertEquals(null, JobForm.parse(null));

        assertEquals(new HubRouter.AuctionForm(60, true), HubRouter.AuctionForm.parse("60:1"));
        assertEquals(new HubRouter.AuctionForm(1, false), HubRouter.AuctionForm.parse("1:0"));
        assertEquals(null, HubRouter.AuctionForm.parse("0:0"));
        assertEquals(null, HubRouter.AuctionForm.parse((AuctionsPages.LONGEST_MINUTES + 1) + ":0"));
        assertEquals(null, HubRouter.AuctionForm.parse("60:2"));
        assertEquals(null, HubRouter.AuctionForm.parse("sixty:1"));
    }
}
