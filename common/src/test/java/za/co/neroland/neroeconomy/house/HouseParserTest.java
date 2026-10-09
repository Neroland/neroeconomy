package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;

/** The House file format, the House a commodity goes to, and the Houses this mod ships. */
class HouseParserTest {
    private static final String[] SHIPPED = {"artisans", "engineers", "general", "growers", "hunters", "masons",
            "settlers", "smiths", "starport"};

    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    @Test void aFullFileParses() {
        HouseParser.Parsed parsed = HouseParser.parse("neroeconomy:smiths", json("{\"name\": \"house.neroeconomy.smiths\","
                + " \"working_capital\": 8000, \"restock_per_day\": 0.5, \"trades\": [\"metals\"], \"sectors\": [\"mining\"]}"));
        assertTrue(parsed.ok());
        HouseDefinition house = parsed.house();
        assertEquals("smiths", house.id());
        assertEquals(8000L, house.workingCapital());
        assertEquals(0.5D, house.restockPerDay());
        assertEquals(List.of("metals"), house.trades());
        assertEquals(List.of("mining"), house.sectors());
    }

    @Test void defaultsApplyAndTheNameFollowsTheFile() {
        HouseDefinition house = HouseParser.parse("somepack:quarry", json("{}")).house();
        assertEquals("house.somepack.quarry", house.nameKey());
        assertEquals(HouseDefinition.DEFAULT_WORKING_CAPITAL, house.workingCapital());
        assertEquals(HouseDefinition.DEFAULT_RESTOCK_PER_DAY, house.restockPerDay());
    }

    @Test void badFilesAreRefusedWithAWord() {
        assertEquals("unknown_field", HouseParser.parse("a:b", json("{\"capital\": 4}")).problem());
        assertEquals("bad_working_capital", HouseParser.parse("a:b", json("{\"working_capital\": -1}")).problem());
        assertEquals("bad_working_capital", HouseParser.parse("a:b", json("{\"working_capital\": 1.5}")).problem());
        assertEquals("bad_restock_per_day", HouseParser.parse("a:b", json("{\"restock_per_day\": 99}")).problem());
        assertEquals("bad_trades", HouseParser.parse("a:b", json("{\"trades\": [\"Bad Id\"]}")).problem());
        assertEquals("bad_sectors", HouseParser.parse("a:b", json("{\"sectors\": \"mining\"}")).problem());
        assertEquals("not_an_object", HouseParser.parse("a:b", json("[]")).problem());
        assertEquals("not_an_object", HouseParser.parse("a:b", null).problem());
        assertEquals("bad_house_id", HouseParser.parse("a:sub/b", json("{}")).problem());
        // A disabled House is neither a House nor a problem.
        HouseParser.Parsed disabled = HouseParser.parse("a:b", json("{\"enabled\": false}"));
        assertFalse(disabled.ok());
        assertNull(disabled.problem());
    }

    @Test void theFirstFileOfAnIdWinsAndGeneralIsAlwaysThere() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("zpack:quarry", json("{\"working_capital\": 2}"));
        files.put("apack:quarry", json("{\"working_capital\": 1}"));
        files.put("apack:broken", json("{\"oops\": 1}"));
        HouseRegistry registry = HouseParser.registry(files);
        assertEquals(1L, registry.get("quarry").workingCapital());
        assertEquals(HouseDefinition.BUILT_IN_FILE, registry.get("general").file());
        assertEquals(2, registry.problems().size());
    }

    @Test void aCommodityGoesToItsHouseThenTradesThenSectorThenGeneral() {
        HouseRegistry registry = HouseRegistry.of(List.of(
                new HouseDefinition("smiths", "a:smiths", "house.a.smiths", List.of("metals"), List.of("mining"), 10L, 1.0D),
                HouseDefinition.of("growers", 10L)));
        assertEquals("growers", registry.houseFor(commodity("growers", "")).id());
        assertEquals("smiths", registry.houseFor(commodity("metals", "")).id());
        assertEquals("smiths", registry.houseFor(commodity("nobody", "mining")).id());
        assertEquals("general", registry.houseFor(commodity("nobody", "space")).id());
    }

    private static CommodityDefinition commodity(String house, String sector) {
        return new CommodityDefinition("minecraft:stone", CommodityDefinition.DIRECT_FILE, 4L, 10L, 10L, 2L, 2L, 0.5D,
                0.1D, 1000, CommodityClass.STAPLE, house, sector, true, true);
    }

    @Test void theShippedHousesParseAndCoverEveryHouseTheShippedCommoditiesName() throws IOException {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (String id : SHIPPED) {
            JsonElement element = resource("/data/neroeconomy/neroeconomy/houses/" + id + ".json");
            if (element == null) return;
            files.put("neroeconomy:" + id, element);
        }
        HouseRegistry registry = HouseParser.registry(files);
        assertTrue(registry.problems().isEmpty(), registry.problems().toString());
        assertEquals(SHIPPED.length, registry.houses().size());
        for (HouseDefinition house : registry.houses()) assertFalse(house.file().equals(HouseDefinition.BUILT_IN_FILE));
        Set<String> named = new TreeSet<>();
        for (String file : List.of("building", "c_gems_and_dusts", "c_ingots", "farming", "metals_and_gems", "mob_drops",
                "compat/neroagriculture", "compat/nerocolonies", "compat/nerocreatures", "compat/nerodecor",
                "compat/nerologistics", "compat/neronotes", "compat/neropower", "compat/nerospace", "compat/nerotech")) {
            JsonElement element = resource("/data/neroeconomy/neroeconomy/commodities/" + file + ".json");
            if (element == null) continue;
            List<JsonElement> entries = new java.util.ArrayList<>();
            if (element.getAsJsonObject().has("commodities"))
                element.getAsJsonObject().getAsJsonArray("commodities").forEach(entries::add);
            else entries.add(element);
            for (JsonElement entry : entries)
                if (entry.getAsJsonObject().has("house")) named.add(entry.getAsJsonObject().get("house").getAsString());
        }
        for (String house : named) assertTrue(registry.get(house) != null, house + " has no House file");
    }

    private static JsonElement resource(String path) throws IOException {
        try (InputStream stream = HouseParserTest.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
