package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * The wire contract with the companion app. The app is already installed, so its parser is the
 * specification: every key it reads is pinned here with the type it casts to, next to the schema-1
 * keys this mod must keep emitting for one more release.
 *
 * <p>The golden files under {@code common/src/test/resources/link} are what the builder produces
 * for the inputs below, wrapped in the bridge's {@code {"ok":true,"data":…}} envelope so they can be
 * dropped into the app as fixtures. They hold item ids, listing ids and amounts and nothing about a
 * person.
 */
class LinkPayloadsContractTest {
    private static final String CREDITS = "nerolandcore:credits";
    private static final long AS_OF = 1_791_000_000_000L;
    private static final String LISTING = "5f0c1a52-8f0e-4a51-9d2e-0b7c3a1e4d0";
    private static final String OFFER = "9b2d7c10-3e4f-4c6a-8a1b-6d5e4f3a2b1";

    /** Newest first. Economy time and wall time are the same here, as on a server whose clock never jumped. */
    private static List<LinkPayloads.Entry> ledger() {
        return List.of(
                entry(TransactionType.MARKET_PURCHASE, 120L, false, 1_790_999_400_000L),
                entry(TransactionType.MARKET_PAYOUT, 350L, true, 1_790_995_800_000L),
                entry(TransactionType.PLAYER_PAYMENT, 50L, false, 1_790_992_200_000L),
                entry(TransactionType.SHOP_PURCHASE, 200L, true, 1_790_988_600_000L),
                entry(TransactionType.STARTING_BALANCE, 1_000L, true, 1_790_902_200_000L));
    }

    private static LinkPayloads.Entry entry(TransactionType type, long amount, boolean incoming, long at) {
        return new LinkPayloads.Entry(type, amount, incoming, CREDITS, at, at);
    }

    private static JsonObject balance() { return LinkPayloads.balance(CREDITS, 1_380L, ledger(), AS_OF); }

    private static JsonObject market() {
        List<LinkPayloads.Row> listings = List.of(
                new LinkPayloads.Row(LISTING + "1", "minecraft:diamond", "Diamond", 12, 95L, false, true),
                new LinkPayloads.Row(LISTING + "2", "minecraft:diamond_sword", "Diamond Sword", 1, 420L, false, false),
                // The viewer's own listing: schema 1 still lists it, the app's rows leave it out.
                new LinkPayloads.Row(LISTING + "3", "minecraft:iron_ingot", "Iron Ingot", 64, 6L, true, true),
                // A name the server could not translate falls back to the item id.
                new LinkPayloads.Row(LISTING + "4", "neroeconomy:credit_chip", "item.neroeconomy.credit_chip", 3, 250L,
                        false, true));
        List<LinkPayloads.Row> offers = List.of(
                new LinkPayloads.Row(OFFER + "1", "minecraft:oak_log", "Oak Log", 128, 4L, false, true),
                new LinkPayloads.Row(OFFER + "2", "minecraft:wheat", "Wheat", 32, 3L, true, true));
        return LinkPayloads.market(listings, offers, 0, 25, 4, 2, AS_OF);
    }

    // --- balance -----------------------------------------------------------------------------

    @Test void balanceCarriesTheKeysTheAppReadsWithTheTypesItCastsTo() {
        JsonObject out = balance();
        // Balance.fromJson: `amount ?? balance` as num, `symbol` as String?, `history` as List of num.
        assertEquals(1_380L, number(out, "amount"));
        assertEquals("cr", string(out, "symbol"));
        JsonArray history = out.getAsJsonArray("history");
        for (JsonElement sample : history) assertTrue(isNumber(sample), "history holds numbers only");
        // SnapshotRepository.parseAsOf: `asOf` as num millis.
        assertEquals(AS_OF, number(out, "asOf"));
    }

    @Test void historyIsOldestFirstEndsAtTheBalanceAndIsRebuiltFromTheSignedRows() {
        assertEquals(List.of(0L, 1_000L, 1_200L, 1_150L, 1_500L, 1_380L), longs(balance().getAsJsonArray("history")));
        // No rows: one sample. The app draws its sparkline from two samples up.
        assertEquals(List.of(75L), longs(LinkPayloads.balance(CREDITS, 75L, List.of(), AS_OF).getAsJsonArray("history")));
        // Two rows are enough for a sparkline.
        assertTrue(LinkPayloads.balance(CREDITS, 1_200L, ledger().subList(3, 5), AS_OF)
                .getAsJsonArray("history").size() >= 2);
    }

    @Test void historyIsCappedAndNeverNegative() {
        List<LinkPayloads.Entry> many = new ArrayList<>();
        for (int index = 0; index < 60; index++)
            many.add(entry(TransactionType.DEPOSIT, 10L, true, 1_790_999_000_000L - index));
        List<Long> capped = longs(LinkPayloads.balance(CREDITS, 600L, many, AS_OF).getAsJsonArray("history"));
        assertEquals(LinkPayloads.HISTORY_POINTS, capped.size());
        assertEquals(600L, capped.get(capped.size() - 1));
        assertEquals(410L, capped.get(0));
        // Rows that would take the balance below zero (a log that lost a row) clamp at zero.
        List<Long> clamped = longs(LinkPayloads.balance(CREDITS, 5L,
                List.of(entry(TransactionType.DEPOSIT, 50L, true, 1L)), AS_OF).getAsJsonArray("history"));
        assertEquals(List.of(0L, 5L), clamped);
    }

    @Test void balanceKeepsTheSchemaOneKeys() {
        JsonObject out = balance();
        assertEquals(CREDITS, string(out, "currency"));
        assertEquals(1_380L, number(out, "balance"));
        JsonArray trend = out.getAsJsonArray("recentTrend");
        assertEquals(5, trend.size());
        JsonObject newest = trend.get(0).getAsJsonObject();
        assertEquals(Set.of("at", "amount", "reason"), newest.keySet());
        assertEquals(1_790_999_400_000L, number(newest, "at"));
        // Schema 1 amounts are unsigned.
        assertEquals(120L, number(newest, "amount"));
        assertEquals("MARKET_PURCHASE", string(newest, "reason"));
    }

    // --- transactions ------------------------------------------------------------------------

    @Test void transactionRowsCarryTheKeysTheAppReadsWithTheTypesItCastsTo() {
        JsonObject out = LinkPayloads.transactions(ledger(), 0, 3, AS_OF);
        JsonArray rows = out.getAsJsonArray("rows");
        assertEquals(3, rows.size());
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            // Transaction.fromJson: id as String, at as num, kind as String?, item as String, qty and amount as num.
            assertFalse(string(row, "id").isEmpty());
            assertTrue(number(row, "at") > 0L);
            assertFalse(string(row, "kind").isEmpty());
            assertEquals(string(row, "kind").toLowerCase(java.util.Locale.ROOT), string(row, "kind"));
            // A fixed description of the kind: never blank (the app titles the row with it), never an item id.
            assertFalse(string(row, "item").isEmpty());
            assertFalse(string(row, "item").contains(":"));
            assertEquals(0L, number(row, "qty"));
            assertTrue(isNumber(row.get("amount")));
        }
        JsonObject first = rows.get(0).getAsJsonObject();
        assertEquals("purchase", string(first, "kind"));
        // Signed from the player's side: a purchase is money out.
        assertEquals(-120L, number(first, "amount"));
        assertEquals("sale", string(rows.get(1).getAsJsonObject(), "kind"));
        assertEquals(350L, number(rows.get(1).getAsJsonObject(), "amount"));
        assertEquals("transfer", string(rows.get(2).getAsJsonObject(), "kind"));
        assertEquals(AS_OF, number(out, "asOf"));
    }

    @Test void nextCursorIsAStringAndIsAbsentOnTheLastPage() {
        JsonObject first = LinkPayloads.transactions(ledger(), 0, 3, AS_OF);
        // TransactionsPage.fromJson casts `nextCursor ?? cursor` to String?: a number throws in the app.
        assertTrue(first.get("nextCursor").isJsonPrimitive() && first.getAsJsonPrimitive("nextCursor").isString());
        assertEquals("3", string(first, "nextCursor"));
        assertFalse(first.has("cursor"));
        JsonObject last = LinkPayloads.transactions(ledger(), 3, 3, AS_OF);
        assertEquals(2, last.getAsJsonArray("rows").size());
        assertFalse(last.has("nextCursor"), "the last page has no nextCursor at all, not null and not -1");
        // A cursor past the end is an empty last page, not an error.
        JsonObject beyond = LinkPayloads.transactions(ledger(), 99, 3, AS_OF);
        assertEquals(0, beyond.getAsJsonArray("rows").size());
        assertFalse(beyond.has("nextCursor"));
        // The mod's own `cursor` parameter takes the string back as an offset.
        assertEquals(3, LinkPayloads.bounded(string(first, "nextCursor"), 0, 0, 1_000_000));
    }

    @Test void transactionsKeepTheSchemaOneItems() {
        JsonArray items = LinkPayloads.transactions(ledger(), 0, 3, AS_OF).getAsJsonArray("items");
        assertEquals(3, items.size());
        JsonObject first = items.get(0).getAsJsonObject();
        assertEquals(Set.of("reason", "amount", "currency", "timestamp"), first.keySet());
        assertEquals("MARKET_PURCHASE", string(first, "reason"));
        assertEquals(120L, number(first, "amount"));
        assertEquals(CREDITS, string(first, "currency"));
        assertEquals(1_790_999_400_000L, number(first, "timestamp"));
    }

    @Test void rowIdsAreStableAsNewRowsArriveAndDistinctWithinOneMillisecond() {
        List<LinkPayloads.Entry> before = List.of(
                entry(TransactionType.DEPOSIT, 5L, true, 2_000L),
                entry(TransactionType.AUCTION_BID, 80L, false, 1_000L),
                entry(TransactionType.ESCROW_REFUND, 80L, true, 1_000L));
        List<String> ids = LinkPayloads.rowIds(before);
        assertEquals(3, Set.copyOf(ids).size());
        assertEquals(List.of("tx-1jk-0", "tx-rs-0", "tx-rs-1"), ids);
        List<LinkPayloads.Entry> after = new ArrayList<>();
        after.add(entry(TransactionType.DEPOSIT, 1L, true, 3_000L));
        after.addAll(before);
        assertEquals(ids, LinkPayloads.rowIds(after).subList(1, 4));
    }

    @Test void everyLedgerReasonHasOneLowerCaseWord() {
        for (TransactionType type : TransactionType.values())
            for (boolean incoming : new boolean[] {true, false})
                assertTrue(LinkPayloads.kind(type, incoming).matches("[a-z]{3,12}"), type.name());
        // The two words the app was designed around.
        assertEquals("sale", LinkPayloads.kind(TransactionType.SHOP_PURCHASE, true));
        assertEquals("purchase", LinkPayloads.kind(TransactionType.SHOP_PURCHASE, false));
    }

    // --- market ------------------------------------------------------------------------------

    @Test void marketListingsCarryTheKeysTheAppReadsWithTheTypesItCastsTo() {
        JsonArray listings = market().getAsJsonArray("listings");
        for (JsonElement element : listings) {
            JsonObject row = element.getAsJsonObject();
            // MarketListing.fromJson: listingId as String (required), displayName as String, numbers as num.
            assertEquals(36, string(row, "listingId").length());
            assertFalse(string(row, "displayName").isEmpty());
            assertTrue(number(row, "unitPrice") >= 1L);
            assertTrue(number(row, "qtyAvailable") >= 1L);
            assertTrue(Set.of("buy", "sell").contains(string(row, "side")));
            assertTrue(string(row, "item").contains(":"));
        }
    }

    @Test void sideIsTheOperationTheViewerPerforms() {
        JsonArray listings = market().getAsJsonArray("listings");
        // A seller's listing is something the viewer buys; the app opens its sheet on Buy.
        JsonObject diamond = listings.get(0).getAsJsonObject();
        assertEquals(LISTING + "1", string(diamond, "listingId"));
        assertEquals("buy", string(diamond, "side"));
        // A standing buy offer is something the viewer sells into; `side == 'sell'` opens the sheet on Sell.
        JsonObject oak = listings.get(listings.size() - 1).getAsJsonObject();
        assertEquals(OFFER + "1", string(oak, "listingId"));
        assertEquals("sell", string(oak, "side"));
        assertEquals(128L, number(oak, "qtyAvailable"));
        assertEquals(4L, number(oak, "unitPrice"));
    }

    @Test void theViewersOwnRowsAreNotOfferedAsTradeable() {
        JsonObject out = market();
        List<String> ids = new ArrayList<>();
        out.getAsJsonArray("listings").forEach(row -> ids.add(string(row.getAsJsonObject(), "listingId")));
        assertEquals(List.of(LISTING + "1", LISTING + "2", LISTING + "4", OFFER + "1"), ids);
        // Totals count what matched on the server, the viewer's own rows included.
        assertEquals(6L, number(out, "total"));
        assertEquals(25L, number(out, "pageSize"));
        assertFalse(out.get("hasMore").getAsBoolean());
        assertTrue(LinkPayloads.market(List.of(), List.of(), 0, 25, 26, 0, AS_OF).get("hasMore").getAsBoolean());
        assertFalse(LinkPayloads.market(List.of(), List.of(), 1, 25, 26, 40, AS_OF).get("hasMore").getAsBoolean());
    }

    @Test void displayNamesAreReadableAndSayWhenAStackIsNotThePlainItem() {
        JsonArray listings = market().getAsJsonArray("listings");
        assertEquals("Diamond", string(listings.get(0).getAsJsonObject(), "displayName"));
        assertEquals("Diamond Sword (modified)", string(listings.get(1).getAsJsonObject(), "displayName"));
        assertFalse(listings.get(1).getAsJsonObject().get("pristine").getAsBoolean());
        assertEquals("Credit Chip", string(listings.get(2).getAsJsonObject(), "displayName"));
        assertEquals("Oak Log", LinkPayloads.displayName(null, "minecraft:oak_log"));
        assertEquals("Oak Log", LinkPayloads.displayName("  ", "minecraft:oak_log"));
        assertEquals("Oak Log", LinkPayloads.displayName("block.minecraft.oak_log", "minecraft:oak_log"));
        assertEquals("Raw Iron", LinkPayloads.displayName("Raw Iron", "minecraft:raw_iron"));
        assertEquals(LinkPayloads.MAX_NAME_LENGTH, LinkPayloads.displayName("x ".repeat(200), "a:b").length());
    }

    @Test void marketKeepsTheSchemaOneKeysAndDoesNotLookLikeABalance() {
        JsonObject out = market();
        assertEquals(0L, number(out, "page"));
        JsonArray items = out.getAsJsonArray("items");
        // Schema 1 listed every sell listing on the page, the viewer's own too, and no buy offers.
        assertEquals(4, items.size());
        JsonObject first = items.get(0).getAsJsonObject();
        assertEquals(Set.of("listingId", "item", "stock", "unitPrice"), first.keySet());
        assertEquals(12L, number(first, "stock"));
        // The app parses the market object through its balance model as well: none of these may
        // appear with another meaning.
        for (String key : List.of("history", "symbol", "amount", "balance")) assertFalse(out.has(key), key);
        assertEquals(AS_OF, number(out, "asOf"));
    }

    // --- trade result, event, throttling -----------------------------------------------------

    @Test void tradeResultCarriesTheReceiptKeysAndTheBalanceObject() {
        JsonObject balance = balance();
        JsonObject out = LinkPayloads.tradeResult(balance,
                new LinkPayloads.Trade("sell", OFFER + "1", 16, 4L, 64L, true, "credit_claim"));
        // _Receipt reads `price`, `total` and `balance` as num.
        assertEquals(4L, number(out, "price"));
        assertEquals(64L, number(out, "total"));
        assertEquals(1_380L, number(out, "balance"));
        assertTrue(out.getAsJsonPrimitive("filled").isBoolean());
        assertTrue(out.get("filled").getAsBoolean());
        assertEquals("sell", string(out, "op"));
        assertEquals(16L, number(out, "qty"));
        assertEquals("credit_claim", string(out, "delivery"));
        for (String key : balance.keySet()) assertEquals(balance.get(key), out.get(key), key);
        // The balance object handed in is not altered.
        assertFalse(balance.has("price"));
        // A posted listing moved nothing, and says so.
        assertFalse(LinkPayloads.tradeResult(balance, new LinkPayloads.Trade("sell", LISTING + "9", 4, 10L, 40L, false,
                "listed")).get("filled").getAsBoolean());
    }

    @Test void aThrottledSnapshotIsACopyOfTheLastOneWithAFlag() {
        JsonObject last = balance();
        JsonObject out = LinkPayloads.throttled(last);
        assertTrue(out.get("throttled").getAsBoolean());
        assertFalse(last.has("throttled"));
        assertEquals(1_380L, number(out, "amount"));
        assertEquals(last.get("asOf"), out.get("asOf"), "the data is still as of when it was read");
    }

    @Test void nothingEmittedNamesAPersonAnAccountOrAPlace() {
        List<JsonObject> all = List.of(balance(), LinkPayloads.transactions(ledger(), 0, 50, AS_OF), market(),
                LinkPayloads.tradeResult(balance(), new LinkPayloads.Trade("buy", LISTING + "1", 2, 95L, 190L, true,
                        "claim")));
        Set<String> forbidden = Set.of("player", "playerUuid", "uuid", "name", "seller", "buyer", "owner", "source",
                "destination", "sourceAccount", "destinationAccount", "account", "counterparty", "location", "pos");
        for (JsonObject object : all) assertNoKey(object, forbidden);
    }

    // --- golden files ------------------------------------------------------------------------

    @Test void goldenFilesAreExactlyWhatTheBuilderEmits() throws IOException {
        assertEquals(data("neroeconomy_balance.json"), balance());
        assertEquals(data("neroeconomy_transactions.json"), LinkPayloads.transactions(ledger(), 0, 3, AS_OF));
        assertEquals(data("neroeconomy_market.json"), market());

        List<LinkPayloads.Entry> afterBuy = new ArrayList<>();
        afterBuy.add(entry(TransactionType.MARKET_PURCHASE, 190L, false, 1_790_999_990_000L));
        afterBuy.addAll(ledger());
        assertEquals(data("neroeconomy_trade_buy.json"), LinkPayloads.tradeResult(
                LinkPayloads.balance(CREDITS, 1_190L, afterBuy, AS_OF),
                new LinkPayloads.Trade("buy", LISTING + "1", 2, 95L, 190L, true, "inventory")));
        assertEquals(data("neroeconomy_trade_sell_fill.json"), LinkPayloads.tradeResult(balance(),
                new LinkPayloads.Trade("sell", OFFER + "1", 16, 4L, 64L, true, "credit_claim")));

        // The event is the balance object; the bridge wraps it as one element of a `delta` frame.
        JsonObject frame = golden("neroeconomy_balance_event.json");
        assertEquals("neroeconomy.balance", string(frame, "topic"));
        assertEquals(balance(), frame.getAsJsonArray("delta").get(0).getAsJsonObject());
    }

    private static JsonObject data(String name) throws IOException {
        JsonObject envelope = golden(name);
        assertTrue(envelope.get("ok").getAsBoolean());
        return envelope.getAsJsonObject("data");
    }

    /** From the classpath when the build puts test resources there, otherwise straight from the repository. */
    private static JsonObject golden(String name) throws IOException {
        try (InputStream stream = LinkPayloadsContractTest.class.getResourceAsStream("/link/" + name)) {
            if (stream != null)
                return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath(); at != null; at = at.getParent()) {
            Path file = at.resolve("common/src/test/resources/link").resolve(name);
            if (Files.isRegularFile(file))
                return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        throw new IOException("golden file not found: " + name);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static boolean isNumber(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static long number(JsonObject object, String key) {
        assertTrue(isNumber(object.get(key)), key + " must be a JSON number");
        return object.get(key).getAsLong();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        assertNotNull(value, key);
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), key + " must be a JSON string");
        return value.getAsString();
    }

    private static List<Long> longs(JsonArray array) {
        List<Long> out = new ArrayList<>();
        array.forEach(value -> out.add(value.getAsLong()));
        return out;
    }

    private static void assertNoKey(JsonElement element, Set<String> forbidden) {
        if (element.isJsonArray()) element.getAsJsonArray().forEach(child -> assertNoKey(child, forbidden));
        if (!element.isJsonObject()) return;
        for (var member : element.getAsJsonObject().entrySet()) {
            assertFalse(forbidden.contains(member.getKey()), member.getKey());
            assertNoKey(member.getValue(), forbidden);
        }
    }
}
