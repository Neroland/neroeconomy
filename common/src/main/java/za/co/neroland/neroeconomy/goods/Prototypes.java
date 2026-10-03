package za.co.neroland.neroeconomy.goods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/**
 * The table behind payload ids: each id above 0 names one stored prototype, and two things that are
 * equal always share one id. In the game a prototype is an item stack of count 1 and "equal" is
 * "same item and same components"; the table itself only needs to be told how to compare and hash,
 * so its rules are tested without the game.
 *
 * <ul>
 *   <li>Ids start at 1 and are never reused, so a stale id can never come to mean other goods.</li>
 *   <li>{@link #intern} returns the id an equal prototype already has, or stores a new one.</li>
 *   <li>{@link #retain} drops every prototype outside a set of ids still in use.</li>
 * </ul>
 *
 * @param <T> what a prototype is
 */
public final class Prototypes<T> {
    private final BiPredicate<T, T> same;
    private final ToIntFunction<T> hash;
    private final Map<Integer, T> byId = new LinkedHashMap<>();
    /** Hash of a prototype to the ids that share it; equality has the last word. */
    private final Map<Integer, List<Integer>> byHash = new HashMap<>();
    private int nextId = 1;

    public Prototypes(BiPredicate<T, T> same, ToIntFunction<T> hash) {
        if (same == null || hash == null) throw new IllegalArgumentException("prototype rules");
        this.same = same;
        this.hash = hash;
    }

    /** The id of the stored prototype equal to {@code value}, storing it under a new id if there is none. */
    public int intern(T value) {
        int found = find(value);
        if (found != Lot.PRISTINE) return found;
        if (nextId == Integer.MAX_VALUE) throw new IllegalStateException("Prototype ids exhausted");
        int id = nextId++;
        put(id, value);
        return id;
    }

    /** The id of the stored prototype equal to {@code value}, or 0 when none is stored. Changes nothing. */
    public int find(T value) {
        List<Integer> candidates = byHash.get(hash.applyAsInt(value));
        if (candidates != null) for (int id : candidates) if (same.test(byId.get(id), value)) return id;
        return Lot.PRISTINE;
    }

    /** The prototype stored under {@code id}, or null. */
    public T get(int id) { return byId.get(id); }

    public boolean contains(int id) { return byId.containsKey(id); }

    public int size() { return byId.size(); }

    /** The id the next new prototype will get. */
    public int nextId() { return nextId; }

    /** Every stored prototype by id, in the order they were stored. */
    public Map<Integer, T> snapshot() { return new LinkedHashMap<>(byId); }

    /**
     * Drops every prototype whose id is not in {@code inUse}. Ids are not reused afterwards.
     *
     * @return how many were dropped
     */
    public int retain(Set<Integer> inUse) {
        List<Integer> dropped = new ArrayList<>();
        for (int id : byId.keySet()) if (!inUse.contains(id)) dropped.add(id);
        for (int id : dropped) {
            T value = byId.remove(id);
            int key = hash.applyAsInt(value);
            List<Integer> candidates = byHash.get(key);
            if (candidates == null) continue;
            candidates.remove(Integer.valueOf(id));
            if (candidates.isEmpty()) byHash.remove(key);
        }
        return dropped.size();
    }

    /**
     * Load path: puts a saved prototype back under its saved id. An id that is not positive or is
     * already taken is refused, so one bad row cannot shadow another.
     *
     * @return whether it was restored
     */
    public boolean restore(int id, T value) {
        if (id <= Lot.PRISTINE || value == null || byId.containsKey(id)) return false;
        put(id, value);
        if (id >= nextId) nextId = id + 1;
        return true;
    }

    /** Load path: ids below {@code saved} were handed out before, even if their prototypes are gone. */
    public void restoreNextId(int saved) {
        if (saved > nextId) nextId = saved;
    }

    private void put(int id, T value) {
        byId.put(id, value);
        byHash.computeIfAbsent(hash.applyAsInt(value), ignored -> new ArrayList<>(1)).add(id);
    }
}
