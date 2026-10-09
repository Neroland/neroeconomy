package za.co.neroland.neroeconomy.trader;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.rules.RuleBinder;
import za.co.neroland.neroeconomy.rules.RuleInventory;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * The rules of an Auto-Trader that need no game: how its one rule is bound to it, set, and let go
 * of. Everything here works on the store and on {@link TraderInventory.Slots}, so the very same
 * code runs for the block in the world ({@link AutoTraders} hands it the block's slots), in plain
 * JUnit and in the operation fuzzer (which hand it an array).
 *
 * <p><b>The binding.</b> A rule that works from an Auto-Trader is saved with the binding
 * {@code trader:<location>}, where the location is the key the block's owner is recorded under.
 * {@link #binder} turns that back into the block's slots — only for the block's owner, and only
 * while the block can be reached. A block that cannot be reached is an inventory that holds
 * nothing, has no room and gives nothing: the rule pauses, says so, and its order leaves the book.
 *
 * <p><b>One rule per block.</b> {@link #apply} replaces the rule a block has; nothing else creates
 * a rule with this binding.
 */
public final class TraderRules {
    /** What the binding of a rule that works from an Auto-Trader starts with. */
    public static final String BINDING_PREFIX = "trader:";

    /** Finds Auto-Trader blocks for the binder. */
    @FunctionalInterface
    public interface Blocks {
        /**
         * @return the slots of the Auto-Trader at {@code location}, or null when none can be
         *         reached there right now: its chunk is not loaded and ticking, or the block is
         *         gone. Must never load a chunk to find out.
         */
        TraderInventory.Slots at(String location);
    }

    /** Slots that cannot be reached: the block is not loaded, or is no longer there. */
    private static final TraderInventory.Slots UNLOADED = new TraderInventory.Slots() {
        @Override public boolean reachable() { return false; }
        @Override public boolean empty(int slot) { return false; }
        @Override public Lot unmodified(int slot) { return null; }
        @Override public void set(int slot, Lot lot) { throw new IllegalStateException("unloaded Auto-Trader"); }
        @Override public int limit(String item) { return 0; }
    };

    private TraderRules() { }

    /** The binding of the rule that works from the Auto-Trader at a location. */
    public static String binding(String location) { return BINDING_PREFIX + location; }

    /** A binder that knows the Auto-Traders {@code blocks} can find. */
    public static RuleBinder binder(Blocks blocks) { return binder(blocks, owner -> true); }

    /**
     * A binder that knows the Auto-Traders {@code blocks} can find, whose sell rules take goods from
     * the slots only while {@code maySupply} says their owner may be a source of goods (not in
     * creative or spectator mode, no endless supply). Nothing a creative player does reaches the
     * market through an Auto-Trader; buying into the slots is never refused.
     */
    public static RuleBinder binder(Blocks blocks, Predicate<UUID> maySupply) {
        if (blocks == null || maySupply == null) throw new IllegalArgumentException("trader blocks");
        return (store, env, owner, binding) -> resolve(blocks, maySupply, store, env, owner, binding);
    }

    private static RuleInventory resolve(Blocks blocks, Predicate<UUID> maySupply, EconomyStore store, TradeEnv env,
            AccountId owner, String binding) {
        if (store == null || binding == null || !binding.startsWith(BINDING_PREFIX)) return null;
        String location = binding.substring(BINDING_PREFIX.length());
        UUID player = Accounts.player(owner);
        // Somebody else's Auto-Trader, or none recorded there: this binding names nothing of theirs.
        if (player == null || !player.equals(store.autoTraders().owner(location))) return null;
        TradeRule rule = store.rules().boundTo(owner, binding);
        Consumer<Lot> vault = lot -> VaultGoods.deliver(store, env, owner, lot);
        TraderInventory.Slots slots = blocks.at(location);
        boolean sells = rule != null && !rule.buys();
        return new TraderInventory(slots == null ? UNLOADED : slots, sells, vault, !sells || maySupply.test(player));
    }

    /**
     * Sets the rule of the Auto-Trader at a location. A rule it already has is deleted first — its
     * budget goes back to the balance and the goods it had listed go back into the slots — so the
     * old budget can pay for the new one; the new rule is then created, and a buy rule's whole
     * budget is escrowed, or it is not created at all.
     *
     * @return the outcome of creating the new rule; when that is refused the Auto-Trader has no
     *         rule, the old one having been returned in full
     */
    public static RuleOutcome apply(EconomyStore store, TradeEnv env, AccountId owner, String location, RuleSide side,
            boolean tag, String target, long limitPrice, int perDay, long total) {
        String binding = binding(location);
        TradeRule existing = store.rules().boundTo(owner, binding);
        if (existing != null) {
            RuleOutcome deleted = store.rules().delete(env, owner, existing.id());
            if (!deleted.ok()) return deleted;
        }
        return store.rules().add(env, owner, side, tag, target, limitPrice, perDay, total, binding, null);
    }

    /**
     * The Auto-Trader at a location is gone, or is going. Every rule bound to it is deleted — its
     * order leaves the book, what is left of its budget goes back to its owner, and the goods it
     * had listed go back to the slots if they can still be reached and to the owner's vault if they
     * cannot — and the block's owner row is dropped. Safe to call when nothing is recorded, and
     * safe to call twice, so every removal path may call it.
     *
     * <p>A rule whose budget cannot be paid back just now stays, paused, with its budget in
     * escrow; its owner deletes it with the rule command once the claim has been collected.
     *
     * @return whether anything was recorded there
     */
    public static boolean release(EconomyStore store, TradeEnv env, String location) {
        if (store == null || env == null || location == null) return false;
        boolean changed = false;
        // The rules go first, while the owner row still says whose block it was.
        for (TradeRule rule : store.rules().boundTo(binding(location))) {
            store.rules().delete(env, rule.owner(), rule.id());
            changed = true;
        }
        return store.autoTraders().remove(location) || changed;
    }
}
