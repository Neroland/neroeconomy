package za.co.neroland.neroeconomy.exchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.simulation.SimRandom;

/**
 * What the exchange tests share: a few made-up companies, the twelve that ship (read from the very
 * files the mod ships), and the random numbers the game's handler would draw for a given secret.
 */
public final class ExchangeTesting {
    /** Ten minutes: the default step. */
    public static final long STEP = 600_000L;
    public static final int STEPS_PER_DAY = 144;
    public static final int STEPS_PER_WEEK = 1008;
    /** A step index that is the first of an economy-day. */
    public static final long DAY_START = 144L * 20_000L;

    /** The ids of the companies that ship, in id order. */
    public static final List<String> SHIPPED = List.of("neroeconomy:bloomspice_traders",
            "neroeconomy:deepcore_mining", "neroeconomy:ductway_freight", "neroeconomy:farside_salvage",
            "neroeconomy:glimmerstone_prospecting", "neroeconomy:greenfield_farms", "neroeconomy:kindlewick_power",
            "neroeconomy:lanternleaf_decor", "neroeconomy:redpulse_dynamics", "neroeconomy:settlers_row_supply",
            "neroeconomy:skylantern_launchworks", "neroeconomy:wildwhisker_menagerie");

    private static final String RESOURCE_DIRECTORY = "data/neroeconomy/neroeconomy/stocks/";

    private ExchangeTesting() { }

    /** A steady company: 100 credits, 2 % a day, 15 % a week. */
    public static StockDefinition steady(String path, String... linked) {
        return new StockDefinition("test:" + path, "stock.test." + path, "stock.test." + path + ".description", "mining",
                StockType.STEADY, 100L, 2.0D, 15.0D, 1.5D, List.of(linked), true);
    }

    /** A risky company: 200 credits, 12 % a day, 19 % a week. */
    public static StockDefinition risky(String path, String... linked) {
        return new StockDefinition("test:" + path, "stock.test." + path, "stock.test." + path + ".description", "space",
                StockType.RISKY, 200L, 12.0D, 19.0D, 0.0D, List.of(linked), true);
    }

    /** A secret that is different for every seed. */
    public static byte[] secret(long seed) {
        byte[] secret = new byte[32];
        new Random(seed).nextBytes(secret);
        return secret;
    }

    /** The numbers the game's handler draws for {@code secret} at {@code step}; no volume, no shock. */
    public static ExchangeSection.Inputs inputs(byte[] secret, long step) {
        return stockId -> PriceModel.gaussian(SimRandom.of(secret, ExchangeStep.NAME, step,
                ExchangeStep.PRICE_STREAM + stockId));
    }

    /** The same random number for every company on every step. */
    public static ExchangeSection.Inputs constant(double z) { return stockId -> z; }

    /** Runs {@code count} steps from {@code first} with the handler's own random numbers. */
    public static void run(ExchangeSection section, StockRegistry registry, ExchangeSettings settings, byte[] secret,
            long first, int count) {
        for (long step = first; step < first + count; step++)
            section.step(registry, settings, step, STEP, inputs(secret, step));
    }

    // --- the shipped data ------------------------------------------------------------------------

    /** The JSON of every shipped stock file, by company id. Fails when a file cannot be found. */
    public static Map<String, JsonElement> shippedFiles() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (String id : SHIPPED) {
            String name = id.substring(id.indexOf(':') + 1) + ".json";
            try {
                files.put(id, read(name));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read the shipped stock file " + name, failure);
            }
        }
        return files;
    }

    /** The twelve shipped companies, parsed exactly as the game parses them. */
    public static StockRegistry shipped() {
        List<String> problems = new ArrayList<>();
        StockRegistry registry = StockParser.registry(shippedFiles(), (id, problem) -> problems.add(id + " " + problem));
        if (!problems.isEmpty()) throw new IllegalStateException("Shipped stock files were refused: " + problems);
        return registry;
    }

    /** From the test classpath when the resources are on it, otherwise from the source tree. */
    private static JsonElement read(String name) throws IOException {
        try (InputStream stream = ExchangeTesting.class.getResourceAsStream("/" + RESOURCE_DIRECTORY + name)) {
            if (stream != null) try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        }
        for (Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath(); at != null; at = at.getParent()) {
            Path file = at.resolve("common/src/main/resources/" + RESOURCE_DIRECTORY + name);
            if (Files.isRegularFile(file)) return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        }
        throw new IOException("not found: " + name);
    }
}
