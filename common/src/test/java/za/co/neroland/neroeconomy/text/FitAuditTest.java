package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.client.demo.DemoScript;
import za.co.neroland.neroeconomy.handbook.Handbook;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * Every screen fits a 320x240 scaled window, and every English string drawn in a fixed-width slot
 * fits its slot. The widths are estimated, conservatively, from the vanilla default font: six pixels
 * a character (a five-pixel glyph and its one-pixel gap) except the known narrow ASCII glyphs, and
 * every {@code %s} as four digits. The slots are read from the client sources:
 *
 * <ul>
 *   <li>the hub's {@code text(g, text, x, y, width, colour)} — the width;</li>
 *   <li>the hub's {@code button(label, tooltip, x, y, width, height, action)} and a
 *       {@code Button.builder(label, ...).bounds(x, y, width, height)} — the width less the
 *       two-pixel margins a button keeps each side;</li>
 *   <li>{@code trim(text, width)} in the other screens — the width;</li>
 *   <li>a confirm button relabelled with {@code label(button, key, armed)} — its width, for its own
 *       word and for "Confirm";</li>
 *   <li>a list's empty message ({@code drawRows}) — two lines of the list's width, as it wraps.</li>
 * </ul>
 *
 * <p>A key built from a variable ({@code lines + ".1"}) is resolved through the variable's
 * assignment. Text drawn with {@code wrapped} has no fixed width and is not checked.
 */
class FitAuditTest {
    private static final int WINDOW_W = 320;
    private static final int WINDOW_H = 240;
    private static final Map<Character, Integer> NARROW = new HashMap<>();
    static {
        String chars = " !\"'()*,.:;<>@I[]`fiklt{|}~";
        int[] widths = { 4, 2, 4, 2, 4, 4, 4, 2, 2, 2, 2, 5, 5, 7, 4, 4, 4, 3, 5, 2, 5, 3, 4, 4, 2, 4, 7 };
        for (int index = 0; index < chars.length(); index++) NARROW.put(chars.charAt(index), widths[index]);
    }
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sd]");
    private static final Pattern CONSTANT = Pattern.compile("(?:static )?final int (\\w+) = ([^;]+);");
    private static final Pattern CALL = Pattern.compile(
            "(?<![\\w.])(?:s\\.|this\\.screen\\.|screen\\.)?(text|button|trim|drawRows)\\(|Button\\.builder\\(");
    private static final Pattern FIELD_BUTTON = Pattern.compile("this\\.(\\w+)\\s*=\\s*this\\.screen\\.button\\(");
    private static final Pattern RELABEL = Pattern.compile("(?<![\\w.])label\\(this\\.(\\w+),\\s*\"([^\"]+)\"");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]+)\"");

    private static Path client;
    private static JsonObject lang;

    /** One string in one slot: how much room is left (negative: it overflows). */
    record Fit(int room, String where, String key, int slot, int width) { }

    @BeforeAll static void read() throws IOException {
        Path main = SourceTree.main();
        client = main.resolve("java/za/co/neroland/neroeconomy/client");
        lang = JsonParser.parseString(Files.readString(main.resolve("resources/assets/neroeconomy/lang/en_us.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test void everyScreenFitsA320By240Window() throws IOException {
        List<String> screens = new ArrayList<>();
        for (Path file : SourceTree.files(client, path -> path.toString().endsWith("Screen.java"))) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Integer> constants = constants(source);
            Integer width = constants.get("PANEL_W");
            Integer height = constants.get("PANEL_H");
            if (width == null || height == null) continue;
            screens.add(file.getFileName() + " " + width + "x" + height);
            assertTrue(width <= WINDOW_W && height <= WINDOW_H, file.getFileName() + " is " + width + "x" + height);
        }
        assertTrue(screens.size() >= 5, "screens found: " + screens);
    }

    @Test void everyStringFitsItsSlot() throws IOException {
        List<Fit> fits = fits();
        List<Fit> over = fits.stream().filter(fit -> fit.room() < 0).toList();
        List<Fit> tight = fits.stream().sorted(Comparator.comparingInt(Fit::room)).limit(10).toList();
        System.out.println("[FitAuditTest] " + fits.size() + " strings in fixed slots; the ten tightest:");
        tight.forEach(fit -> System.out.println("[FitAuditTest]   " + fit));
        assertTrue(over.isEmpty(), "overflowing: " + over);
        assertTrue(fits.size() >= 150, "too few slots found: " + fits.size());
    }

    static List<Fit> fits() throws IOException {
        List<Fit> out = new ArrayList<>();
        for (Path file : SourceTree.files(client, path -> path.toString().endsWith(".java"))) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            Map<String, Integer> constants = constants(source);
            Matcher call = CALL.matcher(source);
            while (call.find()) {
                String kind = call.group(1) == null ? "builder" : call.group(1);
                int open = call.end();
                List<String> args = arguments(source, open);
                String text = null;
                Integer slot = null;
                int lines = 1;
                switch (kind) {
                    case "text" -> { if (args.size() == 6) { text = args.get(1); slot = value(args.get(4), constants); } }
                    case "button" -> { if (args.size() == 7) { text = args.get(0); slot = minus(value(args.get(4), constants), 4); } }
                    case "trim" -> { if (args.size() == 2) { text = args.get(0); slot = value(args.get(1), constants); } }
                    case "drawRows" -> {
                        if (args.size() == 3) { text = "Component.translatable(" + args.get(1) + ")"; slot = 236; lines = 2; }
                    }
                    default -> {
                        if (args.size() == 2) {
                            text = args.get(0);
                            int bounds = source.indexOf(".bounds(", open);
                            if (bounds >= 0 && bounds < open + 400) {
                                List<String> box = arguments(source, bounds + ".bounds(".length());
                                if (box.size() == 4) slot = minus(value(box.get(2), constants), 4);
                            }
                        }
                    }
                }
                if (text == null || slot == null) continue;
                for (String key : keys(text, source, call.start())) {
                    int width = width(lang.get(key).getAsString());
                    out.add(new Fit(slot * lines - width, name + ":" + line(source, call.start()), key, slot * lines, width));
                }
            }
            Map<String, Integer> fieldWidths = new HashMap<>();
            Matcher field = FIELD_BUTTON.matcher(source);
            while (field.find()) {
                List<String> args = arguments(source, field.end());
                Integer width = args.size() == 7 ? value(args.get(4), constants) : null;
                if (width != null) fieldWidths.put(field.group(1), width - 4);
            }
            Matcher relabel = RELABEL.matcher(source);
            while (relabel.find()) {
                Integer slot = fieldWidths.get(relabel.group(1));
                if (slot == null) continue;
                for (String key : List.of(relabel.group(2), "gui.neroeconomy.hub.confirm")) {
                    if (!lang.has(key)) continue;
                    int width = width(lang.get(key).getAsString());
                    out.add(new Fit(slot - width, name + ":" + line(source, relabel.start()), key, slot, width));
                }
            }
        }
        return out;
    }

    /**
     * The "Show me" demo's text slots ({@code HubDemo}): every step's words wrap to at most three
     * lines of the caption's 240 pixels, the DEMO banner fits the 248 pixels across the header, the
     * step counter its 96, and Back / Next / Done / Close their 44-pixel buttons.
     */
    @Test void everyDemoTextFitsItsSlot() {
        List<String> over = new ArrayList<>();
        int steps = 0;
        for (HubTab tab : HubTab.values()) {
            DemoScript script = DemoScript.of(tab);
            for (int index = 0; index < script.steps().size(); index++) {
                String key = script.stepKey(index);
                int lines = wrappedLines(lang.get(key).getAsString(), 240);
                steps++;
                if (lines > 3) over.add(key + " takes " + lines + " lines");
            }
        }
        Map<String, Integer> slots = Map.of(DemoScript.BANNER, 248, DemoScript.COUNTER, 96, DemoScript.BACK, 40,
                DemoScript.NEXT, 40, DemoScript.DONE, 40, DemoScript.CLOSE, 40);
        for (Map.Entry<String, Integer> slot : slots.entrySet()) {
            int width = width(lang.get(slot.getKey()).getAsString());
            if (width > slot.getValue()) over.add(slot.getKey() + " is " + width + " of " + slot.getValue());
        }
        assertTrue(steps >= 50, "too few demo steps: " + steps);
        assertTrue(over.isEmpty(), "demo text too long: " + over);
    }

    /**
     * The Trader's Handbook ({@code HandbookScreen}), with its slots read from the screen's constants:
     * every chapter name fits its list button, every heading the page width, every page wraps to the
     * lines the page has room for, the page counter fits between Back and Next, and the buttons' words
     * fit the buttons.
     */
    @Test void everyHandbookLineFitsItsSlot() throws IOException {
        Map<String, Integer> slot = constants(Files.readString(client.resolve("handbook/HandbookScreen.java"),
                StandardCharsets.UTF_8));
        int pageWidth = slot.get("PAGE_W");
        int lines = slot.get("TEXT_LINES");
        List<String> over = new ArrayList<>();
        int pages = 0;
        for (Handbook.Chapter chapter : Handbook.CHAPTERS_IN_ORDER) {
            fit(over, chapter.nameKey(), slot.get("LIST_W") - 4);
            fit(over, chapter.titleKey(), pageWidth);
            for (int page = 0; page < chapter.pages(); page++) {
                pages++;
                int taken = wrappedLines(lang.get(chapter.pageKey(page)).getAsString(), pageWidth);
                if (taken > lines) over.add(chapter.pageKey(page) + " takes " + taken + " of " + lines + " lines");
            }
        }
        fit(over, Handbook.PAGE, slot.get("COUNTER_W"));
        fit(over, Handbook.DEMO, slot.get("DEMO_W") - 4);
        fit(over, Handbook.PREVIOUS, slot.get("PAGING_W") - 4);
        fit(over, Handbook.NEXT, slot.get("PAGING_W") - 4);
        fit(over, Handbook.TITLE, slot.get("PANEL_W") - 16);
        assertTrue(slot.get("TEXT_Y") + lines * 9 <= slot.get("DEMO_Y"), "the words run into the Open demo button");
        assertTrue(pages >= 30, "too few pages: " + pages);
        assertTrue(over.isEmpty(), "handbook text too long: " + over);
    }

    private static void fit(List<String> over, String key, int slot) {
        int width = width(lang.get(key).getAsString());
        if (width > slot) over.add(key + " is " + width + " of " + slot);
    }

    /** How many lines {@code value} wraps to at {@code width} pixels, breaking between words. */
    static int wrappedLines(String value, int width) {
        int lines = 1;
        int used = 0;
        int space = width(" ");
        for (String word : value.split(" ")) {
            int w = width(word);
            if (used > 0 && used + space + w > width) {
                lines++;
                used = w;
            } else {
                used += (used > 0 ? space : 0) + w;
            }
        }
        return lines;
    }

    /** The conservative pixel width of a lang value, every placeholder as four digits. */
    static int width(String value) {
        String shown = PLACEHOLDER.matcher(value.replace("%%", "%")).replaceAll("0000");
        int width = 0;
        for (int index = 0; index < shown.length(); index++) width += NARROW.getOrDefault(shown.charAt(index), 6);
        return width;
    }

    /** The lang keys a {@code Component.translatable(...)} expression can name; none for anything else. */
    private static List<String> keys(String text, String source, int at) {
        String expression = text.trim();
        if (!expression.startsWith("Component.translatable(")) return List.of();
        List<String> args = arguments(expression, "Component.translatable(".length());
        if (args.isEmpty()) return List.of();
        String first = args.get(0).trim();
        String suffix = "";
        Matcher appended = Pattern.compile("\\+\\s*\"(\\.[^\"]+)\"\\s*$").matcher(first);
        if (appended.find()) {
            suffix = appended.group(1);
            first = first.substring(0, appended.start()).trim();
        }
        List<String> literals = new ArrayList<>();
        Matcher literal = LITERAL.matcher(first);
        while (literal.find()) literals.add(literal.group(1));
        if (literals.isEmpty() && first.matches("\\w+")) {
            // A variable: the literals of its last assignment before the call.
            Matcher assignment = Pattern.compile("\\b" + first + "\\s*=\\s*([^;]+);").matcher(source.substring(0, at));
            String last = null;
            while (assignment.find()) last = assignment.group(1);
            if (last != null) {
                Matcher inner = LITERAL.matcher(last);
                while (inner.find()) literals.add(inner.group(1));
            }
        }
        List<String> keys = new ArrayList<>();
        for (String literalKey : literals) if (lang.has(literalKey + suffix)) keys.add(literalKey + suffix);
        return keys;
    }

    private static Integer minus(Integer value, int less) { return value == null ? null : value - less; }

    private static Map<String, Integer> constants(String source) {
        Map<String, String> raw = new HashMap<>();
        Matcher matcher = CONSTANT.matcher(source);
        while (matcher.find()) raw.put(matcher.group(1), matcher.group(2));
        Map<String, Integer> out = new HashMap<>();
        // Resolve in passes, so a constant built from others is found once they are.
        for (int pass = 0; pass < 4; pass++)
            raw.forEach((name, expression) -> { Integer v = value(expression, out); if (v != null) out.put(name, v); });
        return out;
    }

    /** An int expression of literals, known constants, + - * / and parentheses; null when it is anything else. */
    static Integer value(String expression, Map<String, Integer> constants) {
        String text = expression.trim().replace("this.", "");
        Matcher names = Pattern.compile("[A-Za-z_]\\w*").matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (names.find()) {
            Integer known = constants.get(names.group());
            if (known == null) return null;
            names.appendReplacement(resolved, Integer.toString(known));
        }
        names.appendTail(resolved);
        try {
            int[] at = { 0 };
            String s = resolved.toString().replace(" ", "");
            int result = sum(s, at);
            return at[0] == s.length() ? result : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static int sum(String s, int[] at) {
        int value = product(s, at);
        while (at[0] < s.length() && (s.charAt(at[0]) == '+' || s.charAt(at[0]) == '-')) {
            char op = s.charAt(at[0]++);
            int next = product(s, at);
            value = op == '+' ? value + next : value - next;
        }
        return value;
    }

    private static int product(String s, int[] at) {
        int value = atom(s, at);
        while (at[0] < s.length() && (s.charAt(at[0]) == '*' || s.charAt(at[0]) == '/')) {
            char op = s.charAt(at[0]++);
            int next = atom(s, at);
            value = op == '*' ? value * next : value / next;
        }
        return value;
    }

    private static int atom(String s, int[] at) {
        if (s.charAt(at[0]) == '(') {
            at[0]++;
            int value = sum(s, at);
            if (s.charAt(at[0]++) != ')') throw new IllegalArgumentException(s);
            return value;
        }
        boolean negative = s.charAt(at[0]) == '-';
        if (negative) at[0]++;
        int start = at[0];
        while (at[0] < s.length() && Character.isDigit(s.charAt(at[0]))) at[0]++;
        int value = Integer.parseInt(s.substring(start, at[0]));
        return negative ? -value : value;
    }

    private static int line(String source, int at) {
        int line = 1;
        for (int index = 0; index < at; index++) if (source.charAt(index) == '\n') line++;
        return line;
    }

    /** The top-level arguments of a call whose opening parenthesis ends just before {@code from}. */
    private static List<String> arguments(String source, int from) {
        List<String> out = new ArrayList<>();
        int depth = 1;
        boolean quoted = false;
        int start = from;
        for (int index = from; index < source.length(); index++) {
            char c = source.charAt(index);
            if (quoted) {
                if (c == '\\') index++;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') {
                if (--depth == 0) {
                    out.add(source.substring(start, index));
                    return out;
                }
            } else if (c == ',' && depth == 1) {
                out.add(source.substring(start, index));
                start = index + 1;
            }
        }
        return out;
    }
}
