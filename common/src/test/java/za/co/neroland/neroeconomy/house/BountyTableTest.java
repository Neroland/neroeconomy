package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.guard.GuardTesting;

/** The bounty data format: the shipped file, and every way a file can be wrong. */
class BountyTableTest {
    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    @Test void theShippedFileNamesOnlyHostileMobsWithSmallPayouts() {
        BountyTable table = BountyTable.parse(Map.of("neroeconomy:hostile",
                GuardTesting.read("common/src/main/resources/", "data/neroeconomy/neroeconomy/bounties/hostile.json")));
        assertTrue(table.problems().isEmpty(), table.problems().toString());
        assertTrue(table.entries().size() >= 4);
        for (BountyTable.Entry entry : table.entries()) {
            assertTrue(entry.entity().startsWith("minecraft:"));
            assertTrue(entry.payout() <= 100L && entry.quantity() <= 8, entry.toString());
        }
    }

    @Test void aBadFileIsSkippedWholeAndTheFirstFileWinsAMob() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("b:second", json("{\"bounties\":[{\"entity\":\"minecraft:zombie\",\"quantity\":2,\"payout\":9}]}"));
        files.put("a:first", json("{\"bounties\":[{\"entity\":\"minecraft:zombie\",\"quantity\":1,\"payout\":5}]}"));
        files.put("c:unknown", json("{\"bounties\":[],\"extra\":1}"));
        files.put("d:player", json("{\"bounties\":[{\"entity\":\"minecraft:player\",\"quantity\":1,\"payout\":5}]}"));
        files.put("e:dear", json("{\"bounties\":[{\"entity\":\"minecraft:husk\",\"quantity\":1,\"payout\":99999}]}"));
        files.put("f:missing", json("{\"bounties\":[{\"entity\":\"minecraft:husk\",\"quantity\":1}]}"));
        files.put("g:unreadable", null);
        BountyTable table = BountyTable.parse(files);
        assertEquals(List.of(new BountyTable.Entry("minecraft:zombie", 1, 5L)), table.entries());
        assertEquals(List.of("c:unknown", "d:player", "e:dear", "f:missing", "g:unreadable"),
                table.problems().stream().map(BountyTable.Problem::file).toList());
        assertEquals(0, table.filter(entity -> false).entries().size());
    }
}
