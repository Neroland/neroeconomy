package za.co.neroland.neroeconomy.link;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The words the companion app shows, read from this mod's own English lang file.
 *
 * <p>The app shows a message exactly as it arrives, and a server has no client language table to
 * translate a key with: a dedicated server only knows vanilla's English. So every sentence the app
 * module sends is a lang key resolved here against {@code assets/neroeconomy/lang/en_us.json} on the
 * mod's own class path. The file is read once; a key it does not hold comes back as the key itself,
 * which a unit test makes sure never happens for a key the module uses.
 *
 * <p>Arguments are only ever numbers the player's own action produced; nothing about another
 * player is ever formatted into a sentence.
 */
public final class LinkText {
    private static final String LANG = "/assets/neroeconomy/lang/en_us.json";
    private static volatile Map<String, String> table;

    private LinkText() { }

    /** The English text of {@code key}, with {@code args} formatted in; the key itself when unknown. */
    public static String get(String key, Object... args) {
        if (key == null) return "";
        String text = table().get(key);
        if (text == null) return key;
        if (args == null || args.length == 0) return text.replace("%%", "%");
        try {
            return String.format(Locale.ROOT, text, args);
        } catch (IllegalFormatException malformed) {
            return text;
        }
    }

    /** Whether the lang file holds {@code key}. */
    public static boolean has(String key) { return key != null && table().containsKey(key); }

    /**
     * A refusal reason from the game's own lang (written to follow "Order refused: ") as one
     * sentence: first letter upper case, ending in a full stop.
     */
    public static String sentence(String key, Object... args) {
        String text = get(key, args).strip();
        if (text.isEmpty()) return text;
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' ? text : text + '.';
    }

    private static Map<String, String> table() {
        Map<String, String> loaded = table;
        if (loaded != null) return loaded;
        synchronized (LinkText.class) {
            if (table == null) table = load();
            return table;
        }
    }

    private static Map<String, String> load() {
        Map<String, String> out = new HashMap<>();
        try (InputStream stream = LinkText.class.getResourceAsStream(LANG)) {
            if (stream == null) return Map.copyOf(out);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet())
                if (entry.getValue().isJsonPrimitive()) out.put(entry.getKey(), entry.getValue().getAsString());
        } catch (IOException | JsonParseException | IllegalStateException unreadable) {
            // Left as it is: every lookup then answers with its key, which the tests would catch.
        }
        return Map.copyOf(out);
    }
}
