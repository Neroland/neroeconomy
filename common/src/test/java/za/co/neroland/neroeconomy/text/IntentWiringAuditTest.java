package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * §13 Input (J1 I10, D9, I6): every packet a client can send goes through the one shared guard.
 * {@code NeroEconomyNetwork.intent} is the only place a serverbound handler is made, and it spends the
 * player's rate budget and checks the open menu and its container id before the handler; every
 * payload class is declared there, as an intent or as clientbound; and the loaders register exactly
 * the list it builds. The guard itself is tested in {@code IntentRulesTest} and {@code RateLimiterTest}.
 */
class IntentWiringAuditTest {
    private static final Pattern PAYLOAD = Pattern.compile("(\\w+Payload)\\.java");

    @Test void everyServerboundPayloadIsDeclaredThroughTheGuardAndNothingElseMakesAHandler() throws IOException {
        Path main = SourceTree.main();
        Path java = main.resolve("java");
        Path network = java.resolve("za/co/neroland/neroeconomy/network/NeroEconomyNetwork.java");
        String declared = Files.readString(network);
        // One handler factory, inside intent(), which spends the budget and checks the menu first.
        int handlerAt = declared.indexOf("SERVERBOUND.add(");
        assertTrue(handlerAt > declared.indexOf("void intent("), "the handler is made inside intent()");
        assertEquals(handlerAt, declared.lastIndexOf("SERVERBOUND.add("), "one place makes a handler");
        String body = declared.substring(handlerAt);
        assertTrue(body.indexOf("withinBudget(") < body.indexOf("openMenu(")
                && body.indexOf("openMenu(") < body.indexOf("action.handle("), "budget, then menu, then handler");
        List<String> problems = new ArrayList<>();
        for (Path file : SourceTree.files(java, path -> path.toString().endsWith(".java"))) {
            String text = Files.readString(file);
            if (!file.equals(network) && (text.contains("new Serverbound<") || text.contains("SERVERBOUND")))
                problems.add(java.relativize(file) + " makes a serverbound handler outside intent()");
            Matcher name = PAYLOAD.matcher(file.getFileName().toString());
            if (name.matches() && text.contains("CustomPacketPayload")) {
                String type = name.group(1) + ".TYPE";
                if (!declared.contains("intent(" + type) && !declared.contains("clientbound(" + type))
                    problems.add(name.group(1) + " is not declared as an intent or as clientbound");
            }
        }
        // Each loader registers what NeroEconomyNetwork.serverbound() lists, and nothing of its own.
        Path root = main.getParent().getParent().getParent();
        for (String loader : List.of("neoforge", "fabric", "forge")) {
            Path sources = root.resolve(loader).resolve("src/main/java");
            if (!Files.isDirectory(sources)) continue;
            for (Path file : SourceTree.files(sources, path -> path.toString().endsWith(".java"))) {
                String text = Files.readString(file);
                boolean registers = text.contains("playToServer(") || text.contains("serverboundPlay()")
                        || text.contains("registerGlobalReceiver(");
                if (registers && !text.contains("NeroEconomyNetwork.serverbound()"))
                    problems.add(loader + "/" + sources.relativize(file) + " registers a serverbound payload itself");
            }
        }
        assertEquals(List.of(), problems);
    }
}
