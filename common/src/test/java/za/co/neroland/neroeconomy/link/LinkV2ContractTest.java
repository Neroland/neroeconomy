package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * The schema-2 wire contract. Every section and every action result is built here from fixed
 * inputs and compared with its golden sample under {@code common/src/test/resources/link/v2},
 * each wrapped in the bridge's {@code {"ok":true,"data":…}} envelope: those files are the
 * companion app's fixtures. The keys the app will read are pinned with their JSON types, and
 * nothing in any of them may name or identify another player.
 *
 * <p>A golden file that does not exist yet is written from the builder and the test fails, so a
 * new sample is always looked at and committed on purpose; one that exists must match exactly.
 */
class LinkV2ContractTest {
    private static final String CREDITS = "nerolandcore:credits";
    private static final long AS_OF = 1_791_000_000_000L;
    private static final String ORDER = "3c9e1f20-7b4a-4d8e-9f10-2a3b4c5d6e7";
    private static final String RULE = "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4";
    private static final String OFFER = "8d7c6b5a-4f3e-4d2c-9b1a-0f9e8d7c6b5";
    private static final String BOND = "1f2e3d4c-5b6a-4978-8a6b-5c4d3e2f1a0";
    private static final String JOB = "2b3c4d5e-6f70-4a1b-9c2d-3e4f5a6b7c8";
    private static final String AUCTION = "4d5e6f70-8192-4a3b-8c4d-5e6f7a8b9c0";
    /** Another player, as the stores know them. No sample may contain this id or this name. */
    private static final String OTHER_PLAYER = "0e1f2a3b-4c5d-4e6f-8a7b-9c0d1e2f3a4b";
    private static final String OTHER_NAME = "Steve_The_Other";

    static JsonObject balance() {
        return LinkPayloads.balance(CREDITS, 1_380L, List.of(
                new LinkPayloads.Entry(TransactionType.MARKET_PURCHASE, 120L, false, CREDITS, 1_790_999_400_000L,
                        1_790_999_400_000L),
                new LinkPayloads.Entry(TransactionType.STARTING_BALANCE, 1_500L, true, CREDITS, 1_790_902_200_000L,
                        1_790_902_200_000L)), AS_OF);
    }

    static LinkViews.Remote remote() { return new LinkViews.Remote(true, 1_200L, 5_000L); }

    // --- sections ----------------------------------------------------------------------------

    static JsonObject summary() {
        return LinkViews.summary(new LinkViews.Summary(CREDITS, 1_380L, 64L, 12, 54, 2, 1, 3, 8, 1, 2, 2_150L, 2_000L,
                1, remote(), true, false, AS_OF));
    }

    static LinkViews.RuleRow rule() {
        return new LinkViews.RuleRow(RULE + "1", "buy", false, "minecraft:wheat", "Wheat", 4L, 64, 12, 1_000L, 48L,
                952L, false, true, "running", "Running.");
    }

    static JsonObject orders() {
        return LinkViews.orders(new LinkViews.Orders(
                List.of(new LinkViews.SellOrderRow(ORDER + "1", new LinkViews.Goods("minecraft:oak_log", "Oak Log", 64,
                        false), 6L, 82_800_000L),
                        new LinkViews.SellOrderRow(ORDER + "2", new LinkViews.Goods("minecraft:diamond_sword",
                                "Diamond Sword", 1, true), 420L, 3_600_000L)),
                List.of(new LinkViews.BuyOrderRow(ORDER + "3", new LinkViews.Goods("minecraft:iron_ingot", "Iron Ingot",
                        32, false), 5L, 160L, 43_200_000L)),
                List.of(new LinkViews.ShopRow(ORDER + "4", new LinkViews.Goods("minecraft:bread", "Bread", 40, false),
                        3L)),
                List.of(rule(), new LinkViews.RuleRow(RULE + "2", "sell", true, "minecraft:logs", "#minecraft:logs", 5L,
                        128, 0, 512L, 0L, 0L, true, false, "unreachable",
                        "Paused: its inventory is in a chunk that is not loaded.")),
                8, 300L, 10_000L, false, AS_OF));
    }

    static JsonObject vault() {
        return LinkViews.vault(new LinkViews.VaultView(List.of(
                new LinkViews.VaultRow(0, new LinkViews.Goods("minecraft:wheat", "Wheat", 64, false), 0),
                new LinkViews.VaultRow(1, new LinkViews.Goods("minecraft:diamond_pickaxe", "Diamond Pickaxe", 1, true), 7),
                // A name the server could not resolve falls back to one built from the item id.
                new LinkViews.VaultRow(2, new LinkViews.Goods("neroeconomy:credit_chip", "", 3, false), 0)), 3, 54,
                true, true, AS_OF));
    }

    static JsonObject stocks() {
        return LinkViews.stocks(new LinkViews.Stocks(true, List.of(
                new LinkViews.Stock("neroeconomy:redstone_works", "Redstone Works", false, "risky", "industry", 12_500L,
                        12_750L, 12_250L, 140, "", "Risky", 50, List.of(12_000L, 12_200L, 12_500L), List.of(11_800L,
                                12_100L)),
                new LinkViews.Stock("neroeconomy:index", "Index Fund", true, "steady", "", 10_000L, 10_050L, 9_950L, -20,
                        "", "Spread over every company", 0, List.of(10_020L, 10_000L), List.of())),
                List.of(new LinkViews.BondOffer("1d", "One-day Savings Bond", 1, 20),
                        new LinkViews.BondOffer("7d", "Seven-day Savings Bond", 7, 200)), 100, 60L, AS_OF));
    }

    static LinkViews.Book book(boolean practice) {
        return new LinkViews.Book(List.of(new LinkViews.Holding("neroeconomy:redstone_works", "Redstone Works", 10L,
                1_200L, 1_225L, 25L, 6L, 1_800_000L, "")),
                List.of(new LinkViews.BondRow(BOND + "1", "7d", "Seven-day Savings Bond", 7, 800L, 16L, 432_000_000L,
                        false)),
                1_200L, 1_225L, 25L, 800L, 16L, 2_000L, 50_000L, 10_000L, 5, practice ? 0L : 12L, practice ? 100 : 62,
                !practice, practice ? 9_000L : 0L, practice);
    }

    static JsonObject portfolio() { return LinkViews.portfolio(new LinkViews.Portfolio(book(false), book(true), AS_OF)); }

    static JsonObject offers() {
        return LinkViews.offers(new LinkViews.Offers(
                List.of(new LinkViews.OfferRow(OFFER + "1", List.of(new LinkViews.Goods("minecraft:emerald", "Emerald",
                        8, false)), 0L, new LinkViews.Goods("minecraft:wheat", "Wheat", 64, false), 50L, 86_400_000L)),
                List.of(new LinkViews.OfferRow(OFFER + "2", List.of(), 200L, null, 0L, 3_600_000L)), false, 16,
                AS_OF));
    }

    static JsonObject jobs() {
        return LinkViews.jobs(new LinkViews.Jobs("open", List.of(
                new LinkViews.JobRow(JOB + "1", "contract", "minecraft:oak_log", "Oak Log", 128L, 300L, 7_200_000L,
                        "open", false, false, true),
                // A bounty on a player names nobody: the viewer's own phrase for the target, as in game.
                new LinkViews.JobRow(JOB + "2", "bounty", "player", "a player", 1L, 500L, 3_600_000L, "open", false,
                        false, false)), 1, 4, AS_OF));
    }

    static JsonObject auctions() {
        return LinkViews.auctions(new LinkViews.Auctions("all", List.of(
                new LinkViews.AuctionRow(AUCTION + "1", new LinkViews.Goods("minecraft:netherite_ingot",
                        "Netherite Ingot", 2, false), 900L, 945L, 1_200_000L, false, true, false, false),
                new LinkViews.AuctionRow(AUCTION + "2", new LinkViews.Goods("minecraft:elytra", "Elytra", 1, false), 0L,
                        2_000L, 5_400_000L, false, false, true, true)), AS_OF));
    }

    /** Two bids at 5 (one level), one at 4; asks at 6 and 7. */
    static JsonObject book() {
        return LinkViews.book(new LinkViews.ItemBook("minecraft:wheat", "Wheat",
                LinkViews.levels(List.of(new LinkViews.BookOrder(5L, 64L), new LinkViews.BookOrder(5L, 32L),
                        new LinkViews.BookOrder(4L, 128L))),
                LinkViews.levels(List.of(new LinkViews.BookOrder(6L, 40L), new LinkViews.BookOrder(7L, 200L))), AS_OF));
    }

    static JsonObject alert() { return LinkViews.alert(EconomyAlerts.Kind.ORDER_FILLED.id(), AS_OF); }

    // --- action results ----------------------------------------------------------------------

    static JsonObject result(String action, String message, JsonObject detail, boolean spends) {
        return LinkViews.actionResult(balance(), action, message, detail, spends ? remote() : null);
    }

    /** One sample per action, by action id; {@code trade} keeps its schema-1 samples beside these. */
    static Map<String, JsonObject> actionResults() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        out.put("order_place", result("order_place", LinkText.get("link.neroeconomy.order_placed_buy"),
                LinkViews.orderPlaced(ORDER + "5", "buy", "minecraft:wheat", 64L, 4L, 16L, 48L, 1L, 256L), true));
        out.put("order_cancel", result("order_cancel", LinkText.get("link.neroeconomy.order_cancelled"),
                LinkViews.idOnly("orderId", ORDER + "5"), false));
        out.put("rule_set", result("rule_set", LinkText.get("link.neroeconomy.rule_added"),
                LinkViews.ruleSet(rule(), 1_000L), true));
        out.put("rule_delete", result("rule_delete", LinkText.get("link.neroeconomy.rule_deleted"),
                LinkViews.ruleDeleted(RULE + "1", 952L, false), false));
        out.put("rules_pause", result("rules_pause", LinkText.get("link.neroeconomy.rules_paused", 2),
                LinkViews.rulesPaused(true, 2), false));
        out.put("stock_buy", result("stock_buy", LinkText.get("link.neroeconomy.stock_bought"),
                LinkViews.stockTrade("neroeconomy:redstone_works", "buy", 4L, 12_750L, 510L, 3L, 513L, 0L, 0L, 0L, 0L,
                        false), true));
        JsonObject sold = LinkViews.stockTrade("neroeconomy:redstone_works", "sell", 4L, 12_250L, 490L, 2L, 488L, 480L,
                10L, 0L, 0L, false);
        sold.addProperty("sold", true);
        out.put("stock_sell", result("stock_sell", LinkText.get("link.neroeconomy.stock_sold"), sold, false));
        out.put("bond_buy", result("bond_buy", LinkText.get("link.neroeconomy.bond_bought"),
                LinkViews.bondBought(BOND + "2", "7d", 500L, 10L, 500L, 604_800_000L, false), true));
        out.put("pay", result("pay", LinkText.get("link.neroeconomy.paid"), LinkViews.paid(150L), true));
        out.put("offer_send", result("offer_send", LinkText.get("link.neroeconomy.offer_sent"),
                LinkViews.offerSent(OFFER + "3", 100L, 8L, 172_800_000L), true));
        out.put("offer_accept", result("offer_accept", LinkText.get("link.neroeconomy.offer_accepted"),
                LinkViews.offerAccepted(OFFER + "1", 0L, 50L, 1L, 8L), true));
        out.put("offer_decline", result("offer_decline", LinkText.get("link.neroeconomy.offer_declined"),
                LinkViews.idOnly("offerId", OFFER + "1"), false));
        out.put("offer_cancel", result("offer_cancel", LinkText.get("link.neroeconomy.offer_cancelled"),
                LinkViews.offerCancelled(OFFER + "2", 200L, 0L, false), false));
        out.put("claim_collect", result("claim_collect", LinkText.get("link.neroeconomy.collected", 64L),
                LinkViews.collected(64L), false));
        out.put("vault_deposit", result("vault_deposit", LinkText.get("link.neroeconomy.deposited", 32L),
                LinkViews.deposited(new LinkViews.Goods("minecraft:cobblestone", "Cobblestone", 32, false)), false));
        out.put("vault_withdraw", result("vault_withdraw", LinkText.get("link.neroeconomy.withdrew", 64L),
                LinkViews.withdrew(64L), false));
        return out;
    }

    /**
     * Results that share an action id with a sample above, by golden-file suffix: the reduced-gain
     * refusal a {@code stock_sell} sends as a result when the app asked with {@code structuredRefusal}.
     */
    static Map<String, JsonObject> extraResults() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        out.put("stock_sell_gain_reduced", result("stock_sell", LinkText.get("link.neroeconomy.gain_reduced", 40L, 100L),
                LinkViews.gainRefusal("neroeconomy:redstone_works", 10L, "gain_reduced", 100L, 40L), false));
        return out;
    }

    static Map<String, JsonObject> sections() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        out.put("summary", summary());
        out.put("orders", orders());
        out.put("vault", vault());
        out.put("stocks", stocks());
        out.put("portfolio", portfolio());
        out.put("offers", offers());
        out.put("jobs", jobs());
        out.put("auctions", auctions());
        out.put("market_book", book());
        return out;
    }

    // --- golden files ------------------------------------------------------------------------

    @Test void everySectionMatchesItsGoldenSample() throws IOException {
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, JsonObject> section : sections().entrySet())
            check("neroeconomy_" + section.getKey() + ".json", section.getValue(), written);
        check("neroeconomy_alerts_event.json", alert(), written);
        assertTrue(written.isEmpty(), "golden samples written, check and commit them: " + written);
    }

    @Test void everyActionResultMatchesItsGoldenSample() throws IOException {
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, JsonObject> action : actionResults().entrySet())
            check("neroeconomy_action_" + action.getKey() + ".json", action.getValue(), written);
        for (Map.Entry<String, JsonObject> action : extraResults().entrySet())
            check("neroeconomy_action_" + action.getKey() + ".json", action.getValue(), written);
        assertTrue(written.isEmpty(), "golden samples written, check and commit them: " + written);
    }

    @Test void thereIsASampleForEverySectionAndEveryAction() {
        Set<String> sections = sections().keySet();
        for (String section : NeroEconomyLinkModule.SECTIONS)
            if (!Set.of("balance", "transactions", "market").contains(section))
                assertTrue(sections.contains(section), "no sample for section " + section);
        for (String action : LinkActions.ALL)
            if (!action.equals("trade")) assertTrue(actionResults().containsKey(action), "no sample for action " + action);
    }

    private static void check(String name, JsonObject data, List<String> written) throws IOException {
        Path file = goldenDir().resolve(name);
        if (!Files.isRegularFile(file)) {
            JsonObject envelope = new JsonObject();
            envelope.addProperty("ok", true);
            envelope.add("data", data);
            String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(envelope);
            Files.writeString(file, text.replace("\n", "\r\n") + "\r\n", StandardCharsets.UTF_8);
            written.add(name);
            return;
        }
        JsonObject envelope = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(envelope.get("ok").getAsBoolean(), name);
        assertEquals(envelope.getAsJsonObject("data"), data, name);
    }

    static Path goldenDir() {
        for (Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath(); at != null; at = at.getParent()) {
            Path dir = at.resolve("common/src/test/resources/link/v2");
            if (Files.isDirectory(dir)) return dir;
        }
        fail("common/src/test/resources/link/v2 not found");
        return null;
    }

    // --- the keys the app will read ----------------------------------------------------------

    @Test void theSummaryCarriesTheBalanceKeysTheShippedAppReads() {
        JsonObject out = summary();
        // Balance.fromJson: `amount ?? balance` as num, `symbol` as String?, `history` optional.
        assertNumber(out, "amount");
        assertNumber(out, "balance");
        assertEquals("cr", out.get("symbol").getAsString());
        assertEquals(3_800L, out.getAsJsonObject("remote").get("leftToday").getAsLong());
        assertTrue(out.get("marketOpen").getAsBoolean());
    }

    @Test void everyRowCarriesItsOwnIdAndTheTypesThatArePinned() {
        JsonObject orders = orders();
        JsonObject sell = orders.getAsJsonArray("sell").get(0).getAsJsonObject();
        for (String key : List.of("qty", "unitPrice", "expiresIn")) assertNumber(sell, key);
        for (String key : List.of("orderId", "item", "displayName")) assertString(sell, key);
        // A modified stack says so in its name, as the market rows do.
        assertEquals("Diamond Sword (modified)",
                orders.getAsJsonArray("sell").get(1).getAsJsonObject().get("displayName").getAsString());
        JsonObject rule = orders.getAsJsonArray("rules").get(0).getAsJsonObject();
        for (String key : List.of("ruleId", "side", "target", "status", "statusText")) assertString(rule, key);
        assertTrue(rule.get("vault").getAsBoolean());
        JsonObject line = vault().getAsJsonArray("stacks").get(2).getAsJsonObject();
        assertEquals("Credit Chip", line.get("displayName").getAsString());
        assertTrue(vault().get("liveActions").getAsBoolean());
        assertTrue(vault().get("inGame").getAsBoolean());
        JsonObject away = LinkViews.vault(new LinkViews.VaultView(List.of(), 0, 54, AS_OF));
        assertFalse(away.get("inGame").getAsBoolean());
        assertFalse(away.get("liveActions").getAsBoolean());
        JsonObject stock = stocks().getAsJsonArray("stocks").get(0).getAsJsonObject();
        for (String key : List.of("priceCents", "askCents", "bidCents", "dayChangeBps")) assertNumber(stock, key);
        assertTrue(stock.get("tradeable").getAsBoolean());
        assertTrue(stock.get("history").isJsonArray());
        assertNumber(stocks(), "feeBps");
        assertEquals(60L, stocks().get("holdMinutes").getAsLong());
        JsonObject bond = stocks().getAsJsonArray("bonds").get(0).getAsJsonObject();
        assertNumber(bond, "days");
        assertNumber(bond, "rateBps");
        JsonObject portfolio = portfolio();
        assertEquals(62L, portfolio.getAsJsonObject("dividends").get("factorPercent").getAsLong());
        assertTrue(portfolio.getAsJsonObject("practice").get("canReset").getAsBoolean());
        assertFalse(portfolio.getAsJsonObject("practice").has("dividends"));
    }

    @Test void historiesAndListsAreCutToTheirBounds() {
        List<Long> long_ = new ArrayList<>();
        for (long index = 0; index < 100; index++) long_.add(index);
        JsonArray recent = LinkViews.tail(long_, LinkViews.RECENT_POINTS);
        assertEquals(LinkViews.RECENT_POINTS, recent.size());
        assertEquals(99L, recent.get(recent.size() - 1).getAsLong(), "newest last");
        List<LinkViews.VaultRow> many = new ArrayList<>();
        for (int index = 0; index < 500; index++)
            many.add(new LinkViews.VaultRow(index, new LinkViews.Goods("minecraft:dirt", "Dirt", 64, false), 0));
        assertEquals(LinkViews.MAX_VAULT_ROWS, LinkViews.vault(new LinkViews.VaultView(many, 500, 54, AS_OF))
                .getAsJsonArray("stacks").size());
        List<LinkViews.JobRow> jobs = new ArrayList<>();
        for (int index = 0; index < 500; index++)
            jobs.add(new LinkViews.JobRow(JOB + "0", "contract", "minecraft:dirt", "Dirt", 1L, 1L, 1L, "open", false,
                    false, false));
        assertEquals(LinkViews.MAX_ROWS, LinkViews.jobs(new LinkViews.Jobs("open", jobs, 0, 4, AS_OF))
                .getAsJsonArray("jobs").size());
    }

    @Test void aReducedGainRefusalSaysWhatCanBePaidAndHowToAcceptIt() {
        JsonObject out = extraResults().get("stock_sell_gain_reduced");
        assertEquals("stock_sell", out.get("action").getAsString());
        assertFalse(out.get("sold").getAsBoolean());
        assertEquals("gain_reduced", out.get("refusal").getAsString());
        assertEquals(40L, out.get("payable").getAsLong());
        assertEquals(60L, out.get("gainForfeited").getAsLong());
        assertEquals(LinkViews.ACCEPT_GAIN_PARAM, out.get("acceptParam").getAsString());
        assertEquals(40L, out.get("acceptValue").getAsLong());
        assertString(out, "message");
        assertFalse(out.has("remote"), "nothing was spent");
        assertTrue(actionResults().get("stock_sell").get("sold").getAsBoolean());
        for (String key : balance().keySet()) assertEquals(balance().get(key), out.get(key), key);
    }

    @Test void aBookFoldsOrdersIntoPriceLevelsBestFirstAndIsCutToItsDepth() {
        JsonObject out = book();
        JsonArray bids = out.getAsJsonArray("bids");
        assertEquals(2, bids.size());
        JsonObject top = bids.get(0).getAsJsonObject();
        assertEquals(5L, top.get("unitPrice").getAsLong());
        assertEquals(96L, top.get("qty").getAsLong());
        assertEquals(2L, top.get("orders").getAsLong());
        assertEquals(5L, out.get("bestBid").getAsLong());
        assertEquals(6L, out.get("bestAsk").getAsLong());
        assertEquals(Set.of("unitPrice", "qty", "orders"), top.keySet(), "counts only, never an owner");
        List<LinkViews.BookOrder> many = new ArrayList<>();
        for (long price = 1; price <= 40; price++) {
            many.add(new LinkViews.BookOrder(price, 1L));
            many.add(new LinkViews.BookOrder(price, 2L));
        }
        many.add(1, new LinkViews.BookOrder(0L, 5L));
        List<LinkViews.Level> levels = LinkViews.levels(many);
        assertEquals(LinkViews.BOOK_DEPTH, levels.size());
        assertEquals(new LinkViews.Level(1L, 3L, 2), levels.get(0), "a bad order is skipped, not a level breaker");
        assertEquals(new LinkViews.Level(Long.MAX_VALUE, Long.MAX_VALUE, 2), LinkViews.levels(List.of(
                new LinkViews.BookOrder(Long.MAX_VALUE, Long.MAX_VALUE), new LinkViews.BookOrder(Long.MAX_VALUE, 1L))).get(0));
        // No item: an empty book, both best prices 0.
        JsonObject empty = LinkViews.book(new LinkViews.ItemBook("", "", List.of(), List.of(), AS_OF));
        assertEquals(0L, empty.get("bestBid").getAsLong());
        assertEquals(0, empty.getAsJsonArray("asks").size());
    }

    @Test void anActionResultIsTheBalanceObjectPlusWhatTheActionDid() {
        JsonObject balance = balance();
        for (Map.Entry<String, JsonObject> action : actionResults().entrySet()) {
            for (String key : balance.keySet())
                assertEquals(balance.get(key), action.getValue().get(key), action.getKey() + ": " + key);
            assertEquals(action.getKey(), action.getValue().get("action").getAsString());
        }
        JsonObject out = actionResults().get("pay");
        assertEquals(150L, out.get("paid").getAsLong());
        assertString(out, "message");
        assertEquals(3_800L, out.getAsJsonObject("remote").get("leftToday").getAsLong());
        // An action that cannot spend says nothing about the limit.
        assertFalse(actionResults().get("order_cancel").has("remote"));
    }

    // --- privacy -----------------------------------------------------------------------------

    @Test void nothingNamesOrIdentifiesAnotherPlayer() {
        List<JsonObject> all = new ArrayList<>(sections().values());
        all.addAll(actionResults().values());
        all.addAll(extraResults().values());
        all.add(alert());
        Set<String> forbidden = Set.of("player", "playerUuid", "uuid", "name", "seller", "buyer", "owner", "issuer",
                "bidder", "claimant", "sender", "recipient", "source", "destination", "account", "counterparty",
                "location", "pos", "x", "y", "z", "dimension");
        for (JsonObject object : all) {
            assertNoKey(object, forbidden);
            String text = object.toString();
            assertFalse(text.contains(OTHER_PLAYER), "another player's id");
            assertFalse(text.contains(OTHER_NAME), "another player's name");
        }
        // The other side of an offer is the fixed words, as in game.
        JsonObject offers = offers();
        assertEquals(LinkText.get(LinkViews.A_PLAYER), offers.getAsJsonArray("inbox").get(0).getAsJsonObject()
                .get("from").getAsString());
        assertEquals(LinkText.get(LinkViews.A_PLAYER), offers.getAsJsonArray("outbox").get(0).getAsJsonObject()
                .get("to").getAsString());
    }

    @Test void anAlertEventIsTheFixedSentenceOfItsKindOnly() {
        JsonObject out = alert();
        assertEquals("neroeconomy:order_filled", out.get("alertId").getAsString());
        assertEquals(EconomyAlerts.Kind.ORDER_FILLED.text(), out.get("text").getAsString());
        assertEquals(Set.of("alertId", "kind", "text", "asOf"), out.keySet());
        assertEquals("", LinkViews.alert("no_such_kind", AS_OF).get("text").getAsString());
    }

    // --- helpers -----------------------------------------------------------------------------

    private static void assertNumber(JsonObject object, String key) {
        JsonElement value = object.get(key);
        assertNotNull(value, key);
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), key + " must be a JSON number");
    }

    private static void assertString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        assertNotNull(value, key);
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), key + " must be a JSON string");
    }

    private static void assertNoKey(JsonElement element, Set<String> forbidden) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                assertFalse(forbidden.contains(entry.getKey()), "forbidden key " + entry.getKey());
                assertNoKey(entry.getValue(), forbidden);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) assertNoKey(value, forbidden);
        }
    }
}
