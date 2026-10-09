package za.co.neroland.neroeconomy.client.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.text.SourceTree;

/**
 * The "Show me" walkthroughs: every tab has one of the right length on its own sample pages, every
 * place a step points at, presses or types into is laid out by that tab's view, every word is in
 * the lang file, and nothing a demo runs can reach the server — checked in the sources and in the
 * compiled classes of the demo package, the demo runner, the tab views and the Trader's Handbook,
 * whose Open demo runs a walkthrough with no hub on the server at all.
 */
class DemoScriptTest {
    private static final int BODY_W = 256;
    private static final int BODY_TOP = 16;
    private static final int BODY_BOTTOM = 224;
    private static final String PLACEHOLDER = "Help for the";
    /** What code that can reach the server names, in source or in a compiled class. */
    private static final List<String> SERVERBOUND = List.of("NeroEconomyNetwork", "sendToServer", "Payload",
            "za/co/neroland/neroeconomy/network/", "za.co.neroland.neroeconomy.network", "net/minecraft/network/protocol",
            "net.minecraft.network.protocol", "sendCommand", "sendChat", "sendUnsignedCommand");
    private static final Map<HubTab, String> VIEWS = Map.of(HubTab.WALLET, "HubWalletView",
            HubTab.VAULT, "HubVaultView", HubTab.MARKET, "HubMarketView", HubTab.ORDERS, "HubOrdersView",
            HubTab.INVEST, "HubInvestView", HubTab.TRADE, "HubTradeView", HubTab.JOBS, "HubJobsView",
            HubTab.AUCTIONS, "HubAuctionsView", HubTab.SHOP, "HubShopView", HubTab.ADMIN, "HubAdminView");

    private static Path java;
    private static Path client;
    private static JsonObject lang;

    @BeforeAll static void read() throws IOException {
        Path main = SourceTree.main();
        java = main.resolve("java");
        client = main.resolve("java/za/co/neroland/neroeconomy/client");
        lang = JsonParser.parseString(Files.readString(main.resolve("resources/assets/neroeconomy/lang/en_us.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test void everyTabHasAWalkthroughOfFourToEightStepsOnItsOwnSamplePages() {
        for (HubTab tab : HubTab.values()) {
            DemoScript script = DemoScript.of(tab);
            assertEquals(tab, script.tab());
            int steps = script.steps().size();
            assertTrue(steps >= DemoScript.MIN_STEPS && steps <= DemoScript.MAX_STEPS, tab + " has " + steps + " steps");
            if (tab == HubTab.ADMIN) {
                assertTrue(script.textOnly(), "the Admin walkthrough is words only");
                for (DemoStep step : script.steps()) {
                    assertNull(step.target());
                    assertTrue(step.actions().isEmpty());
                }
                continue;
            }
            assertFalse(script.textOnly(), tab + " shows a sample view");
            for (HubPage page : script.pages()) assertEquals(tab, page.tab(), tab + " has a page of another tab");
            for (DemoStep step : script.steps()) {
                assertNotNull(step.target(), tab + ": every step points at something");
                assertTrue(step.page() >= -1 && step.page() < script.pages().size(), tab + ": page " + step.page());
            }
        }
    }

    @Test void everyTargetIsLaidOutByItsTabsView() throws IOException {
        int widgets = 0;
        for (HubTab tab : HubTab.values()) {
            DemoScript script = DemoScript.of(tab);
            if (script.textOnly()) continue;
            String source = Files.readString(client.resolve(VIEWS.get(tab) + ".java"), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
            for (DemoStep step : script.steps()) {
                List<DemoTarget> targets = new ArrayList<>();
                targets.add(step.target());
                for (DemoAction action : step.actions()) targets.add(action.target());
                for (DemoTarget target : targets) {
                    String where = tab + " " + target;
                    assertTrue(target.x() >= 0 && target.x() + target.w() <= BODY_W && target.y() >= BODY_TOP
                            && target.y() + target.h() <= BODY_BOTTOM, where + " is outside the body");
                    if (!target.widget()) continue;
                    widgets++;
                    // button(..., x, y, w, h, ...) or box(previous, key, x, y, w, maxLength): 14 high.
                    assertTrue(target.h() == 14 && source.contains(target.x() + ", " + target.y() + ", " + target.w()
                            + ", "), where + " is not a button or box the view lays out");
                }
            }
        }
        assertTrue(widgets >= 30, "too few widget targets: " + widgets);
    }

    @Test void actionsTypeAndPressOnlyWidgetsAndTypeLangOrDigits() {
        for (HubTab tab : HubTab.values()) {
            for (DemoStep step : DemoScript.of(tab).steps()) {
                for (DemoAction action : step.actions()) {
                    switch (action.kind()) {
                        case TYPE -> {
                            assertTrue(action.target().widget(), tab + " types into a widget");
                            assertTrue(action.text().matches("\\d{1,6}"), tab + " types digits only: " + action.text());
                        }
                        case TYPE_TEXT -> {
                            assertTrue(action.target().widget(), tab + " types into a widget");
                            assertTrue(lang.has(action.text()), tab + " types a lang key: " + action.text());
                        }
                        case PRESS -> assertTrue(action.target().widget(), tab + " presses a widget");
                        case CLICK -> { }
                    }
                }
            }
        }
    }

    @Test void everyWordOfEveryWalkthroughIsInTheLangFile() {
        List<String> missing = new ArrayList<>();
        List<String> keys = new ArrayList<>(DemoScript.FRAME_KEYS);
        keys.addAll(DemoPages.KEYS);
        for (HubTab tab : HubTab.values()) {
            keys.add(tab.helpKey());
            DemoScript script = DemoScript.of(tab);
            for (int index = 0; index < script.steps().size(); index++) keys.add(script.stepKey(index));
            keys.addAll(script.sampleNames());
            assertFalse(lang.has(DemoScript.stepKey(tab, script.steps().size())), tab + " has words for a step it lacks");
        }
        for (String key : keys) if (!lang.has(key)) missing.add(key);
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
        for (HubTab tab : HubTab.values())
            assertFalse(lang.get(tab.helpKey()).getAsString().startsWith(PLACEHOLDER), tab + " still has the placeholder");
    }

    @Test void theDemoSenderSendsNothingAndIsNotTheLiveOne() throws IOException {
        DemoIntents.NONE.send(null, null, 0L, 0L, "");
        String demo = Files.readString(client.resolve("HubDemo.java"), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String hub = Files.readString(client.resolve("EconomyHubScreen.java"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        assertTrue(demo.contains("newView(this.script.tab(), DemoIntents.NONE,"), "demo views get the demo sender");
        assertFalse(Pattern.compile("\\blive\\b").matcher(demo).find(), "the demo never names the live sender");
        assertTrue(hub.contains("private void send(HubAction action"), "only the hub itself can send");
        assertTrue(hub.contains("private final HubIntents live = this::send;"), "the live sender is the hub's own");
        Matcher sends = Pattern.compile("(?<![\\w.])send\\(").matcher(hub);
        int count = 0;
        while (sends.find()) count++;
        assertEquals(1, count, "the hub calls its own send only through its live sender");
    }

    @Test void theHandbookRunsDemosWithoutAHubAndThoseCannotSend() throws IOException {
        String book = Files.readString(client.resolve("handbook/HandbookScreen.java"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        String hub = Files.readString(client.resolve("EconomyHubScreen.java"), StandardCharsets.UTF_8)
                .replaceAll("//[^\n]*", "").replaceAll("\\s+", " ");
        assertTrue(book.contains("EconomyHubScreen.demoOnly("), "the handbook opens the demo-only hub");
        assertFalse(book.contains("new EconomyHubScreen("), "the handbook never makes a live hub");
        assertTrue(hub.contains("new EconomyHubMenu(DEMO_ONLY_CONTAINER, inventory)"), "a client-made menu");
        assertTrue(hub.contains("private static final int DEMO_ONLY_CONTAINER = -1;"), "an id the server never opens");
        assertTrue(hub.contains("String text) { if (this.leave != null) return; NeroEconomyNetwork.sendToServer("),
                "a demo-only hub refuses to send before anything else");
        assertTrue(hub.contains("HubPage update = this.leave != null ? null : ClientHubPage.poll("),
                "a demo-only hub reads no page");
        assertTrue(hub.contains("public void onClose() { if (this.leave != null) { this.leave.run(); return; }"),
                "leaving a demo-only hub closes no container");
    }

    /** The sources the demo runs: its package, its runner, every tab view and their base, and the handbook. */
    private static List<Path> demoSources() throws IOException {
        List<Path> files = new ArrayList<>(SourceTree.files(client.resolve("demo"), path -> path.toString().endsWith(".java")));
        files.addAll(SourceTree.files(client, path -> {
            String name = path.getFileName().toString();
            return path.getParent().equals(client) && (name.equals("HubDemo.java") || name.equals("HubIntents.java")
                    || name.matches("Hub\\w*View\\.java"));
        }));
        files.addAll(SourceTree.files(client.resolve("handbook"), path -> path.toString().endsWith(".java")));
        files.addAll(SourceTree.files(java.resolve("za/co/neroland/neroeconomy/handbook"),
                path -> path.toString().endsWith(".java")));
        return files;
    }

    @Test void noDemoSourceNamesAnythingServerbound() throws IOException {
        List<Path> files = demoSources();
        assertTrue(files.size() >= 22, "sources found: " + files.size());
        assertTrue(files.stream().anyMatch(file -> file.endsWith("HandbookScreen.java")), "the handbook is scanned");
        List<String> found = new ArrayList<>();
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String banned : SERVERBOUND) if (source.contains(banned)) found.add(file.getFileName() + ": " + banned);
        }
        assertTrue(found.isEmpty(), "demo code that can reach the server: " + found);
    }

    @Test void noCompiledDemoClassReferencesAnythingServerbound() throws IOException {
        List<String> classes = new ArrayList<>();
        Pattern nested = Pattern.compile("(?:class|enum|record|interface) (\\w+)");
        for (Path file : demoSources()) {
            String outer = file.getFileName().toString().replace(".java", "");
            String pkg = java.relativize(file.getParent()).toString().replace('\\', '/') + "/";
            Matcher inner = nested.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (inner.find())
                classes.add(pkg + (inner.group(1).equals(outer) ? outer : outer + "$" + inner.group(1)) + ".class");
        }
        List<String> found = new ArrayList<>();
        int read = 0;
        ClassLoader loader = DemoScript.class.getClassLoader();
        for (String name : classes) {
            try (InputStream in = loader.getResourceAsStream(name)) {
                if (in == null) continue;
                read++;
                String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String banned : SERVERBOUND) if (bytes.contains(banned)) found.add(name + ": " + banned);
            }
        }
        assertTrue(read >= 30, "compiled classes read: " + read + " of " + classes);
        assertTrue(classes.contains("za/co/neroland/neroeconomy/client/handbook/HandbookScreen.class"), "handbook class");
        assertTrue(found.isEmpty(), "compiled demo code that can reach the server: " + found);
    }
}
