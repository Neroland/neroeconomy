package za.co.neroland.neroeconomy.house;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The hostile mobs the server may post a bounty on, read from the bounty data files
 * ({@link BountyData} documents the format). Pure: the game and the tests parse the same way.
 * A file that is not valid is skipped as a whole and reported by file id and a fixed reason word.
 */
public final class BountyTable {
    /** The most mobs the table holds. */
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_QUANTITY = 64;
    public static final long MAX_PAYOUT = 10_000L;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Set<String> FIELDS = Set.of("entity", "quantity", "payout");

    /** One mob: how many kills a bounty on it asks for and what it pays. */
    public record Entry(String entity, int quantity, long payout) {
        public Entry {
            if (entity == null || !ID.matcher(entity).matches() || entity.length() > 64 || "minecraft:player".equals(entity)
                    || quantity < 1 || quantity > MAX_QUANTITY || payout < 1L || payout > MAX_PAYOUT)
                throw new IllegalArgumentException("bounty entry");
        }
    }

    public record Problem(String file, String word) { }

    public static final BountyTable EMPTY = new BountyTable(List.of(), List.of());

    private final List<Entry> entries;
    private final List<Problem> problems;

    private BountyTable(List<Entry> entries, List<Problem> problems) {
        this.entries = List.copyOf(entries);
        this.problems = List.copyOf(problems);
    }

    public static BountyTable of(List<Entry> entries) {
        return new BountyTable(entries.subList(0, Math.min(entries.size(), MAX_ENTRIES)), List.of());
    }

    /** The mobs, in entity id order; one entry per mob. */
    public List<Entry> entries() { return entries; }

    public List<Problem> problems() { return problems; }

    /** Only the entries {@code allowed} accepts: the game keeps hostile mobs that exist. */
    public BountyTable filter(java.util.function.Predicate<String> allowed) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries) if (allowed.test(entry.entity())) kept.add(entry);
        return new BountyTable(kept, problems);
    }

    /**
     * Reads the files, by id ({@code <namespace>:<path>}); a null value is a file that could not be
     * read. When two files name the same mob, the first in id order wins.
     */
    public static BountyTable parse(Map<String, JsonElement> files) {
        TreeMap<String, Entry> byEntity = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            String word = read(file.getValue(), byEntity);
            if (word != null) problems.add(new Problem(file.getKey(), word));
        }
        List<Entry> entries = new ArrayList<>(byEntity.values());
        return new BountyTable(entries.subList(0, Math.min(entries.size(), MAX_ENTRIES)), problems);
    }

    /** @return null when the file was taken in, else the reason word it was skipped for */
    private static String read(JsonElement json, TreeMap<String, Entry> into) {
        if (json == null) return "unreadable";
        if (!json.isJsonObject()) return "not_an_object";
        JsonObject root = json.getAsJsonObject();
        for (String key : root.keySet()) if (!"bounties".equals(key)) return "unknown_field";
        JsonElement list = root.get("bounties");
        if (list == null || !list.isJsonArray()) return "no_bounties";
        List<Entry> read = new ArrayList<>();
        try {
            for (JsonElement element : list.getAsJsonArray()) {
                if (!element.isJsonObject()) return "bad_entry";
                JsonObject entry = element.getAsJsonObject();
                for (String key : entry.keySet()) if (!FIELDS.contains(key)) return "unknown_field";
                if (!entry.has("entity") || !entry.has("quantity") || !entry.has("payout")) return "missing_field";
                read.add(new Entry(entry.get("entity").getAsString(), entry.get("quantity").getAsInt(),
                        entry.get("payout").getAsLong()));
            }
        } catch (RuntimeException invalid) {
            return "bad_entry";
        }
        for (Entry entry : read) into.putIfAbsent(entry.entity(), entry);
        return null;
    }
}
