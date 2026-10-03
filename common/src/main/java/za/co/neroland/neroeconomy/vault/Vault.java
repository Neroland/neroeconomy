package za.co.neroland.neroeconomy.vault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.Lots;
import za.co.neroland.neroeconomy.goods.StackLimit;

/**
 * The rules of the vault: per account, an ordered list of stacks, each a lot no larger than its
 * goods' stack limit. Pure Java, so every rule runs in plain JUnit and in the operation fuzzer.
 *
 * <p>There are two ways goods come in, and they differ on purpose:
 * <ul>
 *   <li>{@link #deposit} is what a <b>player</b> asks for — putting goods in at a Bank Terminal,
 *       buying something that is delivered to the vault. It is all or nothing against the cap, and
 *       it is refused outright while the vault is over the cap.</li>
 *   <li>{@link #deliver} is what the <b>system</b> does — a refund, an expiry, a fill of a standing
 *       order, a migration. It <b>always</b> books, even past the cap, because the alternative is
 *       destroying goods. The result says when the vault is past the cap, or past the hard bound
 *       the caller should warn an operator about.</li>
 * </ul>
 * Goods leave by {@link #withdraw}, {@link #take} or {@link #withdrawAt}, which always work. Only
 * {@link #erase} destroys goods, and it hands back what it destroyed.
 *
 * <p>The cap, the hard bound and the stack limit are passed in by the caller, so the rules hold no
 * configuration and no game state.
 */
public final class Vault {
    /**
     * What a system delivery left behind.
     *
     * @param stacks    how many stacks the account holds now
     * @param overCap   the vault is past its cap: player deposits are refused until it is back under
     * @param overBound the vault is past the hard bound; nothing was lost, but an operator should know
     */
    public record Delivery(int stacks, boolean overCap, boolean overBound) { }

    private final Map<AccountId, List<Lot>> vaults = new LinkedHashMap<>();
    /** Bumped on every change to an account, so a live view knows when to look again. Not saved. */
    private final Map<AccountId, Long> revisions = new HashMap<>();
    private long changes;

    // --- reads -------------------------------------------------------------------------------

    /** The account's stacks in order; a copy. */
    public List<Lot> stacks(AccountId account) {
        List<Lot> held = vaults.get(account);
        return held == null ? List.of() : List.copyOf(held);
    }

    public int stackCount(AccountId account) {
        List<Lot> held = vaults.get(account);
        return held == null ? 0 : held.size();
    }

    /** How many of one kind of goods the account holds. */
    public long total(AccountId account, String item, int payload) {
        List<Lot> held = vaults.get(account);
        return held == null ? 0L : Lots.total(held, item, payload);
    }

    /** How many of each kind of goods the account holds. */
    public Map<Lot.Key, Long> totals(AccountId account) {
        List<Lot> held = vaults.get(account);
        return held == null ? Map.of() : Lots.totals(held);
    }

    /** Whether the vault holds more stacks than {@code cap}; player deposits are refused while it does. */
    public boolean overCap(AccountId account, int cap) { return stackCount(account) > Math.max(0, cap); }

    /** Every account that holds anything. */
    public Set<AccountId> accounts() { return Set.copyOf(vaults.keySet()); }

    public boolean isEmpty() { return vaults.isEmpty(); }

    /** Changes each time the account's vault changes; never the same twice in one session. */
    public long revision(AccountId account) { return revisions.getOrDefault(account, 0L); }

    /** Hands every lot in every vault to {@code visitor}. */
    public void forEachLot(Consumer<Lot> visitor) {
        for (List<Lot> held : vaults.values()) held.forEach(visitor);
    }

    // --- goods coming in ---------------------------------------------------------------------

    /**
     * Whether a player-initiated deposit of {@code lots} would be accepted: the vault is not over
     * the cap now, and would not be after the goods were merged in. Changes nothing.
     */
    public boolean canAccept(AccountId account, Collection<Lot> lots, int cap, StackLimit limit) {
        if (account == null || lots == null || limit == null) return false;
        List<Lot> held = vaults.getOrDefault(account, List.of());
        int allowed = Math.max(0, cap);
        return held.size() <= allowed && Lots.merge(held, lots, limit).size() <= allowed;
    }

    /**
     * A player-initiated deposit: every lot goes in, or none does.
     *
     * @return false, with nothing changed, when {@link #canAccept} refuses
     */
    public boolean deposit(AccountId account, Collection<Lot> lots, int cap, StackLimit limit) {
        if (!canAccept(account, lots, cap, limit)) return false;
        store(account, Lots.merge(vaults.getOrDefault(account, List.of()), lots, limit));
        return true;
    }

    /**
     * A system delivery: always booked, whatever the cap. Stacks of the same goods are topped up
     * first; the rest becomes new stacks at the end.
     *
     * @param hardBound the stack count past which the caller should warn; the delivery is booked anyway
     */
    public Delivery deliver(AccountId account, Collection<Lot> lots, int cap, int hardBound, StackLimit limit) {
        if (account == null || limit == null) throw new IllegalArgumentException("delivery");
        List<Lot> held = vaults.getOrDefault(account, List.of());
        List<Lot> merged = Lots.merge(held, lots == null ? List.of() : lots, limit);
        if (!merged.equals(held)) store(account, merged);
        return new Delivery(merged.size(), merged.size() > Math.max(0, cap), merged.size() > Math.max(0, hardBound));
    }

    // --- goods going out ---------------------------------------------------------------------

    /**
     * Takes up to {@code count} of one kind of goods, newest stack first.
     *
     * @return how many were taken; less than asked for only when the vault held fewer
     */
    public long withdraw(AccountId account, String item, int payload, long count) {
        List<Lot> held = vaults.get(account);
        if (held == null || count <= 0L) return 0L;
        Lots.Taken taken = Lots.take(held, item, payload, count);
        if (taken.taken() > 0L) store(account, taken.stacks());
        return taken.taken();
    }

    /**
     * Takes exactly {@code lot}, or nothing.
     *
     * @return false, with nothing changed, when the vault holds fewer than {@code lot.count()}
     */
    public boolean take(AccountId account, Lot lot) {
        if (lot == null || total(account, lot.item(), lot.payload()) < lot.count()) return false;
        return withdraw(account, lot.item(), lot.payload(), lot.count()) == lot.count();
    }

    /**
     * Takes up to {@code count} from the stack at {@code index}.
     *
     * @return what was taken, or null when there is no such stack or {@code count} is not positive
     */
    public Lot withdrawAt(AccountId account, int index, int count) {
        List<Lot> held = vaults.get(account);
        if (held == null || index < 0 || index >= held.size() || count <= 0) return null;
        Lot stack = held.get(index);
        int moved = Math.min(count, stack.count());
        List<Lot> next = new ArrayList<>(held);
        if (moved == stack.count()) next.remove(index);
        else next.set(index, stack.withCount(stack.count() - moved));
        store(account, next);
        return stack.withCount(moved);
    }

    // --- the inventory boundary --------------------------------------------------------------

    /**
     * Replaces the account's whole list. This is the write a live view makes after the game has
     * moved stacks between the vault's slots and a player's inventory: the caller vouches that the
     * difference went to, or came from, that inventory. Nothing else may use it.
     *
     * @return whether anything changed
     */
    public boolean replaceAll(AccountId account, List<Lot> stacks) {
        if (account == null || stacks == null) return false;
        List<Lot> next = new ArrayList<>(stacks.size());
        for (Lot lot : stacks) if (lot != null) next.add(lot);
        if (next.equals(vaults.getOrDefault(account, List.of()))) return false;
        store(account, next);
        return true;
    }

    // --- erasure -----------------------------------------------------------------------------

    /**
     * Destroys the account's vault.
     *
     * @return what was destroyed; empty when the account held nothing
     */
    public List<Lot> erase(AccountId account) {
        List<Lot> held = vaults.remove(account);
        if (held == null) return List.of();
        touch(account);
        return List.copyOf(held);
    }

    // --- persistence -------------------------------------------------------------------------

    /** Every vault, for saving. */
    public Map<AccountId, List<Lot>> snapshot() {
        Map<AccountId, List<Lot>> copy = new LinkedHashMap<>();
        vaults.forEach((account, held) -> copy.put(account, List.copyOf(held)));
        return copy;
    }

    /** Load path: appends saved stacks to an account exactly as they were saved. */
    public void restore(AccountId account, List<Lot> stacks) {
        if (account == null || stacks == null || stacks.isEmpty()) return;
        vaults.computeIfAbsent(account, ignored -> new ArrayList<>()).addAll(stacks);
    }

    private void store(AccountId account, List<Lot> next) {
        if (next.isEmpty()) vaults.remove(account);
        else vaults.put(account, new ArrayList<>(next));
        touch(account);
    }

    private void touch(AccountId account) { revisions.put(account, ++changes); }
}
