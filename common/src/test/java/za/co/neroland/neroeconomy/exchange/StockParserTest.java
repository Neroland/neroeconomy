package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.trade.ItemCatalog;

/** The stock data files: what a good one comes to, what is refused, and the twelve that ship. */
class StockParserTest {
    private static final String GOOD = """
            {
              "name": "stock.test.good",
              "sector": "mining",
              "type": "steady",
              "start_price": 120,
              "volatility": 2.0,
              "drift": 15.0,
              "dividend_yield": 1.5,
              "linked": ["#c:ingots/iron", "minecraft:coal"]
            }""";

    private static JsonObject good() { return JsonParser.parseString(GOOD).getAsJsonObject(); }

    private static String problem(JsonElement json) {
        StockParser.Parsed parsed = StockParser.parse("test:company", json);
        assertFalse(parsed.ok());
        assertNull(parsed.definition());
        return parsed.problem();
    }

    private static JsonObject with(String key, String json) {
        JsonObject object = good();
        object.add(key, JsonParser.parseString(json));
        return object;
    }

    private static JsonObject without(String key) {
        JsonObject object = good();
        object.remove(key);
        return object;
    }

    // --- a good file -----------------------------------------------------------------------------

    @Test void aGoodFileBecomesACompany() {
        StockParser.Parsed parsed = StockParser.parse("test:company", good());
        assertTrue(parsed.ok());
        assertNull(parsed.problem());
        StockDefinition definition = parsed.definition();
        assertEquals("test:company", definition.id());
        assertEquals("stock.test.good", definition.nameKey());
        // The description key defaults to the name key with ".description".
        assertEquals("stock.test.good.description", definition.descriptionKey());
        assertEquals("mining", definition.sector());
        assertEquals(StockType.STEADY, definition.type());
        assertTrue(definition.steady());
        assertEquals(120L, definition.startPrice());
        assertEquals(12_000L, definition.startCents());
        assertEquals(2.0D, definition.volatility());
        assertEquals(15.0D, definition.drift());
        assertEquals(1.5D, definition.dividendYield());
        assertEquals(List.of("#c:ingots/iron", "minecraft:coal"), definition.linked());
        assertTrue(definition.enabled());
        assertThrows(UnsupportedOperationException.class, () -> definition.linked().add("minecraft:dirt"));
    }

    @Test void optionalFieldsHaveDefaultsAndMayBeGiven() {
        StockDefinition plain = StockParser.parse("test:company", without("dividend_yield")).definition();
        assertEquals(0.0D, plain.dividendYield());
        JsonObject full = with("description", "\"stock.test.other\"");
        full.addProperty("enabled", false);
        full.addProperty("type", "risky");
        full.add("linked", JsonParser.parseString("[]"));
        StockDefinition definition = StockParser.parse("pack:deep/nested_name", full).definition();
        assertEquals("pack:deep/nested_name", definition.id());
        assertEquals("stock.test.other", definition.descriptionKey());
        assertFalse(definition.enabled());
        assertEquals(StockType.RISKY, definition.type());
        assertTrue(definition.linked().isEmpty());
        // A whole number written with a fraction of zero is still a whole number.
        assertEquals(45L, StockParser.parse("test:company", with("start_price", "45.0")).definition().startPrice());
        // Drift may be negative: a company in decline.
        assertEquals(-10.0D, StockParser.parse("test:company", with("drift", "-10")).definition().drift());
    }

    @Test void aCompanyIsLinkedToItemsAndToTheTagsTheyAreIn() {
        StockDefinition definition = StockParser.parse("test:company", good()).definition();
        ItemCatalog tags = new ItemCatalog() {
            @Override public boolean known(String item) { return true; }
            @Override public boolean tradeable(String item) { return true; }
            @Override public Set<String> tagsOf(String item) {
                return "minecraft:iron_ingot".equals(item) ? Set.of("c:ingots/iron", "c:ingots") : Set.of();
            }
            @Override public boolean tagKnown(String tag) { return true; }
        };
        assertTrue(definition.linksItem("minecraft:coal", tags));
        assertTrue(definition.linksItem("minecraft:iron_ingot", tags));
        assertFalse(definition.linksItem("minecraft:gold_ingot", tags));
        assertFalse(definition.linksItem(null, tags));
        // Without tags only the item ids match; a tag that does not exist never does.
        assertTrue(definition.linksItem("minecraft:coal", ItemCatalog.OPEN));
        assertFalse(definition.linksItem("minecraft:iron_ingot", ItemCatalog.OPEN));
        assertFalse(definition.linksItem("minecraft:iron_ingot", null));
    }

    // --- refusals --------------------------------------------------------------------------------

    @Test void aFileThatIsNotAnObjectOrHasAnUnknownFieldIsRefused() {
        assertEquals("not_an_object", problem(null));
        assertEquals("not_an_object", problem(JsonParser.parseString("[1, 2]")));
        assertEquals("not_an_object", problem(JsonParser.parseString("\"stock\"")));
        assertEquals("unknown_field", problem(with("colour", "\"red\"")));
        // A misspelt optional field is caught the same way instead of being silently ignored.
        assertEquals("unknown_field", problem(with("dividend", "2.0")));
        assertFalse(StockParser.parse("Not An Id", good()).ok());
        assertEquals("bad_id", StockParser.parse("Not An Id", good()).problem());
        assertEquals("bad_id", StockParser.parse(null, good()).problem());
    }

    @Test void everyRequiredFieldIsMissedByName() {
        assertEquals("missing_name", problem(without("name")));
        assertEquals("missing_sector", problem(without("sector")));
        assertEquals("missing_type", problem(without("type")));
        assertEquals("missing_start_price", problem(without("start_price")));
        assertEquals("missing_volatility", problem(without("volatility")));
        assertEquals("missing_drift", problem(without("drift")));
        assertEquals("missing_linked", problem(without("linked")));
    }

    @Test void aFieldOfTheWrongKindOrOutOfRangeIsRefused() {
        assertEquals("bad_name", problem(with("name", "5")));
        assertEquals("bad_name", problem(with("name", "\"Has Spaces\"")));
        assertEquals("bad_name", problem(with("name", "\"\"")));
        assertEquals("bad_description", problem(with("description", "\"Not A Key\"")));
        assertEquals("bad_description", problem(with("description", "true")));
        assertEquals("bad_sector", problem(with("sector", "\"Heavy Industry\"")));
        assertEquals("bad_sector", problem(with("sector", "[]")));
        assertEquals("bad_type", problem(with("type", "\"bluechip\"")));
        assertEquals("bad_type", problem(with("type", "\"STEADY\"")));
        assertEquals("bad_type", problem(with("type", "1")));
        assertEquals("bad_start_price", problem(with("start_price", "0")));
        assertEquals("bad_start_price", problem(with("start_price", "-5")));
        assertEquals("bad_start_price", problem(with("start_price", "12.5")));
        assertEquals("bad_start_price", problem(with("start_price", "1000001")));
        assertEquals("bad_start_price", problem(with("start_price", "\"120\"")));
        assertEquals("bad_volatility", problem(with("volatility", "0")));
        assertEquals("bad_volatility", problem(with("volatility", "-1")));
        assertEquals("bad_volatility", problem(with("volatility", "50.5")));
        assertEquals("bad_volatility", problem(with("volatility", "\"high\"")));
        assertEquals("bad_drift", problem(with("drift", "101")));
        assertEquals("bad_drift", problem(with("drift", "-51")));
        assertEquals("bad_drift", problem(with("drift", "null")));
        assertEquals("bad_dividend_yield", problem(with("dividend_yield", "-0.1")));
        assertEquals("bad_dividend_yield", problem(with("dividend_yield", "26")));
        assertEquals("bad_linked", problem(with("linked", "\"minecraft:coal\"")));
        assertEquals("bad_linked", problem(with("linked", "[\"Not An Item\"]")));
        assertEquals("bad_linked", problem(with("linked", "[\"#\"]")));
        assertEquals("bad_linked", problem(with("linked", "[\"coal\"]")));
        assertEquals("bad_linked", problem(with("linked", "[5]")));
        // No entry twice.
        assertEquals("bad_linked", problem(with("linked", "[\"minecraft:coal\", \"minecraft:coal\"]")));
        StringBuilder many = new StringBuilder("[");
        for (int index = 0; index <= StockDefinition.MAX_LINKED; index++)
            many.append(index == 0 ? "" : ",").append("\"minecraft:item_").append(index).append('"');
        assertEquals("bad_linked", problem(with("linked", many.append(']').toString())));
        assertEquals("bad_enabled", problem(with("enabled", "\"yes\"")));
        assertEquals("bad_enabled", problem(with("enabled", "1")));
    }

    @Test void aDefinitionCannotBeBuiltOutOfRangeEither() {
        assertThrows(IllegalArgumentException.class, () -> new StockDefinition("test:x", "a.b", "a.b.c", "mining",
                StockType.STEADY, 0L, 2.0D, 15.0D, 0.0D, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new StockDefinition("test:x", "a.b", "a.b.c", "mining",
                StockType.STEADY, 10L, Double.NaN, 15.0D, 0.0D, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new StockDefinition("test:x", "a.b", "a.b.c", "mining",
                null, 10L, 2.0D, 15.0D, 0.0D, List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new StockDefinition("test:x", "a.b", "a.b.c", "mining",
                StockType.STEADY, 10L, 2.0D, 15.0D, 0.0D, List.of("bad link"), true));
    }

    // --- a whole data pack -----------------------------------------------------------------------

    @Test void badFilesAreSkippedWithOneWarningEachAndTheGoodOnesStillLoad() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("test:zinc", good());
        files.put("test:broken", with("start_price", "-1"));
        files.put("test:alpha", good());
        files.put("test:unreadable", null);
        files.put("test:off", with("enabled", "false"));
        files.put("test:typo", with("volatilty", "3"));
        List<String> warnings = new ArrayList<>();
        StockRegistry registry = StockParser.registry(files, (id, problem) -> warnings.add(id + " " + problem));
        // In id order, whatever order the files came in; the switched-off one is not listed and not warned about.
        assertEquals(List.of("test:alpha", "test:zinc"), registry.stocks().stream().map(StockDefinition::id).toList());
        assertEquals(List.of("test:broken bad_start_price", "test:typo unknown_field", "test:unreadable not_an_object"),
                warnings);
        assertNotNull(registry.get("test:alpha"));
        assertNull(registry.get("test:off"));
        assertNull(registry.get("test:broken"));
        assertNull(registry.get(null));
        assertTrue(registry.available());
        // Nobody to warn: still no failure.
        assertEquals(2, StockParser.registry(files, null).stocks().size());
        assertTrue(StockParser.registry(Map.of(), null).stocks().isEmpty());
    }

    @Test void noMoreThanTheCapAreListedAndTheRestAreWarnedAbout() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (int index = 0; index < StockRegistry.MAX_LISTED + 3; index++)
            files.put(String.format("test:company_%03d", index), good());
        List<String> warnings = new ArrayList<>();
        StockRegistry registry = StockParser.registry(files, (id, problem) -> warnings.add(id + " " + problem));
        assertEquals(StockRegistry.MAX_LISTED, registry.stocks().size());
        assertEquals(List.of("test:company_064 too_many_stocks", "test:company_065 too_many_stocks",
                "test:company_066 too_many_stocks"), warnings);
    }

    // --- what ships ------------------------------------------------------------------------------

    @Test void twelveCompaniesShipHalfSteadyHalfRiskyAcrossTheEightSectors() {
        StockRegistry registry = ExchangeTesting.shipped();
        assertEquals(ExchangeTesting.SHIPPED, registry.stocks().stream().map(StockDefinition::id).toList());
        assertEquals(12, registry.stocks().size());
        int steady = 0;
        Set<String> sectors = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (StockDefinition definition : registry.stocks()) {
            if (definition.steady()) steady++;
            sectors.add(definition.sector());
            assertTrue(names.add(definition.nameKey()));
            assertTrue(definition.startPrice() >= 20L && definition.startPrice() <= 500L, definition.id());
            assertTrue(definition.enabled());
            assertFalse(definition.linked().isEmpty(), definition.id());
            // Steady shares are calm, grow slowly and pay a dividend; risky ones swing, grow fast and pay none.
            if (definition.steady()) {
                assertTrue(definition.volatility() >= 3.0D && definition.volatility() <= 4.5D, definition.id());
                assertTrue(definition.dividendYield() >= 1.0D && definition.dividendYield() <= 2.0D, definition.id());
                assertTrue(definition.drift() >= 5.0D && definition.drift() <= 8.0D, definition.id());
            } else {
                assertTrue(definition.volatility() >= 18.0D && definition.volatility() <= 25.0D, definition.id());
                assertEquals(0.0D, definition.dividendYield(), definition.id());
                assertTrue(definition.drift() >= 20.0D && definition.drift() <= 26.0D, definition.id());
            }
            // Each is linked to at least one real convention tag, so vanilla trade moves it.
            assertTrue(definition.linked().stream().anyMatch(link -> link.startsWith("#c:")), definition.id());
        }
        assertEquals(6, steady);
        assertEquals(Set.of("mining", "farming", "energy", "logistics", "space", "colonies", "creatures", "decor"),
                sectors);
    }

    @Test void everythingTheExchangeShowsHasAnEnglishString() throws IOException {
        JsonObject lang;
        try (InputStream stream = StockParserTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            // The lang file reaches the test classpath through the loader node's resources.
            assumeTrue(stream != null, "en_us.json is not on this test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>();
        for (StockDefinition definition : ExchangeTesting.shipped().stocks()) {
            keys.add(definition.nameKey());
            keys.add(definition.descriptionKey());
            keys.add("sector.neroeconomy." + definition.sector());
        }
        for (StockType type : StockType.values()) keys.add(type.translationKey());
        for (BondTerm term : BondTerm.values()) {
            keys.add(term.translationKey());
            keys.add(term.descriptionKey());
        }
        keys.add(IndexState.NAME_KEY);
        keys.add(IndexState.DESCRIPTION_KEY);
        keys.addAll(List.of("command.neroeconomy.stocks.header", "command.neroeconomy.stocks.off",
                "command.neroeconomy.stocks.none", "command.neroeconomy.stocks.row",
                "command.neroeconomy.stocks.row_halted", "command.neroeconomy.stocks.row_delisted",
                "command.neroeconomy.stocks.index", "command.neroeconomy.stocks.bond"));
        List<String> missing = keys.stream().filter(key -> !lang.has(key)).toList();
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }
}
