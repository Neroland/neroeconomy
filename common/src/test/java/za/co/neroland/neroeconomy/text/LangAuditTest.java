package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The English lang file against the sources, read from disk: it is valid, sorted and free of
 * duplicates; every key the code names is in it; every key in it is one the code can show; and no
 * player-visible English is built as a literal component in {@code common/}.
 *
 * <p>A key counts as used when a source string names it, when a source string ending in {@code .} or
 * {@code _} is a prefix of it (the families built at run time from an enum or an id), when it is one
 * of the suffixed variants the screens append ({@code .tooltip}, {@code .hint}, {@code .short},
 * {@code .1}, {@code .2}, ...) of a used key or prefix, or when it is a block's or an item's name and
 * that id is registered.
 */
class LangAuditTest {
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern KEY = Pattern.compile("^[a-z_]+\\.neroeconomy\\.[a-z0-9_.]+$");
    private static final Pattern SUFFIX = Pattern.compile("\\.(tooltip|hint|short|\\d+)$");
    private static final Pattern REGISTRY_NAME = Pattern.compile("^(block|item)\\.neroeconomy\\.([a-z0-9_]+)$");
    private static final Pattern LINE = Pattern.compile("^  \"((?:[^\"\\\\]|\\\\.)*)\": ");
    private static final Pattern LETTERS = Pattern.compile("[A-Za-z]{2,}");

    private static Path main;
    private static String raw;
    private static JsonObject lang;
    private static Set<String> strings;
    private static List<String> prefixes;

    @BeforeAll static void read() throws IOException {
        main = SourceTree.main();
        raw = Files.readString(main.resolve("resources/assets/neroeconomy/lang/en_us.json"), StandardCharsets.UTF_8);
        lang = JsonParser.parseString(raw).getAsJsonObject();
        strings = new HashSet<>();
        for (Path file : SourceTree.files(main, path -> {
            String name = path.toString().replace('\\', '/');
            return name.endsWith(".java") || (name.endsWith(".json") && !name.contains("/lang/"));
        })) {
            Matcher matcher = STRING.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) strings.add(matcher.group(1));
        }
        prefixes = strings.stream().filter(s -> s.contains(".neroeconomy") && (s.endsWith(".") || s.endsWith("_")))
                .toList();
    }

    @Test void theLangFileIsValidSortedAndHasNoDuplicates() {
        List<String> keys = new ArrayList<>();
        for (String line : raw.split("\r?\n")) {
            Matcher matcher = LINE.matcher(line);
            if (matcher.find()) keys.add(JsonParser.parseString("\"" + matcher.group(1) + "\"").getAsString());
        }
        assertEquals(lang.size(), keys.size(), "a key is in the file twice, or a line is not one key per line");
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(null);
        assertEquals(sorted, keys, "en_us.json is not sorted by key");
        assertTrue(raw.contains("\r\n") && !raw.replace("\r\n", "").contains("\n"), "en_us.json is not CRLF throughout");
    }

    @Test void everyKeyTheCodeNamesExists() {
        Set<String> missing = new TreeSet<>();
        for (String string : strings) {
            // A prefix ending in "." is a family built at run time; everyKeyInTheLangFileIsUsed covers it.
            if (!KEY.matcher(string).matches() || string.endsWith(".") || lang.has(string)) continue;
            // A base the code appends .1/.2 (or another suffix) to: some key extends it.
            if (lang.keySet().stream().anyMatch(key -> key.startsWith(string + "."))) continue;
            missing.add(string);
        }
        assertTrue(missing.isEmpty(), "named in code but not in en_us.json: " + missing);
    }

    @Test void everyKeyInTheLangFileIsUsed() {
        Set<String> unused = new TreeSet<>();
        for (String key : lang.keySet()) if (!used(key)) unused.add(key);
        assertTrue(unused.isEmpty(), "in en_us.json but nothing shows it: " + unused);
    }

    @Test void noPlayerVisibleEnglishIsALiteralComponent() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : SourceTree.files(main.resolve("java"), path -> path.toString().endsWith(".java"))) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            int at = source.indexOf("Component.literal(");
            while (at >= 0) {
                String call = arguments(source, at + "Component.literal(".length());
                Matcher matcher = STRING.matcher(call);
                // Numbers, ids, tag ids and punctuation only: a word in a literal is English.
                while (matcher.find())
                    if (LETTERS.matcher(matcher.group(1)).find())
                        found.add(file.getFileName() + ": Component.literal(" + call + ")");
                at = source.indexOf("Component.literal(", at + 1);
            }
        }
        assertTrue(found.isEmpty(), "literal English: " + found);
    }

    private static boolean used(String key) {
        for (String candidate = key; ; ) {
            if (covered(candidate)) return true;
            Matcher suffix = SUFFIX.matcher(candidate);
            if (!suffix.find()) break;
            candidate = candidate.substring(0, suffix.start());
        }
        Matcher registry = REGISTRY_NAME.matcher(key);
        return registry.matches() && strings.contains(registry.group(2));
    }

    private static boolean covered(String key) {
        if (strings.contains(key)) return true;
        for (String prefix : prefixes) if (key.startsWith(prefix)) return true;
        return false;
    }

    /** The text between an opening parenthesis (already consumed) and its closing one. */
    private static String arguments(String source, int from) {
        int depth = 1;
        boolean quoted = false;
        for (int index = from; index < source.length(); index++) {
            char c = source.charAt(index);
            if (quoted) {
                if (c == '\\') index++;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return source.substring(from, index);
        }
        return source.substring(from);
    }
}
