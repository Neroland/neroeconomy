package za.co.neroland.neroeconomy.guard;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityParser;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;

/**
 * What the commodity and guard tests share: the vanilla recipes, item tags and item ids, read as
 * raw JSON straight from the Minecraft jar on the test classpath (the game is never started); the
 * data this mod ships, read from the very files it ships; and the frozen picture of the sibling
 * mods under {@code common/src/test/resources/guard}.
 */
public final class GuardTesting {
    /** The id of every commodity file that ships, in id order. */
    public static final List<String> SHIPPED_FILES = List.of("neroeconomy:building", "neroeconomy:c_gems_and_dusts",
            "neroeconomy:c_ingots", "neroeconomy:compat/neroagriculture", "neroeconomy:compat/nerocolonies",
            "neroeconomy:compat/nerocreatures", "neroeconomy:compat/nerodecor", "neroeconomy:compat/nerologistics",
            "neroeconomy:compat/neronotes", "neroeconomy:compat/neropower", "neroeconomy:compat/nerospace",
            "neroeconomy:compat/nerotech", "neroeconomy:farming", "neroeconomy:metals_and_gems",
            "neroeconomy:mob_drops");
    /** The id of every guard data file that ships. */
    public static final List<String> SHIPPED_GUARD_FILES = List.of("neroeconomy:nerotech");
    /** This mod's own recipes. */
    public static final List<String> OWN_RECIPES = List.of("auto_trader", "bank_terminal", "credit_chip", "market_board",
            "player_shop", "trading_terminal");
    public static final String CREDIT_CHIP = "neroeconomy:credit_chip";

    private static final String MAIN = "common/src/main/resources/";
    private static final String TEST = "common/src/test/resources/";
    private static final String RECIPES = "data/minecraft/recipe/";
    private static final String ITEM_TAGS = "data/minecraft/tags/item/";
    private static final String ITEM_MODELS = "assets/minecraft/items/";
    private static final String PROBE = ITEM_TAGS + "planks.json";

    private static Map<String, JsonElement> vanillaRecipes;
    private static Map<String, Set<String>> vanillaTags;
    private static Set<String> vanillaItems;
    private static boolean vanillaItemsExact;
    private static JsonObject siblingWorld;

    private GuardTesting() { }

    // --- the Minecraft jar -----------------------------------------------------------------------

    /** Every vanilla recipe file, by recipe id. Fails when the Minecraft data cannot be found. */
    public static synchronized Map<String, JsonElement> vanillaRecipes() {
        if (vanillaRecipes == null) load();
        return vanillaRecipes;
    }

    /** Every vanilla item tag, by tag id without {@code #}, with nested tags resolved to item ids. */
    public static synchronized Map<String, Set<String>> vanillaTags() {
        if (vanillaTags == null) load();
        return vanillaTags;
    }

    /**
     * Every vanilla item id: one per item model definition where the jar carries the client's
     * assets, otherwise every id a recipe or an item tag mentions.
     */
    public static synchronized Set<String> vanillaItems() {
        if (vanillaItems == null) load();
        return vanillaItems;
    }

    /**
     * Whether a vanilla item id exists. Exact where the jar carries the item model definitions;
     * on a classpath without the client's assets an id that no recipe or tag mentions cannot be
     * told from a typing mistake, and is taken to exist.
     */
    public static synchronized boolean vanillaItemKnown(String item) {
        if (vanillaItems == null) load();
        return vanillaItems.contains(item) || (!vanillaItemsExact && item.startsWith("minecraft:"));
    }

    private static void load() {
        Map<String, String> files;
        try {
            files = minecraftFiles();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the Minecraft data on the test classpath", failure);
        }
        Map<String, JsonElement> recipes = new TreeMap<>();
        Map<String, List<String>> rawTags = new TreeMap<>();
        Set<String> items = new TreeSet<>();
        Set<String> mentioned = new TreeSet<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String path = file.getKey();
            if (path.startsWith(RECIPES)) {
                JsonElement json = JsonParser.parseString(file.getValue());
                recipes.put("minecraft:" + path.substring(RECIPES.length(), path.length() - 5), json);
                GuardRecipe read = RecipeReader.read("probe", json);
                if (read != null) {
                    mentioned.add(read.result());
                    for (GuardRecipe.Input input : read.inputs())
                        for (String option : input.options()) if (option.charAt(0) != '#') mentioned.add(option);
                }
            } else if (path.startsWith(ITEM_TAGS)) {
                List<String> values = new ArrayList<>();
                for (JsonElement value : JsonParser.parseString(file.getValue()).getAsJsonObject().getAsJsonArray("values"))
                    values.add(value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString());
                rawTags.put("minecraft:" + path.substring(ITEM_TAGS.length(), path.length() - 5), values);
            } else if (path.startsWith(ITEM_MODELS)) {
                String name = path.substring(ITEM_MODELS.length(), path.length() - 5);
                if (name.indexOf('/') < 0) items.add("minecraft:" + name);
            }
        }
        if (recipes.size() < 1000 || rawTags.size() < 100)
            throw new IllegalStateException("The Minecraft data on the test classpath is incomplete: " + recipes.size()
                    + " recipes, " + rawTags.size() + " item tags");
        Map<String, Set<String>> tags = new TreeMap<>();
        for (String tag : rawTags.keySet()) tags.put(tag, resolve(tag, rawTags, new TreeSet<>()));
        vanillaItemsExact = !items.isEmpty();
        if (!vanillaItemsExact) {
            items.addAll(mentioned);
            for (Set<String> members : tags.values()) items.addAll(members);
        }
        vanillaRecipes = Map.copyOf(recipes);
        vanillaTags = Map.copyOf(tags);
        vanillaItems = Set.copyOf(items);
    }

    private static Set<String> resolve(String tag, Map<String, List<String>> raw, Set<String> visiting) {
        Set<String> out = new TreeSet<>();
        if (!visiting.add(tag)) return out;
        for (String value : raw.getOrDefault(tag, List.of())) {
            if (value.charAt(0) == '#') out.addAll(resolve(value.substring(1), raw, visiting));
            else out.add(value);
        }
        visiting.remove(tag);
        return out;
    }

    /**
     * The recipe, item tag and item model files of the Minecraft jar, by path. The jar is found
     * through a resource every version has; when the data is not reachable as a classpath
     * resource it is read from the jar a Minecraft class was loaded from.
     */
    private static Map<String, String> minecraftFiles() throws IOException {
        ClassLoader loader = GuardTesting.class.getClassLoader();
        List<Path> roots = new ArrayList<>();
        Enumeration<URL> found = loader.getResources(PROBE);
        while (found.hasMoreElements()) {
            URL url = found.nextElement();
            if ("jar".equals(url.getProtocol())) {
                roots.add(Path.of(URI.create(((JarURLConnection) url.openConnection()).getJarFileURL().toString())));
            } else if ("file".equals(url.getProtocol())) {
                Path root = Path.of(URI.create(url.toString()));
                for (int up = 0; up < PROBE.split("/").length; up++) root = root.getParent();
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            try {
                CodeSource source = Class.forName("net.minecraft.resources.Identifier", false, loader)
                        .getProtectionDomain().getCodeSource();
                if (source != null && source.getLocation() != null) roots.add(Path.of(source.getLocation().toURI()));
            } catch (ReflectiveOperationException | java.net.URISyntaxException | LinkageError missing) {
                throw new IOException("no Minecraft jar on the test classpath", missing);
            }
        }
        // More than one jar can carry a file under data/minecraft; the game's own is the one with
        // the recipes in it.
        Map<String, String> best = Map.of();
        long bestRecipes = -1L;
        for (Path root : roots) {
            Map<String, String> files = filesOf(root);
            long recipes = files.keySet().stream().filter(path -> path.startsWith(RECIPES)).count();
            if (recipes > bestRecipes) {
                best = files;
                bestRecipes = recipes;
            }
        }
        return best;
    }

    private static Map<String, String> filesOf(Path root) throws IOException {
        Map<String, String> files = new HashMap<>();
        {
            // A folder of resources (a development run) or a jar.
            if (Files.isDirectory(root)) {
                for (String directory : List.of(RECIPES, ITEM_TAGS, ITEM_MODELS)) {
                    Path start = root.resolve(directory);
                    if (!Files.isDirectory(start)) continue;
                    try (Stream<Path> walk = Files.walk(start)) {
                        for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                            String path = root.relativize(file).toString().replace('\\', '/');
                            if (path.endsWith(".json")) files.putIfAbsent(path, Files.readString(file, StandardCharsets.UTF_8));
                        }
                    }
                }
            } else try (JarFile jar = new JarFile(root.toFile())) {
                for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                    JarEntry entry = entries.nextElement();
                    String path = entry.getName();
                    if (entry.isDirectory() || !path.endsWith(".json")) continue;
                    if (!path.startsWith(RECIPES) && !path.startsWith(ITEM_TAGS) && !path.startsWith(ITEM_MODELS)) continue;
                    try (InputStream stream = jar.getInputStream(entry)) {
                        files.putIfAbsent(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return files;
    }

    // --- what this mod ships ---------------------------------------------------------------------

    /** The JSON of every shipped commodity file, by file id. Fails when a file cannot be found. */
    public static Map<String, JsonElement> shippedCommodityFiles() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (String id : SHIPPED_FILES)
            files.put(id, read(MAIN, "data/neroeconomy/neroeconomy/commodities/" + id.substring(id.indexOf(':') + 1) + ".json"));
        return files;
    }

    /** The shipped guard data: Nerotech's code-side bonus and its filter loop. */
    public static GuardBonuses shippedBonuses() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (String id : SHIPPED_GUARD_FILES)
            files.put(id, read(MAIN, "data/neroeconomy/neroeconomy/guard/" + id.substring(id.indexOf(':') + 1) + ".json"));
        return GuardBonuses.parse(files);
    }

    /** This mod's own recipes, the Credit Chip's among them, by recipe id. */
    public static Map<String, JsonElement> ownRecipes() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (String name : OWN_RECIPES) files.put("neroeconomy:" + name, read(MAIN, "data/neroeconomy/recipe/" + name + ".json"));
        return files;
    }

    /** The shipped {@code neroeconomy:never_commodity} tag, with vanilla tags in it resolved. */
    public static Set<String> neverCommodity() {
        Set<String> out = new TreeSet<>();
        for (JsonElement value : read(MAIN, "data/neroeconomy/tags/item/never_commodity.json").getAsJsonObject()
                .getAsJsonArray("values")) {
            String id = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
            if (id.charAt(0) == '#') out.addAll(vanillaTags().getOrDefault(id.substring(1), Set.of()));
            else out.add(id);
        }
        return out;
    }

    /** The shipped English lang file. */
    public static JsonObject lang() { return read(MAIN, "assets/neroeconomy/lang/en_us.json").getAsJsonObject(); }

    // --- the frozen sibling mods -----------------------------------------------------------------

    /** A frozen sample of Nerotech's recipes: its five machine shapes and the crafting around them. */
    public static Map<String, JsonElement> nerotechRecipes() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : read(TEST, "guard/nerotech_recipes.json").getAsJsonObject().entrySet())
            files.put(entry.getKey(), entry.getValue());
        return files;
    }

    private static synchronized JsonObject siblingWorld() {
        if (siblingWorld == null) siblingWorld = read(TEST, "guard/sibling_world.json").getAsJsonObject();
        return siblingWorld;
    }

    /** Every item tag the tests know: the vanilla ones, and the frozen convention and sibling tags. */
    public static Map<String, Set<String>> allTags() {
        Map<String, Set<String>> tags = new TreeMap<>(vanillaTags());
        for (Map.Entry<String, JsonElement> tag : siblingWorld().getAsJsonObject("tags").entrySet()) {
            Set<String> members = new TreeSet<>();
            for (JsonElement member : tag.getValue().getAsJsonArray()) members.add(member.getAsString());
            tags.put(tag.getKey(), members);
        }
        return tags;
    }

    /** The world with every sibling mod installed: the worst case for the guard. */
    public static CommodityCatalog fullWorld() {
        Set<String> mods = new TreeSet<>();
        for (JsonElement mod : siblingWorld().getAsJsonArray("mods")) mods.add(mod.getAsString());
        Set<String> items = new TreeSet<>(vanillaItems());
        for (JsonElement item : siblingWorld().getAsJsonArray("items")) items.add(item.getAsString());
        return catalog(mods, items, allTags());
    }

    /** The world with no other mod installed: vanilla items and vanilla tags only. */
    public static CommodityCatalog vanillaWorld() { return catalog(Set.of(), vanillaItems(), vanillaTags()); }

    private static CommodityCatalog catalog(Set<String> mods, Set<String> items, Map<String, Set<String>> tags) {
        Set<String> never = neverCommodity();
        return new CommodityCatalog() {
            @Override public boolean modLoaded(String modId) { return mods.contains(modId); }
            @Override public boolean itemKnown(String item) { return items.contains(item) || vanillaItemKnown(item); }
            @Override public Collection<String> tagItems(String tag) {
                Set<String> members = new TreeSet<>(tags.getOrDefault(tag, Set.of()));
                members.removeIf(member -> !itemKnown(member));
                return members;
            }
            @Override public String refusal(String item) {
                if (CREDIT_CHIP.equals(item)) return CommodityCatalog.CREDIT_CHIP;
                return never.contains(item) ? NEVER_COMMODITY : null;
            }
        };
    }

    /** The shipped commodity files, resolved against {@code world} exactly as the game does it. */
    public static CommodityRegistry shipped(CommodityCatalog world) {
        return CommodityParser.registry(shippedCommodityFiles(), CommodityParser.DEFAULT_SPREAD_PERCENT, world);
    }

    /** Every recipe the shipped-data check runs over: vanilla, this mod's own, and the Nerotech sample. */
    public static RecipeReader.Read allRecipes() {
        Map<String, JsonElement> files = new HashMap<>(vanillaRecipes());
        files.putAll(ownRecipes());
        files.putAll(nerotechRecipes());
        return RecipeReader.readAll(files);
    }

    /** A tag lookup over {@link #allTags()}. */
    public static ArbitrageGuard.Tags tags() {
        Map<String, Set<String>> tags = allTags();
        return tag -> tags.getOrDefault(tag, Set.of());
    }

    // --- files -----------------------------------------------------------------------------------

    /** From the test classpath when the file is on it, otherwise from the source tree. Fails when it is in neither. */
    public static JsonElement read(String sourceRoot, String path) {
        try {
            try (InputStream stream = GuardTesting.class.getResourceAsStream("/" + path)) {
                if (stream != null) try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader);
                }
            }
            Path file = sourceFile(sourceRoot + path);
            if (file != null) return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + path, failure);
        }
        throw new IllegalStateException("Not on the test classpath and not in the source tree: " + path);
    }

    /** The file or directory at {@code relative} under the repository root, or null when it cannot be found. */
    public static Path sourceFile(String relative) {
        for (Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath(); at != null; at = at.getParent()) {
            Path file = at.resolve(relative);
            if (Files.exists(file)) return file;
        }
        return null;
    }
}
