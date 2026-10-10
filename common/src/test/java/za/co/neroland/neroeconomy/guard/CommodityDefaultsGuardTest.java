package za.co.neroland.neroeconomy.guard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityApi;
import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityDefaults;
import za.co.neroland.neroeconomy.commodity.CommodityParser;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;

/** A code default goes through the arbitrage guard like data: a looping default loses its buy side. */
class CommodityDefaultsGuardTest {
    @Test void aLoopingDefaultHasItsBuySideTurnedOff() {
        CommodityDefaults defaults = new CommodityDefaults(Set.of("mymod")::contains, () -> false);
        // Sold cheap, and smelts into something the Houses buy dear: a money loop.
        defaults.register("mymod", new CommodityApi.CommoditySpec("mymod:ore", 2L, CommodityClass.STAPLE, 1_000L, 0L,
                null, null, null, null, null, null));
        defaults.register("mymod", new CommodityApi.CommoditySpec("mymod:ingot", 100L, CommodityClass.STAPLE, 0L, 1_000L,
                null, null, null, null, null, null));
        CommodityRegistry registry = CommodityParser.registry(Map.of(), defaults.files(),
                CommodityParser.DEFAULT_SPREAD_PERCENT,
                CommodityCatalog.of(Set.of("mymod"), Set.of("mymod:ore", "mymod:ingot"), Map.of(), Set.of()));
        Map<String, JsonElement> files = Map.of("test:smelt", JsonParser.parseString(
                "{\"type\": \"minecraft:smelting\", \"ingredient\": \"mymod:ore\", \"result\": {\"id\": \"mymod:ingot\"}}"));
        List<GuardRecipe> recipes = RecipeReader.readAll(files).recipes();
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, null, null, null,
                ArbitrageGuard.DEFAULT_DEPTH));
        assertTrue(!report.passed() && report.buyOff().contains("mymod:ingot"), report::toString);
    }
}
