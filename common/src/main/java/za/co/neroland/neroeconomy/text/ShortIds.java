package za.co.neroland.neroeconomy.text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ids in a form a player can type. An order, offer or rule is known to its owner by the first
 * characters of its id — as many as it takes to tell it apart from their other rows, never fewer
 * than {@link #MIN} — and every command that takes an id accepts that prefix, or the whole id.
 * Pure Java so the rules are unit tested directly.
 */
public final class ShortIds {
    /** The fewest characters a short id is shown with. */
    public static final int MIN = 8;

    private ShortIds() { }

    /**
     * What a typed id stands for among the caller's own rows.
     *
     * @param id        the row; null when nothing matched, or more than one did
     * @param ambiguous true when the text is the start of more than one of the caller's ids
     */
    public record Match(UUID id, boolean ambiguous) {
        public boolean found() { return id != null; }
    }

    /** The shortest prefix of {@code id}, at least {@link #MIN} characters, that no other id in {@code all} shares. */
    public static String of(UUID id, Collection<UUID> all) {
        if (id == null) return "";
        String text = id.toString();
        for (int length = MIN; length < text.length(); length++) {
            String prefix = text.substring(0, length);
            boolean unique = true;
            if (all != null) for (UUID other : all) {
                if (other != null && !other.equals(id) && other.toString().startsWith(prefix)) {
                    unique = false;
                    break;
                }
            }
            if (unique) return prefix;
        }
        return text;
    }

    /** {@link #of} for every id in {@code all}, in the same order. */
    public static List<String> all(Collection<UUID> all) {
        List<String> out = new ArrayList<>(all.size());
        for (UUID id : all) out.add(of(id, all));
        return out;
    }

    /**
     * Resolves what a player typed against their own rows: a whole id, or a prefix that exactly one
     * of {@code candidates} starts with. A whole id is returned as it is even when it is not one of
     * the candidates — the store then says it is not theirs, in the same words as for no id at all.
     */
    public static Match resolve(String typed, Collection<UUID> candidates) {
        if (typed == null || typed.isBlank()) return new Match(null, false);
        String text = typed.trim().toLowerCase(Locale.ROOT);
        if (text.length() == 36) {
            try { return new Match(UUID.fromString(text), false); }
            catch (IllegalArgumentException notAnId) { return new Match(null, false); }
        }
        UUID found = null;
        if (candidates != null) for (UUID candidate : candidates) {
            if (candidate == null || !candidate.toString().startsWith(text)) continue;
            if (found != null && !found.equals(candidate)) return new Match(null, true);
            found = candidate;
        }
        return new Match(found, false);
    }
}
