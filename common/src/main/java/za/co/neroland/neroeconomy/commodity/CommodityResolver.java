package za.co.neroland.neroeconomy.commodity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import za.co.neroland.neroeconomy.commodity.CommodityRegistry.Clash;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.LoadReport;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.Problem;

/**
 * Turns parsed commodity definitions into a {@link CommodityRegistry} for one world: applies the
 * {@code compat/<modid>} gate, drops what this world does not have, refuses what may never be a
 * commodity, resolves every tag to its item ids and settles who owns an item two entries claim.
 * Pure — the world is a {@link CommodityCatalog} — so every rule here is unit tested.
 *
 * <p><b>Who owns an item.</b> An item belongs to at most one commodity. An entry that names the
 * item itself always wins over an entry that names a tag the item is in, and that is by design,
 * not a clash. Between two entries of the same kind the first in file-id order wins; the other
 * loses the item (a tag entry keeps its other members) and the clash is recorded for
 * {@code /eco admin reload-check}.
 */
final class CommodityResolver {
    private CommodityResolver() { }

    /**
     * @param definitions the parsed entries, in (file id, place in file) order
     * @param catalog     the world
     * @param parsed      what parsing already found: its counts and problems are carried over
     * @param entries     each definition's place in its file, for problem reports; null numbers
     *                    them by their place in {@code definitions}
     */
    static CommodityRegistry resolve(List<CommodityDefinition> definitions, CommodityCatalog catalog, LoadReport parsed,
            int[] entries) {
        List<Problem> problems = new ArrayList<>(parsed.problems());
        List<Clash> clashes = new ArrayList<>(parsed.clashes());
        int absent = parsed.absent();
        int disabled = parsed.disabled();
        Map<String, CommodityDefinition> byKey = new TreeMap<>();
        Map<String, CommodityDefinition> owner = new HashMap<>();
        Map<String, List<String>> items = new HashMap<>();
        Map<String, Boolean> loaded = new HashMap<>();

        // Two passes: every entry that names an item first, so it wins over any tag entry.
        for (int pass = 0; pass < 2; pass++) {
            boolean tags = pass == 1;
            for (int index = 0; index < definitions.size(); index++) {
                CommodityDefinition definition = definitions.get(index);
                if (definition == null || definition.tag() != tags) continue;
                int entry = entries != null && index < entries.length ? entries[index] : index;
                if (!definition.enabled()) {
                    disabled++;
                    continue;
                }
                String mod = definition.compatMod();
                if (mod != null && !loaded.computeIfAbsent(mod, catalog::modLoaded)) {
                    absent++;
                    continue;
                }
                // A compat file, or an entry that says so, expects to find nothing in some worlds.
                boolean lenient = mod != null || !definition.required();
                if (byKey.size() >= CommodityRegistry.MAX_COMMODITIES) {
                    problems.add(new Problem(definition.file(), entry, "too_many_commodities"));
                    continue;
                }
                CommodityDefinition earlier = byKey.get(definition.key());
                if (earlier != null) {
                    clashes.add(new Clash(definition.key(), earlier.key(), earlier.file(), definition.key(),
                            definition.file()));
                    problems.add(new Problem(definition.file(), entry, tags ? "duplicate_tag" : "duplicate_item"));
                    continue;
                }
                if (!tags) {
                    String item = definition.key();
                    if (!catalog.itemKnown(item)) {
                        if (lenient) absent++;
                        else problems.add(new Problem(definition.file(), entry, "unknown_item"));
                        continue;
                    }
                    String refusal = catalog.refusal(item);
                    if (refusal != null) {
                        problems.add(new Problem(definition.file(), entry, refusal));
                        continue;
                    }
                    byKey.put(item, definition);
                    owner.put(item, definition);
                    items.put(item, List.of(item));
                    continue;
                }
                TreeSet<String> members = new TreeSet<>(catalog.tagItems(definition.target()));
                if (members.isEmpty()) {
                    if (lenient) absent++;
                    else problems.add(new Problem(definition.file(), entry, "empty_tag"));
                    continue;
                }
                List<String> claimed = new ArrayList<>(members.size());
                boolean excluded = false;
                for (String member : members) {
                    if (catalog.refusal(member) != null) {
                        excluded = true;
                        continue;
                    }
                    CommodityDefinition holder = owner.get(member);
                    if (holder == null) claimed.add(member);
                    else if (holder.tag())
                        clashes.add(new Clash(member, holder.key(), holder.file(), definition.key(), definition.file()));
                }
                // The entry still loads for its other members; the operator is told once.
                if (excluded) problems.add(new Problem(definition.file(), entry, "members_excluded"));
                if (claimed.isEmpty()) {
                    // Every member already has a price of its own: nothing is left to cover.
                    if (!excluded) absent++;
                    continue;
                }
                byKey.put(definition.key(), definition);
                for (String member : claimed) owner.put(member, definition);
                items.put(definition.key(), List.copyOf(claimed));
            }
        }
        List<CommodityDefinition> listed = List.copyOf(byKey.values());
        Map<String, CommodityDefinition> index = Map.copyOf(byKey);
        Map<String, CommodityDefinition> byItem = Map.copyOf(owner);
        Map<String, List<String>> covered = Map.copyOf(items);
        LoadReport report = new LoadReport(parsed.files(), parsed.gatedFiles(), absent, disabled, problems, clashes);
        return new CommodityRegistry() {
            @Override public List<CommodityDefinition> commodities() { return listed; }
            @Override public CommodityDefinition get(String key) { return key == null ? null : index.get(key); }
            @Override public CommodityDefinition forItem(String item) { return item == null ? null : byItem.get(item); }
            @Override public List<String> itemsOf(String key) {
                List<String> found = key == null ? null : covered.get(key);
                return found == null ? List.of() : found;
            }
            @Override public LoadReport report() { return report; }
        };
    }
}
