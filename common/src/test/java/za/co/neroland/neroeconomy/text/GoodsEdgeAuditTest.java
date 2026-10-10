package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * §13 Vault and items (J1 V1, V2): every edge where goods leave a live inventory or a block for the
 * economy asks {@code MarketSavedData.tradeable} (size bound and the {@code untradeable} tag) before
 * the stack becomes a lot. {@code lotOf} makes a lot without asking, so it may only follow that
 * question, or sit on a path that books system deliveries and migrations. The pure rule is
 * {@code MarketLimitsTest}; the encoded size of a real stack needs a running game.
 */
class GoodsEdgeAuditTest {
    /** Edges that take goods from players or blocks; each must ask the tradeable question. */
    private static final List<String> EDGES = List.of("menu/VaultView.java", "direct/LiveTrade.java",
            "offers/OfferStacks.java", "book/BookStacks.java", "link/LinkActions.java",
            "obligation/ObligationSavedData.java", "block/PlayerShopBlockEntity.java", "block/AutoTraderBlockEntity.java");
    /** Paths where lotOf needs no question: system deliveries, the migration of old rows, and the section itself. */
    private static final Set<String> NO_QUESTION = Set.of("vault/VaultStacks.java", "economy/EconomyStore.java",
            "goods/GoodsSection.java");
    private static final int LOOK_BACK = 14;

    @Test void everyEdgeAsksWhetherAStackIsTradeableBeforeItBecomesALot() throws IOException {
        Path base = SourceTree.main().resolve("java/za/co/neroland/neroeconomy");
        List<String> problems = new ArrayList<>();
        for (String edge : EDGES)
            if (!Files.readString(base.resolve(edge)).contains("MarketSavedData.tradeable("))
                problems.add(edge + " never asks MarketSavedData.tradeable");
        for (Path file : SourceTree.files(base, path -> path.toString().endsWith(".java"))) {
            String relative = base.relativize(file).toString().replace('\\', '/');
            if (NO_QUESTION.contains(relative)) continue;
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains(".lotOf(")) continue;
                boolean asked = false;
                for (int j = Math.max(0, i - LOOK_BACK); j < i && !asked; j++)
                    asked = lines.get(j).contains("tradeable(") || lines.get(j).contains("accepts(");
                if (!asked) problems.add(relative + ":" + (i + 1) + " makes a lot without asking tradeable");
            }
        }
        assertEquals(List.of(), problems);
    }
}
