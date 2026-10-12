package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * §13 Flooding (J1 F8): "a hard bound and a purge on every store". Each section of the economy store
 * is either bounded by its own store-wide constant, or bounded by per-account caps times the number
 * of accounts, with the purge named. A new section must be added here with its reason, so none is
 * left unbounded by accident. Accounts themselves are bounded by the server's player base, which a
 * mod cannot bound on an {@code online-mode=false} server (README, operators).
 */
class StoreBoundsAuditTest {
    /** Sections with their own store-wide bound (a {@code MAX_} constant, each tested by its section's test). */
    private static final Map<String, String> HARD = Map.ofEntries(
            Map.entry("AutoTraderSection", "MAX_ROWS, MAX_PER_OWNER; purge: block removal + stale-location sweep"),
            Map.entry("SimulationSection", "MAX_STATE_ENTRIES; purge: handlers replace their state"),
            Map.entry("HouseSection", "MAX_COMMODITIES, MAX_ACCOUNT_ROWS, MAX_REFERENCE_ACCOUNTS, MAX_PENDING_ITEMS; purge: day roll"),
            Map.entry("RemoteSpendSection", "MAX_ACCOUNTS; purge: rows of earlier days"),
            Map.entry("SiblingSection", "MAX_CALLERS, MAX_PLAYERS, MAX_REQUEST_KEYS; purge: day roll, lapsed keys"),
            Map.entry("MilestoneSection", "MAX_PLAYERS; purge: erasure"),
            Map.entry("PerkSection", "MAX_PLAYERS; purge: waiver spent, erasure"),
            Map.entry("ExchangeSection", "MAX_STATES; purge: delisting"),
            Map.entry("GallerySandbox", "MAX_BLOCKS, MAX_LABELS; purge: gallery clear"),
            Map.entry("TradeEscrowSection", "MAX_STACKS per side, at most one open trade per online player; purge: swap/cancel/load"),
            Map.entry("ObligationSavedData", "MAX_SYSTEM_JOBS, MAX_HOUSE_AUCTIONS, per-player job/auction caps; purge: expiry"));
    /** Sections bounded by per-account caps times accounts, with their purge. */
    private static final Map<String, String> PER_ACCOUNT = Map.of(
            "VaultSection", "vaultHardBound per account; purge: erasure",
            "BookSection", "maxSellOrders/maxBuyOrders per account; purge: order expiry, erasure",
            "OffersSection", "maxOffersPerPlayer out, MAX_OFFERS_IN in, MAX_LOTS each; purge: offer expiry, erasure",
            "RulesSection", "maxRulesPerPlayer; purge: rule delete, erasure",
            "MarketSavedData", "one credit-claim row per account, shop listings one per placed shop; purge: expiry, stranded-shop sweep, erasure",
            "ShopOwnershipState", "one row per placed Player Shop; purge: stranded-shop sweep, erasure",
            "EconomySavedData", "one balance per account; transactions maxTransactionRecords + retention days",
            "GoodsSection", "prototypes only while a lot refers to them; purge: sweep once a minute",
            "AdminSection", "a fixed set of counters",
            "EconomyStore", "the container of the sections above");

    @Test void everySectionOfTheStoreIsBoundedAndSaysHow() throws IOException {
        Path java = SourceTree.main().resolve("java");
        Map<String, String> found = new TreeMap<>();
        for (Path file : SourceTree.files(java, path -> path.toString().endsWith(".java"))) {
            String text = Files.readString(file);
            if (text.contains(" extends EconomySection")) {
                String name = file.getFileName().toString().replace(".java", "");
                found.put(name, text);
            }
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> section : found.entrySet()) {
            String name = section.getKey();
            if (HARD.containsKey(name)) {
                if (!section.getValue().contains("static final int MAX_")) problems.add(name + " lost its MAX_ bound");
            } else if (!PER_ACCOUNT.containsKey(name)) {
                problems.add(name + " is a store section with no bound recorded here");
            }
        }
        for (String name : HARD.keySet()) if (!found.containsKey(name)) problems.add(name + " is no longer a section");
        for (String name : PER_ACCOUNT.keySet()) if (!found.containsKey(name)) problems.add(name + " is no longer a section");
        assertEquals(List.of(), problems);
    }
}
