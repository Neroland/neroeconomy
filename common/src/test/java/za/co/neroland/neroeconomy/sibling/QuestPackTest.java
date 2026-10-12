package za.co.neroland.neroeconomy.sibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.text.SourceTree;

/**
 * The quest pack under {@code data/neroeconomy/neroquests} follows the quest mod's pack rules without
 * that mod on the classpath: translation keys that exist, objectives and rewards of the kinds a pack
 * may use, server scope only in a chapter that allows it, prerequisites inside this namespace or the
 * quest mod's own story, every quest placed in the chapter, and every gate an objective or a hook names
 * shipped as a player-scope gate file of this mod (a hook's without {@code requires}).
 */
class QuestPackTest {
    static final String NAMESPACE = "neroeconomy";
    static final Set<String> OBJECTIVES = Set.of("collect_item", "craft_item", "kill_entity", "reach_dimension",
            "visit_structure", "gate_open", "quest_complete", "custom_event", "economy", "manual");
    static final Set<String> REWARDS = Set.of("item", "xp", "currency", "mod_hook");
    static final String KEY = "[a-z0-9_-]+(\\.[a-z0-9_-]+)+";

    @Test void packFollowsTheQuestFormat() throws IOException {
        Path root = SourceTree.main().resolve("resources");
        Path data = root.resolve("data/" + NAMESPACE + "/neroquests");
        Map<String, JsonObject> quests = read(data.resolve("quests"));
        Map<String, JsonObject> chapters = read(data.resolve("chapters"));
        JsonObject lang = JsonParser.parseString(Files.readString(
                root.resolve("assets/" + NAMESPACE + "/lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(List.of(NAMESPACE + ":traders_ledger"), new ArrayList<>(chapters.keySet()));
        assertEquals(15, quests.size(), "quests in the pack");
        List<String> problems = new ArrayList<>();
        Set<String> serverOk = new HashSet<>();
        Set<String> placed = new HashSet<>();
        chapters.forEach((id, c) -> {
            key(problems, lang, id, c, "title", true);
            for (JsonElement e : c.getAsJsonArray("quests")) {
                String q = e.getAsJsonObject().get("quest").getAsString();
                if (!quests.containsKey(q)) problems.add(id + " lists unknown/foreign quest " + q);
                if (!placed.add(q)) problems.add(id + " lists " + q + " twice");
                if (c.has("allow_server_scope") && c.get("allow_server_scope").getAsBoolean()) serverOk.add(q);
            }
        });
        quests.forEach((id, q) -> {
            if (!placed.contains(id)) problems.add(id + " is in no chapter");
            key(problems, lang, id, q, "title", true);
            key(problems, lang, id, q, "description", false);
            boolean server = q.has("scope") && "server".equals(q.get("scope").getAsString());
            if (server && !serverOk.contains(id)) problems.add(id + ": scope server needs allow_server_scope");
            if (q.has("prerequisites")) q.getAsJsonArray("prerequisites").forEach(p -> ref(problems, quests, id, p.getAsString()));
            JsonArray objectives = q.getAsJsonArray("objectives");
            if (objectives == null || objectives.isEmpty()) problems.add(id + ": no objectives");
            else for (JsonElement e : objectives) {
                JsonObject o = e.getAsJsonObject();
                String type = o.get("type").getAsString().replace("neroquests:", "");
                if (!OBJECTIVES.contains(type)) problems.add(id + ": unknown objective " + type);
                if (type.equals("quest_complete")) ref(problems, quests, id, o.get("quest").getAsString());
                if (type.equals("gate_open")) gate(problems, root, id, o.get("gate").getAsString(), false);
                if (type.equals("custom_event")) {
                    if (!o.get("channel").getAsString().startsWith(NAMESPACE + ":"))
                        problems.add(id + ": custom_event channel is not this mod's");
                    if (!server && !(o.has("audience") && "everyone".equals(o.get("audience").getAsString())))
                        problems.add(id + ": custom_event on a player quest needs \"audience\": \"everyone\"");
                }
            }
            if (q.has("rewards")) for (JsonElement e : q.getAsJsonArray("rewards")) {
                JsonObject r = e.getAsJsonObject();
                String type = r.get("type").getAsString().replace("neroquests:", "");
                if (!REWARDS.contains(type)) problems.add(id + ": reward " + type + " not allowed in a pack");
                if (type.equals("mod_hook")) {
                    String gate = r.get("gate").getAsString();
                    if (!gate.startsWith(NAMESPACE + ":hook/")) problems.add(id + ": mod_hook gate must be " + NAMESPACE + ":hook/<id>");
                    else gate(problems, root, id, gate, true);
                    if (QuestHooks.Hook.byGate(net.minecraft.resources.Identifier.parse(gate)) == null)
                        problems.add(id + ": no listener for hook " + gate);
                }
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    static void gate(List<String> problems, Path root, String id, String gate, boolean hook) {
        String path = gate.substring(gate.indexOf(':') + 1);
        if (!gate.startsWith(NAMESPACE + ":")) { problems.add(id + ": gate " + gate + " is not this mod's"); return; }
        Path file = root.resolve("data/" + NAMESPACE + "/neroland_gates/" + path + ".json");
        if (!Files.isRegularFile(file)) { problems.add(id + ": no gate file for " + gate); return; }
        try {
            JsonObject definition = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!"player".equals(definition.get("scope").getAsString())) problems.add(gate + " is not a player gate");
            if (definition.has("requires")) problems.add(gate + " is a record gate and must have no requires");
        } catch (IOException | RuntimeException unreadable) {
            problems.add(gate + ": unreadable gate file");
        }
    }

    static void ref(List<String> problems, Map<String, JsonObject> quests, String id, String target) {
        if (target.startsWith("neroquests:")) return;
        if (!target.startsWith(NAMESPACE + ":")) problems.add(id + ": depends on another pack's quest " + target);
        else if (!quests.containsKey(target)) problems.add(id + ": unknown quest " + target);
    }

    static void key(List<String> problems, JsonObject lang, String id, JsonObject o, String field, boolean required) {
        if (!o.has(field)) { if (required) problems.add(id + ": missing " + field); return; }
        String key = o.get(field).getAsString();
        if (!key.matches(KEY)) problems.add(id + ": " + field + " is not a translation key");
        else if (!lang.has(key)) problems.add(id + ": en_us.json has no " + key);
    }

    static Map<String, JsonObject> read(Path dir) throws IOException {
        Map<String, JsonObject> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                out.put(NAMESPACE + ":" + rel.substring(0, rel.length() - 5),
                        JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
        return out;
    }
}
