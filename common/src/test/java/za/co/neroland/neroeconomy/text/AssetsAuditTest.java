package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The mod's assets and data, read from disk: every registered block has a blockstate, a model, an
 * item definition, a loot table, a recipe, a pickaxe tag entry and an English name; every registered
 * item has an item definition, a recipe and an English name; every model a blockstate or an item
 * definition names exists, and every texture a model names exists; every texture is a real one (no
 * magenta missing-texture pixels, more than two colours, an animated strip has its .mcmeta and whole
 * frames); and no texture is left that nothing uses.
 */
class AssetsAuditTest {
    private static final Pattern REGISTERED = Pattern.compile("\\.register\\(\"([a-z0-9_]+)\"");
    private static final Pattern GUI_TEXTURE = Pattern.compile("\"textures/gui/([a-z0-9_]+)\\.png\"");

    private static Path assets;
    private static Path data;
    private static List<String> blocks;
    private static List<String> items;
    private static String javaSources;
    private static JsonObject lang;

    @BeforeAll static void read() throws IOException {
        Path main = SourceTree.main();
        assets = main.resolve("resources/assets/neroeconomy");
        data = main.resolve("resources/data");
        Path registry = main.resolve("java/za/co/neroland/neroeconomy/registry");
        blocks = registered(registry.resolve("ModBlocks.java"));
        items = registered(registry.resolve("ModItems.java"));
        StringBuilder all = new StringBuilder();
        for (Path file : SourceTree.files(main.resolve("java"), path -> path.toString().endsWith(".java")))
            all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
        javaSources = all.toString();
        lang = json(assets.resolve("lang/en_us.json")).getAsJsonObject();
    }

    @Test void everyBlockAndItemHasItsFiles() throws IOException {
        List<String> missing = new ArrayList<>();
        assertTrue(blocks.size() >= 7 && items.size() >= 8, "registries not read: " + blocks + " " + items);
        Set<String> pickaxe = new HashSet<>();
        for (JsonElement value : json(data.resolve("minecraft/tags/block/mineable/pickaxe.json")).getAsJsonObject()
                .getAsJsonArray("values")) pickaxe.add(value.getAsString());
        for (String block : blocks) {
            need(missing, assets.resolve("blockstates/" + block + ".json"));
            need(missing, data.resolve("neroeconomy/loot_table/blocks/" + block + ".json"));
            if (!items.contains(block)) missing.add("no block item for " + block);
            if (!pickaxe.contains("neroeconomy:" + block)) missing.add("not mineable with a pickaxe: " + block);
            if (!lang.has("block.neroeconomy." + block)) missing.add("no English name: block." + block);
            if (Files.isRegularFile(data.resolve("neroeconomy/loot_table/blocks/" + block + ".json"))
                    && !Files.readString(data.resolve("neroeconomy/loot_table/blocks/" + block + ".json"))
                            .contains("\"neroeconomy:" + block + "\"")) missing.add("loot table drops something else: " + block);
        }
        for (String item : items) {
            need(missing, assets.resolve("items/" + item + ".json"));
            Path recipe = data.resolve("neroeconomy/recipe/" + item + ".json");
            need(missing, recipe);
            if (Files.isRegularFile(recipe) && !Files.readString(recipe).contains("\"neroeconomy:" + item + "\""))
                missing.add("recipe makes something else: " + item);
            if (!blocks.contains(item) && !lang.has("item.neroeconomy." + item)) missing.add("no English name: item." + item);
        }
        assertTrue(missing.isEmpty(), "missing: " + missing);
    }

    @Test void everyModelAndTextureNamedExists() throws IOException {
        List<String> missing = new ArrayList<>();
        Set<String> models = new LinkedHashSet<>();
        for (Path file : SourceTree.files(assets.resolve("blockstates"), path -> path.toString().endsWith(".json")))
            collect(json(file), "model", models);
        for (Path file : SourceTree.files(assets.resolve("items"), path -> path.toString().endsWith(".json")))
            collect(json(file), "model", models);
        Set<String> textures = new LinkedHashSet<>();
        for (String model : models) {
            if (!model.startsWith("neroeconomy:")) continue;
            Path file = assets.resolve("models/" + model.substring("neroeconomy:".length()) + ".json");
            if (!need(missing, file)) continue;
            JsonObject json = json(file).getAsJsonObject();
            if (json.has("textures"))
                for (Map.Entry<String, JsonElement> texture : json.getAsJsonObject("textures").entrySet()) {
                    String value = texture.getValue().getAsString();
                    if (value.startsWith("neroeconomy:")) textures.add(value.substring("neroeconomy:".length()));
                }
        }
        for (String texture : textures) need(missing, assets.resolve("textures/" + texture + ".png"));
        // Nothing left over: every block and item texture is named by a model, every GUI sheet by a screen.
        Set<String> guiSheets = new HashSet<>();
        Matcher gui = GUI_TEXTURE.matcher(javaSources);
        while (gui.find()) guiSheets.add("gui/" + gui.group(1));
        for (Path png : SourceTree.files(assets.resolve("textures"), path -> path.toString().endsWith(".png"))) {
            String name = assets.resolve("textures").relativize(png).toString().replace('\\', '/');
            name = name.substring(0, name.length() - ".png".length());
            if (!textures.contains(name) && !guiSheets.contains(name)) missing.add("unused texture " + name);
        }
        assertTrue(missing.isEmpty(), "missing or unused: " + missing);
        assertTrue(textures.size() >= 20, "models not read: " + textures);
    }

    @Test void everyTextureIsARealOne() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path png : SourceTree.files(assets.resolve("textures"), path -> path.toString().endsWith(".png"))) {
            BufferedImage image = ImageIO.read(png.toFile());
            String name = png.getFileName().toString();
            if (image == null) { bad.add(name + " unreadable"); continue; }
            Set<Integer> colours = new HashSet<>();
            int magenta = 0;
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    colours.add(argb);
                    int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    if (a > 0 && r >= 200 && g <= 60 && b >= 200) magenta++;
                }
            if (magenta > 0) bad.add(name + " has " + magenta + " missing-texture magenta pixels");
            if (colours.size() <= 2) bad.add(name + " is flat (" + colours.size() + " colours)");
            boolean strip = image.getHeight() > image.getWidth();
            Path meta = png.resolveSibling(name + ".mcmeta");
            if (strip && (!Files.isRegularFile(meta) || image.getHeight() % image.getWidth() != 0))
                bad.add(name + " is a strip without an .mcmeta or whole frames");
            if (Files.isRegularFile(meta) && !json(meta).getAsJsonObject().has("animation"))
                bad.add(name + ".mcmeta has no animation");
        }
        assertTrue(bad.isEmpty(), "placeholder or broken textures: " + bad);
    }

    private static boolean need(List<String> missing, Path file) {
        if (Files.isRegularFile(file)) return true;
        missing.add(file.getFileName().toString() + " (" + file.getParent().getFileName() + ")");
        return false;
    }

    private static void collect(JsonElement element, String field, Set<String> into) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals(field) && entry.getValue().isJsonPrimitive()) into.add(entry.getValue().getAsString());
                else collect(entry.getValue(), field, into);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : (JsonArray) element) collect(child, field, into);
        }
    }

    private static List<String> registered(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        Matcher matcher = REGISTERED.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    private static JsonElement json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
    }
}
