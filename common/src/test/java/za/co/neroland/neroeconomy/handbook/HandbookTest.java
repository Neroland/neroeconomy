package za.co.neroland.neroeconomy.handbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.client.demo.DemoScript;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.text.SourceTree;

/**
 * The Trader's Handbook: the chapters the design asks for, in order, each of two to five pages; every
 * word in the lang file and no page left over; every Open demo button pointing at a walkthrough that
 * exists and shows a sample view (never the operators' Admin tab); the data chapter names the contact.
 */
class HandbookTest {
    private static JsonObject lang;

    @BeforeAll static void read() throws IOException {
        lang = JsonParser.parseString(Files.readString(
                SourceTree.main().resolve("resources/assets/neroeconomy/lang/en_us.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test void theChaptersAreTheOnesTheDesignAsksForInReadingOrder() {
        List<String> ids = Handbook.CHAPTERS_IN_ORDER.stream().map(Handbook.Chapter::id).toList();
        assertEquals(List.of("credits", "vault", "market", "orders", "shops", "trading", "auto_trade", "investing",
                "houses", "jobs", "fair_play", "data"), ids);
        assertEquals(ids.size(), new HashSet<>(ids).size(), "a chapter id twice");
        for (Handbook.Chapter chapter : Handbook.CHAPTERS_IN_ORDER) {
            assertTrue(chapter.pages() >= Handbook.MIN_PAGES && chapter.pages() <= Handbook.MAX_PAGES,
                    chapter.id() + " has " + chapter.pages() + " pages");
            assertTrue(chapter.id().matches("[a-z_]+"), chapter.id());
        }
    }

    @Test void pagingStaysInsideTheBook() {
        assertEquals(Handbook.CHAPTERS_IN_ORDER.get(0), Handbook.chapter(-5));
        assertEquals(Handbook.CHAPTERS_IN_ORDER.get(Handbook.CHAPTERS_IN_ORDER.size() - 1), Handbook.chapter(99));
        assertEquals(0, Handbook.clampPage(0, -1));
        assertEquals(Handbook.chapter(0).pages() - 1, Handbook.clampPage(0, 99));
        assertEquals(1, Handbook.clampPage(0, 1));
    }

    @Test void everyWordIsInTheLangFileAndNoPageIsLeftOver() {
        List<String> missing = new ArrayList<>();
        List<String> keys = new ArrayList<>(Handbook.FRAME_KEYS);
        keys.add("item.neroeconomy.traders_handbook");
        keys.add("item.neroeconomy.traders_handbook.tooltip");
        keys.add(HandbookGift.GIVEN);
        keys.add(HandbookGift.GIVEN_TO_VAULT);
        Set<String> texts = new HashSet<>();
        for (Handbook.Chapter chapter : Handbook.CHAPTERS_IN_ORDER) {
            keys.add(chapter.nameKey());
            keys.add(chapter.titleKey());
            for (int page = 0; page < chapter.pages(); page++) keys.add(chapter.pageKey(page));
            assertFalse(lang.has(chapter.pageKey(chapter.pages())), chapter.id() + " has words for a page it lacks");
        }
        for (String key : keys) {
            if (!lang.has(key)) missing.add(key);
            else if (key.matches(".*\\.\\d+$")) assertTrue(texts.add(lang.get(key).getAsString()), "a page twice: " + key);
        }
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
        // Every handbook key in the file is one the book shows.
        Set<String> known = new HashSet<>(keys);
        for (String key : lang.keySet())
            if (key.startsWith("gui.neroeconomy.handbook.")) assertTrue(known.contains(key), "unused: " + key);
    }

    @Test void everyDemoLinkTargetsAWalkthroughWithASampleView() {
        Set<HubTab> linked = new HashSet<>();
        for (Handbook.Chapter chapter : Handbook.CHAPTERS_IN_ORDER) {
            for (int page = 0; page < chapter.pages(); page++) {
                HubTab tab = chapter.demo(page);
                assertNotEquals(HubTab.ADMIN, tab, chapter.id() + " links the operators' tab");
                DemoScript script = DemoScript.of(tab);
                assertEquals(tab, script.tab());
                assertFalse(script.textOnly(), chapter.id() + " page " + (page + 1) + " links a words-only demo");
                assertTrue(script.steps().size() >= DemoScript.MIN_STEPS);
                linked.add(tab);
            }
        }
        // Every tab a player can use has a page that demonstrates it.
        for (HubTab tab : HubTab.values()) if (tab != HubTab.ADMIN) assertTrue(linked.contains(tab), tab + " is never linked");
    }

    @Test void theDataChapterSaysWhereToAsk() {
        Handbook.Chapter data = Handbook.chapter(Handbook.CHAPTERS_IN_ORDER.size() - 1);
        assertEquals("data", data.id());
        StringBuilder words = new StringBuilder();
        for (int page = 0; page < data.pages(); page++) words.append(lang.get(data.pageKey(page)).getAsString()).append(' ');
        String all = words.toString();
        assertTrue(all.contains("info@neroland.co.za"));
        assertTrue(all.contains("export") && all.contains("erase"));
    }
}
