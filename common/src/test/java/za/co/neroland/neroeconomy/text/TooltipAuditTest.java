package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every control on every screen has a tooltip: the hub views (through {@code EconomyHubScreen.button}
 * and {@code box}), the vault, direct trade, the Auto-Trader and the shop. The widgets are declared in
 * client code that needs the game to run, so this walks their declarations in the sources:
 *
 * <ul>
 *   <li>a {@code Button.builder} (or {@code CycleButton}/{@code Checkbox} builder) chain sets
 *       {@code .tooltip(...)} before {@code .build()}, or the field it is stored in is given one with
 *       {@code setTooltip};</li>
 *   <li>a {@code new EditBox} is stored in a variable that is given one with {@code setTooltip};</li>
 *   <li>every call of the hub's {@code button(label, tooltipKey, ...)} passes a tooltip key.</li>
 * </ul>
 */
class TooltipAuditTest {
    private static final Pattern ASSIGNED = Pattern.compile("(?:this\\.)?(\\w+)\\s*=\\s*(?:addRenderableWidget\\()?\\s*$");
    private static final List<String> BUILDERS = List.of("Button.builder(", "CycleButton.builder(", "Checkbox.builder(");

    @Test void everyButtonBoxAndToggleHasATooltip() throws IOException {
        Path client = SourceTree.main().resolve("java/za/co/neroland/neroeconomy/client");
        List<String> missing = new ArrayList<>();
        int builders = 0;
        int boxes = 0;
        int hubButtons = 0;
        for (Path file : SourceTree.files(client, path -> path.toString().endsWith(".java"))) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            for (String builder : BUILDERS) {
                for (int at = source.indexOf(builder); at >= 0; at = source.indexOf(builder, at + 1)) {
                    builders++;
                    int end = source.indexOf(".build()", at);
                    String chain = end < 0 ? source.substring(at) : source.substring(at, end);
                    if (chain.contains(".tooltip(")) continue;
                    String field = assignedTo(source, at);
                    if (field != null && source.contains(field + ".setTooltip(")) continue;
                    missing.add(name + ":" + line(source, at) + " " + builder + " without a tooltip");
                }
            }
            for (int at = source.indexOf("new EditBox("); at >= 0; at = source.indexOf("new EditBox(", at + 1)) {
                boxes++;
                String field = assignedTo(source, at);
                if (field == null || !source.contains(field + ".setTooltip("))
                    missing.add(name + ":" + line(source, at) + " EditBox without a tooltip");
            }
            for (int at = source.indexOf("button("); at >= 0; at = source.indexOf("button(", at + 1)) {
                // The hub's helper: screen.button(...) from the views, button(...) inside the screen.
                boolean call = at > 0 && (source.startsWith(".button(", at - 1)
                        || (name.equals("EconomyHubScreen.java") && !Character.isJavaIdentifierPart(source.charAt(at - 1))
                                && !source.startsWith("Button button(", at - 7)));
                if (!call) continue;
                List<String> args = arguments(source, at + "button(".length());
                if (args.size() < 7) continue;
                hubButtons++;
                String tooltip = args.get(1).trim();
                if (tooltip.equals("null") || tooltip.equals("\"\""))
                    missing.add(name + ":" + line(source, at) + " hub button without a tooltip key");
            }
        }
        assertTrue(missing.isEmpty(), "controls without a tooltip: " + missing);
        // The walk really saw the screens: builders in five screens, the hub's boxes and buttons.
        assertTrue(builders >= 12 && boxes >= 3 && hubButtons >= 40,
                "too few controls found: " + builders + " builders, " + boxes + " boxes, " + hubButtons + " hub buttons");
    }

    /** The variable or field a widget expression starting at {@code at} is stored in, or null. */
    private static String assignedTo(String source, int at) {
        int start = Math.max(Math.max(source.lastIndexOf(';', at), source.lastIndexOf('{', at)),
                source.lastIndexOf('}', at)) + 1;
        Matcher matcher = ASSIGNED.matcher(source.substring(start, at));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int line(String source, int at) {
        int line = 1;
        for (int index = 0; index < at; index++) if (source.charAt(index) == '\n') line++;
        return line;
    }

    /** The top-level arguments of a call whose opening parenthesis was just consumed. */
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
