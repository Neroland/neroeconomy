package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/** Every lang key the hub uses, including the ones it puts together, has English wording. */
class HubKeysTest {
    private static final String SOURCES = "common/src/main/java/za/co/neroland/neroeconomy";
    private static final Pattern LITERAL = Pattern.compile("\"((?:gui|command|container)\\.neroeconomy\\.[a-z0-9_.]+)\"");
    private static final Pattern BOX = Pattern.compile("\\.box\\([^,]+,\\s*\"([a-z0-9_.]+)\"");
    private static final Pattern MODE_BUTTON = Pattern.compile("modeButton\\([^,]+,\\s*\"([a-z0-9_.]+)\"");
    /** Keys the views extend with ".1" and ".2" for a two-line message. */
    private static final List<String> TWO_LINE = List.of("gui.neroeconomy.hub.orders.form.hint",
            "gui.neroeconomy.hub.orders.form.confirm_buy", "gui.neroeconomy.hub.orders.form.confirm_sell");

    private static JsonObject lang() throws IOException {
        try (InputStream stream = HubKeysTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            assumeTrue(stream != null, "en_us.json is not on this test classpath");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void assertAll(JsonObject lang, Iterable<String> keys) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) if (!lang.has(key)) missing.add(key);
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }

    @Test void everyKeyTheHubBuildsFromItsTabsQuotesAndPagesExists() throws IOException {
        JsonObject lang = lang();
        List<String> keys = new ArrayList<>(HubQuotes.KEYS);
        keys.addAll(HubPages.KEYS);
        for (HubTab tab : HubTab.values()) {
            keys.add(tab.key());
            keys.add(tab.tooltipKey());
            keys.add(tab.helpKey());
            if (!tab.built()) keys.add(HubPages.COMING_SOON + "." + tab.id());
        }
        for (String key : TWO_LINE) {
            keys.add(key + ".1");
            keys.add(key + ".2");
        }
        // The market's confirm button is labelled by mode, and its tooltip is the label key plus ".tooltip".
        for (String mode : List.of("buy", "sell", "place")) {
            keys.add("gui.neroeconomy.hub.market." + mode);
            keys.add("gui.neroeconomy.hub.market." + mode + ".tooltip");
        }
        // The F1b tabs: row titles, the "My data" lines and their tooltips, and the keys the views put together.
        keys.addAll(InvestPages.KEYS);
        keys.addAll(TradePages.KEYS);
        keys.addAll(AuctionsPages.KEYS);
        keys.addAll(ShopPages.KEYS);
        keys.add(HubJobs.SERVER_BADGE);
        for (String key : DataPages.KEYS) {
            keys.add(key);
            keys.add(key + ".tooltip");
        }
        for (String risk : List.of("steady", "risky", "index", "bond")) keys.add("invest.neroeconomy.risk." + risk + ".short");
        for (JobForm.Kind kind : JobForm.Kind.values())
            keys.add("gui.neroeconomy.hub.jobs.kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
        for (OfferForm.Source source : OfferForm.Source.values())
            keys.add("gui.neroeconomy.hub.trade.source." + source.name().toLowerCase(java.util.Locale.ROOT));
        // F3: the Admin tab's row keys, view buttons, counted actions and store sections.
        keys.addAll(AdminPages.KEYS);
        for (String view : List.of("supply", "sim", "commodities", "stocks", "houses", "check", "health", "player")) {
            keys.add("gui.neroeconomy.hub.admin.view." + view);
            keys.add("gui.neroeconomy.hub.admin.view." + view + ".tooltip");
        }
        for (za.co.neroland.neroeconomy.admin.AdminSection.Action action
                : za.co.neroland.neroeconomy.admin.AdminSection.Action.values()) keys.add(action.key());
        for (String section : AdminPages.SECTIONS) keys.add(AdminPages.SECTION_KEY + section);
        keys.add("message.neroeconomy.market_closed");
        for (String target : List.of("item", "entity", "player")) {
            keys.add("gui.neroeconomy.hub.jobs.target_" + target + ".hint");
            keys.add("gui.neroeconomy.hub.jobs.target_" + target + ".tooltip");
        }
        keys.addAll(List.of("container.neroeconomy.economy_hub", "block.neroeconomy.exchange_terminal",
                "block.neroeconomy.merchant_stall", "command.neroeconomy.hub.no_terminal",
                "command.neroeconomy.hub.unknown_tab"));
        assertAll(lang, keys);
    }

    private static Path sources() {
        Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (; at != null; at = at.getParent()) if (Files.isDirectory(at.resolve(SOURCES))) return at.resolve(SOURCES);
        return null;
    }

    @Test void everyKeyWrittenInTheHubSourcesExists() throws IOException {
        JsonObject lang = lang();
        Path root = sources();
        assumeTrue(root != null, "the sources are not reachable from the test's working directory");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> hub = Files.list(root.resolve("hub"))) { hub.forEach(files::add); }
        try (Stream<Path> client = Files.list(root.resolve("client"))) {
            client.filter(path -> path.getFileName().toString().matches("(Hub|EconomyHub).*\\.java")).forEach(files::add);
        }
        files.add(root.resolve("menu/EconomyHubMenu.java"));
        files.add(root.resolve("block/MerchantStallBlock.java"));
        files.add(root.resolve("block/ExchangeTerminalBlock.java"));
        TreeSet<String> keys = new TreeSet<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher literal = LITERAL.matcher(text);
            while (literal.find()) {
                String key = literal.group(1);
                if (key.endsWith(".") || TWO_LINE.contains(key)) continue;
                keys.add(key);
            }
            Matcher box = BOX.matcher(text);
            while (box.find()) {
                keys.add(box.group(1) + ".hint");
                keys.add(box.group(1) + ".tooltip");
            }
            Matcher mode = MODE_BUTTON.matcher(text);
            while (mode.find()) keys.add(mode.group(1) + ".tooltip");
        }
        assertTrue(keys.size() > 150, "the scan found too few keys: " + keys.size());
        assertAll(lang, keys);
    }
}
