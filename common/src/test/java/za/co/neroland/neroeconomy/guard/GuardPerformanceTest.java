package za.co.neroland.neroeconomy.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;

/**
 * The guard runs on the server thread when a world starts, after every {@code /reload} and for
 * {@code /eco admin reload-check}: over the whole vanilla recipe set it has to take well under a
 * second.
 */
class GuardPerformanceTest {
    private static final long LIMIT_MILLIS = 1_000L;
    private static final int RUNS = 5;

    /** The fastest of a few runs: the first carries class loading and an unwarmed JIT. */
    private static long best(Runnable work) {
        long best = Long.MAX_VALUE;
        for (int run = 0; run < RUNS; run++) {
            long started = System.nanoTime();
            work.run();
            best = Math.min(best, (System.nanoTime() - started) / 1_000_000L);
        }
        return best;
    }

    @Test void readingEveryVanillaRecipeAndCheckingTheShippedDataIsFast() {
        Map<String, JsonElement> files = GuardTesting.vanillaRecipes();
        CommodityRegistry registry = GuardTesting.shipped(GuardTesting.fullWorld());
        GuardBonuses bonuses = GuardTesting.shippedBonuses();
        ArbitrageGuard.Tags tags = GuardTesting.tags();
        boolean[] passed = {false};
        long millis = best(() -> {
            RecipeReader.Read recipes = RecipeReader.readAll(files);
            passed[0] = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes.recipes(), recipes.skipped(),
                    bonuses, tags, Map.of(GuardTesting.CREDIT_CHIP, 100L), ArbitrageGuard.DEFAULT_DEPTH)).passed();
        });
        assertTrue(passed[0]);
        System.out.println("[guard] read " + files.size() + " vanilla recipe files and checked the shipped data in "
                + millis + " ms");
        assertTrue(millis < LIMIT_MILLIS, () -> "reading and checking took " + millis + " ms");
    }

    @Test void theWorstCaseIsFastToo() {
        // Every vanilla item is a commodity the Houses both sell and buy, so every recipe is a
        // chain to compare and costs spread through all of them, at the deepest setting.
        List<CommodityDefinition> definitions = new ArrayList<>();
        Set<String> items = GuardTesting.vanillaItems();
        long price = 2L;
        for (String item : items) {
            definitions.add(CommodityDefinition.of(item, price, 100L, 100L, 1000, CommodityClass.STAPLE));
            price = price % 97L + 2L;
        }
        CommodityRegistry registry = CommodityRegistry.of(definitions, CommodityCatalog.of(Set.of(), items, Map.of(), Set.of()));
        RecipeReader.Read recipes = GuardTesting.allRecipes();
        ArbitrageGuard.Tags tags = GuardTesting.tags();
        GuardReport[] report = new GuardReport[1];
        long millis = best(() -> report[0] = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes.recipes(),
                recipes.skipped(), GuardTesting.shippedBonuses(), tags, Map.of(GuardTesting.CREDIT_CHIP, 100L),
                ArbitrageGuard.MAX_DEPTH)));
        // Prices picked without thought are full of profitable recipes, and the guard finds them.
        assertFalse(report[0].passed());
        assertTrue(report[0].chainsChecked() > 1000, () -> "only " + report[0].chainsChecked() + " chains were checked");
        System.out.println("[guard] worst case: " + registry.commodities().size() + " commodities, "
                + report[0].chainsChecked() + " chains checked at depth " + report[0].depth() + ", "
                + report[0].failures().size() + " failures kept, in " + millis + " ms");
        assertTrue(millis < LIMIT_MILLIS, () -> "the worst case took " + millis + " ms");
    }
}
