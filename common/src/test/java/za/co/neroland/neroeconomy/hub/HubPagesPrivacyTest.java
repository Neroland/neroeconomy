package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.hub.HubPages.OrderKind;
import za.co.neroland.neroeconomy.hub.HubPages.SellerKind;

/**
 * No page of any tab carries a player's name or id. The views a page is built from have no field
 * that could hold one, and every string on a built page is a lang key, an item id or a tag id.
 */
class HubPagesPrivacyTest {
    private static final UUID VIEWER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String VIEWER_NAME = "ViewerSteve";
    private static final String OTHER_NAME = "SellerAlex";
    /** A lang key, an item or tag id (tags may start with '#'), or nothing. */
    private static final Pattern ALLOWED = Pattern.compile(
            "^$|^#?[a-z0-9_.-]+:[a-z0-9_./-]+$|^[a-z0-9_]+(\\.[a-z0-9_]+)+$");

    private static List<HubPages.Built> everyTab() {
        List<HubPages.Built> built = new ArrayList<>();
        List<HubPages.HistoryLine> history = new ArrayList<>();
        for (int index = 0; index < 20; index++)
            history.add(new HubPages.HistoryLine("command.neroeconomy.transaction_type.transfer", 10L + index,
                    index % 2 == 0, 1_700_000_000_000L + index));
        HubPages.WalletView wallet = new HubPages.WalletView(500L, 100L, 30L, 40L, false, history);
        for (int page = 0; page < 4; page++) built.add(HubPages.wallet(wallet, page));

        List<HubPages.VaultLine> vault = List.of(new HubPages.VaultLine("minecraft:wheat", 64L, 0),
                new HubPages.VaultLine("minecraft:diamond_sword", 1L, 77));
        built.add(HubPages.vault(new HubPages.VaultView(2, 27, vault), 0));

        List<HubPages.MarketLine> lines = List.of(
                new HubPages.MarketLine(new UUID(5L, 1L), "minecraft:wheat", SellerKind.PLAYER, "", 4L, 64L, 0L, 0L, -1L,
                        -1L, false, false),
                new HubPages.MarketLine(new UUID(5L, 2L), "minecraft:bread", SellerKind.SHOP, "", 6L, 12L, 0L, 0L, -1L,
                        -1L, true, true),
                new HubPages.MarketLine(new UUID(5L, 3L), "minecraft:iron_ingot", SellerKind.HOUSE,
                        "house.neroeconomy.smiths", 9L, 500L, 8L, 10L, 30L, 40L, false, false));
        HubQuotes.Quote quote = HubQuotes.buy(4L, 4L, 64L, false, 3L, 5.0, true, true);
        built.add(HubPages.market(new HubPages.MarketView(HubAction.MarketMode.BROWSE, lines, 3L, vault, quote,
                HubAction.MARKET_QUOTE), 0));
        built.add(HubPages.market(new HubPages.MarketView(HubAction.MarketMode.VAULT, List.of(), 0L, vault, null, null), 0));

        List<HubPages.OrderLine> orders = List.of(
                new HubPages.OrderLine(OrderKind.SELL, new UUID(6L, 1L), "minecraft:wheat", "minecraft:wheat", false, false,
                        false, "", 10, 4, 60_000, 0, 0, 0),
                new HubPages.OrderLine(OrderKind.BUY, new UUID(6L, 2L), "minecraft:wheat", "minecraft:wheat", true, false,
                        false, "", 10, 4, 40, 60_000, 0, 0),
                new HubPages.OrderLine(OrderKind.SHOP, new UUID(6L, 3L), "minecraft:bread", "minecraft:bread", false, false,
                        false, "", 3, 6, 0, 0, 0, 0),
                new HubPages.OrderLine(OrderKind.RULE, new UUID(6L, 4L), "minecraft:oak_log", "minecraft:logs", true, true,
                        true, "message.neroeconomy.rule.status.paused", 5, 1, 16, 3, 64, 200));
        built.add(HubPages.orders(new HubPages.OrdersView(orders, 1, 8, 50L, 10_000L, false), 0));
        for (HubTab tab : HubTab.values()) if (!tab.built()) built.add(HubPages.comingSoon(tab));
        // Invest (real and practice), Trade, Jobs, Auctions, Shop and the Wallet's "My data".
        built.addAll(HubF1bPagesTest.every());
        // The Admin tab: every view, the player data tools before a lookup (the typed account is tested apart).
        built.addAll(AdminPagesTest.every());
        return built;
    }

    @Test void everyTabIsCoveredByThisTest() {
        java.util.Set<HubTab> covered = java.util.EnumSet.noneOf(HubTab.class);
        for (HubPages.Built built : everyTab()) covered.add(built.page().tab());
        for (HubTab tab : HubTab.values())
            if (tab.built()) assertTrue(covered.contains(tab), tab + " has no page in the privacy test");
    }

    @Test void theF1bViewsHaveNoFieldThatCouldHoldAPlayer() {
        // A field ending in "Key" holds a lang key; anything else that sounds like a person is refused.
        Pattern suspicious = Pattern.compile("name|owner|player|seller|buyer|bidder|issuer|claimant|sender|recipient"
                + "|counterpart|account|uuid|who", Pattern.CASE_INSENSITIVE);
        for (Class<?> type : List.of(InvestPages.InvestView.class, InvestPages.ProductLine.class,
                InvestPages.BondTermLine.class, InvestPages.HoldingLine.class, InvestPages.BondLine.class,
                InvestPages.Totals.class, InvestPages.Picked.class, InvestPages.Chart.class, InvestPages.Quote.class,
                InvestPages.Outlook.class, TradePages.TradeView.class, TradePages.OfferLine.class,
                JobsPages.JobsView.class, JobsPages.JobLine.class, AuctionsPages.AuctionsView.class,
                AuctionsPages.AuctionLine.class, ShopPages.ShopView.class, ShopPages.ShopLine.class,
                DataPages.DataLine.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName();
                if (!name.endsWith("Key"))
                    assertFalse(suspicious.matcher(name).find(), type.getSimpleName() + "." + name);
                if (component.getType() == UUID.class)
                    assertEquals("id", name, type.getSimpleName() + " has a UUID that is not its own id");
            }
        }
    }

    @Test void aBountyOnAPlayerIsNeverShownByItsStoredTarget() {
        // The Jobs page only ever receives what JobTargets lets a viewer see.
        String stored = za.co.neroland.neroeconomy.obligation.JobTargets.player(OTHER);
        String shown = za.co.neroland.neroeconomy.obligation.JobTargets.visibleTo(stored, VIEWER, UUID.randomUUID());
        String key = za.co.neroland.neroeconomy.obligation.JobTargets.playerLabelKey(shown);
        assertClean(key, "bounty target");
        assertFalse(key.contains(OTHER.toString()));
    }

    private static void assertClean(String value, String where) {
        assertTrue(ALLOWED.matcher(value).matches(), where + " carries free text: " + value);
        String lower = value.toLowerCase(Locale.ROOT);
        for (String name : List.of(VIEWER_NAME, OTHER_NAME))
            assertFalse(lower.contains(name.toLowerCase(Locale.ROOT)), where + " names a player: " + value);
        for (UUID id : List.of(VIEWER, OTHER))
            assertFalse(lower.contains(id.toString()), where + " carries a player id: " + value);
    }

    @Test void noRowOrHeaderOfAnyTabCarriesANameOrAPlayerId() {
        for (HubPages.Built built : everyTab()) {
            HubPage page = built.page();
            String tab = page.tab().id();
            for (HubPage.Row row : page.rows()) {
                assertNotEquals(VIEWER, row.id(), tab);
                assertNotEquals(OTHER, row.id(), tab);
                assertClean(row.icon(), tab + " icon");
                assertClean(row.title(), tab + " title");
                for (String arg : row.args()) assertClean(arg, tab + " arg");
            }
            for (Map.Entry<String, String> entry : page.keys().entrySet()) {
                assertTrue(entry.getKey().matches("[a-z_]+"), tab + " header name " + entry.getKey());
                assertClean(entry.getValue(), tab + " header");
            }
            for (String name : page.numbers().keySet())
                assertTrue(name.matches("[a-z_]+"), tab + " header name " + name);
            for (HubPages.RowRef ref : built.refs().values()) {
                assertNotEquals(VIEWER, ref.id(), tab);
                assertNotEquals(OTHER, ref.id(), tab);
            }
        }
    }

    @Test void theViewsHaveNoFieldThatCouldHoldAPlayer() {
        Pattern suspicious = Pattern.compile("name|owner|player|seller|buyer|counterpart|account|uuid|who",
                Pattern.CASE_INSENSITIVE);
        for (Class<?> type : List.of(HubPages.WalletView.class, HubPages.HistoryLine.class, HubPages.VaultView.class,
                HubPages.VaultLine.class, HubPages.MarketView.class, HubPages.MarketLine.class, HubPages.OrdersView.class,
                HubPages.OrderLine.class, HubPages.RowRef.class, HubPage.class, HubPage.Row.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertFalse(suspicious.matcher(component.getName()).find(), type.getSimpleName() + "." + component.getName());
                if (component.getType() == UUID.class)
                    assertEquals("id", component.getName(), type.getSimpleName() + " has a UUID that is not its own id");
            }
        }
    }

    @Test void aPlayerSellerIsOnlyEverAPlayerAndAShopNeverItsOwner() {
        assertEquals(HubPages.SELLER_PLAYER, HubPages.badge(SellerKind.PLAYER, "house.neroeconomy.smiths"));
        assertEquals(HubPages.SELLER_SHOP, HubPages.badge(SellerKind.SHOP, ""));
        assertEquals("house.neroeconomy.smiths", HubPages.badge(SellerKind.HOUSE, "house.neroeconomy.smiths"));
        assertEquals(HubPages.SELLER_SHOP, HubPages.badge(SellerKind.HOUSE, ""));
    }

    @Test void pagesStayWithinTheirRowCountAndThePageAskedForIsClamped() {
        HubPages.WalletView wallet = new HubPages.WalletView(0L, 0L, 0L, 0L, true,
                java.util.Collections.nCopies(30, new HubPages.HistoryLine("command.neroeconomy.transaction_type.transfer",
                        1L, true, 0L)));
        HubPage last = HubPages.wallet(wallet, 999).page();
        assertEquals(HubPage.pages(30, HubPages.WALLET_ROWS) - 1, last.page());
        assertEquals(30 - last.page() * HubPages.WALLET_ROWS, last.rows().size());
        for (HubPages.Built built : everyTab()) assertTrue(built.page().rows().size() <= HubPage.MAX_ROWS);
        assertEquals(1L, HubPages.wallet(wallet, 0).page().number(HubPages.OPT_OUT, -1L));
    }
}
