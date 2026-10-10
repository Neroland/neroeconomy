package za.co.neroland.neroeconomy.commodity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityApi;
import za.co.neroland.neroeconomy.api.CommodityClass;

/** Commodity defaults and exclusions registered in code by other mods. */
class CommodityDefaultsTest {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CommodityDefaults defaults = new CommodityDefaults(Set.of("mymod")::contains, closed::get);

    private static CommodityApi.CommoditySpec spec(String key, long price) {
        return new CommodityApi.CommoditySpec(key, price, CommodityClass.STAPLE, null, null, null, null, null, null,
                null, null);
    }

    /** A world with mymod's two items, a tag of one of them, and exclusions taken from {@code defaults}. */
    private CommodityCatalog world() {
        CommodityCatalog base = CommodityCatalog.of(Set.of("mymod"), Set.of("mymod:ore", "mymod:ingot", "mymod:relic"),
                Map.of("c:ores", List.of("mymod:ore"), "c:empty", List.of()), Set.of());
        return new CommodityCatalog() {
            @Override public boolean modLoaded(String modId) { return base.modLoaded(modId); }
            @Override public boolean itemKnown(String item) { return base.itemKnown(item); }
            @Override public Collection<String> tagItems(String tag) { return base.tagItems(tag); }
            @Override public String refusal(String item) {
                return defaults.excluded(item) ? NEVER_COMMODITY : base.refusal(item);
            }
        };
    }

    @Test void callersAndKeysAreChecked() {
        assertEquals(CommodityApi.Registration.BAD_CALLER, defaults.register("othermod", spec("othermod:x", 5L)));
        assertEquals(CommodityApi.Registration.BAD_CALLER, defaults.register("neroeconomy", spec("neroeconomy:x", 5L)));
        assertEquals(CommodityApi.Registration.BAD_KEY, defaults.register("mymod", spec("not a key", 5L)));
        assertEquals(CommodityApi.Registration.FOREIGN_KEY, defaults.register("mymod", spec("minecraft:diamond", 5L)));
        assertEquals(CommodityApi.Registration.FOREIGN_KEY, defaults.register("mymod", spec("#minecraft:logs", 5L)));
        assertEquals(CommodityApi.Registration.INVALID, defaults.register("mymod", spec("mymod:ore", 0L)));
        assertEquals(CommodityApi.Registration.ACCEPTED, defaults.register("mymod", spec("#c:ores", 5L)));
        assertEquals(CommodityApi.Registration.ACCEPTED, defaults.register("mymod", spec("mymod:ingot", 30L)));
        assertEquals(CommodityApi.Registration.INVALID, defaults.register("mymod", spec("mymod:ingot", 31L)), "twice");
        assertEquals(CommodityApi.Registration.FOREIGN_KEY, defaults.exclude("mymod", "minecraft:diamond"));
        closed.set(true);
        assertEquals(CommodityApi.Registration.TOO_LATE, defaults.register("mymod", spec("mymod:relic", 5L)));
        assertEquals(CommodityApi.Registration.TOO_LATE, defaults.exclude("mymod", "mymod:relic"));
    }

    @Test void dataBeatsCodeAndCodeLoadsWhatDataLeavesOut() {
        defaults.register("mymod", spec("mymod:ingot", 30L));
        defaults.register("mymod", spec("mymod:ore", 4L));
        Map<String, JsonElement> data = Map.of("mymod:pack", JsonParser.parseString(
                "{\"item\": \"mymod:ingot\", \"reference_price\": 99}"));
        CommodityRegistry registry = CommodityParser.registry(data, defaults.files(), CommodityParser.DEFAULT_SPREAD_PERCENT,
                world());
        assertEquals("mymod:pack", registry.get("mymod:ingot").file(), "the data entry wins");
        assertEquals(99L, registry.get("mymod:ingot").referencePrice());
        assertEquals("code:mymod", registry.get("mymod:ore").file(), "the default's source is code:<modid>");
        assertTrue(registry.report().problems().isEmpty(), () -> registry.report().problems().toString());
    }

    @Test void aTagWithNoMembersIsSkippedQuietly() {
        defaults.register("mymod", spec("#c:empty", 5L));
        CommodityRegistry registry = CommodityParser.registry(Map.of(), defaults.files(),
                CommodityParser.DEFAULT_SPREAD_PERCENT, world());
        assertNull(registry.get("#c:empty"));
        assertTrue(registry.report().problems().isEmpty());
    }

    @Test void anExclusionBeatsData() {
        assertEquals(CommodityApi.Registration.ACCEPTED, defaults.exclude("mymod", "mymod:relic"));
        Map<String, JsonElement> data = Map.of("datapack:relics", JsonParser.parseString(
                "{\"item\": \"mymod:relic\", \"reference_price\": 500}"));
        CommodityRegistry registry = CommodityParser.registry(data, defaults.files(), CommodityParser.DEFAULT_SPREAD_PERCENT,
                world());
        assertNull(registry.forItem("mymod:relic"));
        assertEquals(CommodityCatalog.NEVER_COMMODITY, registry.report().problems().get(0).word());
        assertNotNull(defaults.files());
    }
}
