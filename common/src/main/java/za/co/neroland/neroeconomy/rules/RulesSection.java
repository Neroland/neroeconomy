package za.co.neroland.neroeconomy.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BookResult;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.RuleOrders;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.Traded;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.EscrowHolder;
import za.co.neroland.neroeconomy.economy.EscrowMath;
import za.co.neroland.neroeconomy.economy.Fees;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.market.MarketLimits;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * The auto-trade rules section of the economy store: standing instructions to buy or sell that
 * keep working with their owner offline and the server empty.
 *
 * <p><b>A rule</b> names an item id — or an item tag — a side, a limit price, a quantity per
 * economy-day and a total: a budget in credits for a buy rule, a quantity for a sell rule. It is
 * bound to an inventory ({@link RuleInventory}): the owner's vault, or whatever a
 * {@link RuleBinder} resolves. It reports exactly one {@link RuleStatus}.
 *
 * <p><b>How a rule trades.</b> Through ordinary orders on the order book, so there is one matching
 * engine and one settlement. A buy rule escrows its whole budget when it is created; an item buy
 * rule keeps one resting buy order for what is left of today's quantity and budget; a sell rule
 * lists what its inventory holds of the target, up to today's quantity, at its limit price; a tag
 * buy rule has nothing to rest on the book, so it takes crossing sell orders of items in its tag,
 * cheapest first. Every order a rule places pays the listing fee and every fill the market fee,
 * exactly like a player's, and a rule's order is never given a discount by a price modifier — so
 * two rules trading with each other can only ever lose credits to the Reserve.
 *
 * <p><b>Where the credits are.</b> A buy rule's budget is in the ledger's escrow account from the
 * moment the rule exists. {@code budget} here is the part not committed to an order; the part
 * behind the rule's order is the order's own escrow, on the book. A fill below the limit, an
 * expired order and a paused rule all return credits to the budget, never to the balance; only
 * deleting the rule pays the budget back.
 *
 * <p><b>Evaluation.</b> A rule is looked at again when a simulation step comes round and whenever
 * the book changes for an item it trades. Rules waiting to be looked at queue up and are taken in
 * turn, at most {@code ruleEvaluationsPerTick} per tick, so any number of rules cannot stall one.
 * The queue is saved with the store.
 *
 * <p><b>Bounds.</b> {@code maxRulesPerPlayer} rules per account, one order per rule (which counts
 * towards the account's order caps), and {@code ruleDailyValueCap}: the credits' worth all of one
 * account's rules may fill per economy-day, enforced on every fill. <b>Erasure.</b>
 * {@link #forgetPlayer}. <b>Export.</b> {@link #exportFor}.
 */
public final class RulesSection extends EconomySection implements EscrowHolder, RuleOrders {
    public static final Codec<RulesSection> CODEC = codec();
    /** The binding of a rule that works from its owner's vault. */
    public static final String VAULT = "vault";
    /** The largest quantity a sell rule may be for in total. */
    public static final long MAX_TOTAL_QUANTITY = 1_000_000L;
    /** The longest binding a rule may be saved with. */
    public static final int MAX_BINDING_LENGTH = 128;
    /** The most sell orders one evaluation of a tag buy rule takes from. */
    private static final int MAX_SWEEP_FILLS = 8;
    private static final long NO_TICK = Long.MIN_VALUE;

    /** A rule that cannot be reached: nothing in, nothing out. */
    private static final RuleInventory NOWHERE = new RuleInventory() {
        @Override public boolean reachable() { return false; }
        @Override public long available(String item) { return 0L; }
        @Override public Map<String, Long> goods() { return Map.of(); }
        @Override public boolean take(Lot lot) { return false; }
        @Override public void put(Lot lot) { throw new IllegalStateException("unreachable inventory"); }
        @Override public long room(String item) { return 0L; }
    };

    /** The live rule. Only this section changes it; readers get a {@link TradeRule}. */
    private static final class Rule {
        private final UUID id;
        private final AccountId owner;
        private final RuleSide side;
        private final boolean tag;
        private final String target;
        private final long limitPrice;
        private final int perDay;
        private final String binding;
        private final long sequence;
        private final long createdAt;
        private long total;
        private boolean paused;
        private RuleStatus status = RuleStatus.RUNNING;
        private long day;
        private int filledToday;
        private long done;
        private long budget;
        private UUID orderId;

        private Rule(UUID id, AccountId owner, RuleSide side, boolean tag, String target, long limitPrice, int perDay,
                long total, String binding, long sequence, long createdAt) {
            this.id = id;
            this.owner = owner;
            this.side = side;
            this.tag = tag;
            this.target = target;
            this.limitPrice = limitPrice;
            this.perDay = perDay;
            this.total = total;
            this.binding = binding;
            this.sequence = sequence;
            this.createdAt = createdAt;
        }

        private TradeRule view() {
            return new TradeRule(id, owner, side, tag, target, limitPrice, perDay, total, binding, paused, status,
                    filledToday, done, budget, orderId, createdAt);
        }
    }

    /** What one account's rules have filled in one economy-day. */
    private record DayValue(long day, long value) { }

    private final Map<UUID, Rule> rules = new LinkedHashMap<>();
    /** The order each rule keeps on the book, back to the rule. Rebuilt on load. */
    private final Map<UUID, UUID> byOrder = new HashMap<>();
    private final Map<String, Set<UUID>> byItem = new HashMap<>();
    private final Map<String, Set<UUID>> byTag = new HashMap<>();
    private final Map<AccountId, Integer> counts = new HashMap<>();
    private final Set<AccountId> pausedAll = new LinkedHashSet<>();
    private final Map<AccountId, DayValue> dayValues = new LinkedHashMap<>();
    /** Rules waiting to be evaluated, in the order they joined. Saved, so a restart loses no turn. */
    private final LinkedHashSet<UUID> pending = new LinkedHashSet<>();
    /** Items the book changed for since the last evaluation; folded into {@link #pending} then. Saved. */
    private final LinkedHashSet<String> dirtyItems = new LinkedHashSet<>();
    private long nextSequence = 1L;
    /** The tick the evaluation budget below belongs to. Not saved. */
    private long budgetTick = NO_TICK;
    private int budgetUsed;

    public RulesSection() { }

    // --- reads -------------------------------------------------------------------------------

    public synchronized TradeRule rule(UUID id) {
        Rule rule = id == null ? null : rules.get(id);
        return rule == null ? null : rule.view();
    }

    /** The account's own rules, oldest first. */
    public synchronized List<TradeRule> rulesOf(AccountId owner) {
        List<TradeRule> out = new ArrayList<>();
        for (Rule rule : rules.values()) if (rule.owner.equals(owner)) out.add(rule.view());
        return out;
    }

    public synchronized int count(AccountId owner) { return counts.getOrDefault(owner, 0); }
    public synchronized int size() { return rules.size(); }

    /** Whether the account has switched all its rules off. */
    public synchronized boolean pausedAll(AccountId owner) { return owner != null && pausedAll.contains(owner); }

    /** How many rules are waiting for their turn to be evaluated. */
    public synchronized int waiting() { return pending.size(); }

    /** Whether anything is waiting: a rule in the queue, or a book change not yet folded into it. */
    public synchronized boolean hasWork() { return !pending.isEmpty() || !dirtyItems.isEmpty(); }

    /** What the account's rules have filled today, in credits, against {@code ruleDailyValueCap}. */
    public synchronized long valueToday(AccountId owner, long now) {
        DayValue value = dayValues.get(owner);
        return value == null || value.day() != EconomySavedData.economyDay(now) ? 0L : value.value();
    }

    /** The unallocated budget of every buy rule: its share of what the escrow account holds. */
    @Override public synchronized long escrowObligations() {
        long total = 0L;
        for (Rule rule : rules.values()) total = EscrowMath.total(total, rule.budget);
        return total;
    }

    @Override public synchronized boolean isEmpty() {
        return rules.isEmpty() && pausedAll.isEmpty() && dayValues.isEmpty() && pending.isEmpty() && dirtyItems.isEmpty()
                && nextSequence == 1L;
    }

    // --- creating, changing and deleting -----------------------------------------------------

    /**
     * Creates a rule. A buy rule's whole budget moves into escrow here, or the rule is not
     * created; it is promised to other players, so an unvested starting balance cannot fund it.
     *
     * @param tag      whether {@code target} is an item tag (no leading {@code #})
     * @param total    a buy rule's budget in credits; a sell rule's quantity
     * @param binding  {@link #VAULT}, or a binding the environment's {@link RuleBinder} knows
     * @param presetId the id the rule is to have, or null for a fresh one
     */
    public synchronized RuleOutcome add(TradeEnv env, AccountId owner, RuleSide side, boolean tag, String target,
            long limitPrice, int perDay, long total, String binding, UUID presetId) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (store == null || env == null || !(owner instanceof PlayerAccountId) || side == null || target == null
                || target.isBlank() || target.length() > Lot.MAX_ITEM_LENGTH || limitPrice < 1L || perDay < 1
                || perDay > BookSection.MAX_ORDER_QUANTITY || total < 1L || binding == null || binding.isBlank()
                || binding.length() > MAX_BINDING_LENGTH || (side == RuleSide.SELL && total > MAX_TOTAL_QUANTITY)
                || (presetId != null && (rules.containsKey(presetId) || byOrder.containsKey(presetId))))
            return RuleOutcome.refused(RuleResult.INVALID);
        if (tag) {
            if (!env.items().tagKnown(target)) return RuleOutcome.refused(RuleResult.UNKNOWN_TAG);
        } else {
            if (!env.items().known(target)) return RuleOutcome.refused(RuleResult.UNKNOWN_ITEM);
            if (!env.items().tradeable(target)) return RuleOutcome.refused(RuleResult.UNTRADEABLE);
        }
        if (!VAULT.equals(binding) && (env.ruleBinder() == null || env.ruleBinder().resolve(store, env, owner, binding) == null))
            return RuleOutcome.refused(RuleResult.UNKNOWN_BINDING);
        if (!MarketLimits.withinCap(count(owner), 1, env.settings().maxRules())) return RuleOutcome.refused(RuleResult.LIMIT_REACHED);
        long now = store.ledger().now();
        boolean buys = side == RuleSide.BUY;
        if (buys) {
            LedgerResult escrowed = store.execute(LedgerPlan.of(owner, SystemAccountId.ESCROW, total,
                    TransactionType.MARKET_ESCROW));
            if (!escrowed.ok()) return RuleOutcome.refused(reason(escrowed));
        }
        Rule rule = new Rule(presetId != null ? presetId : freshId(), owner, side, tag, target, limitPrice, perDay, total,
                binding, nextSequence++, now);
        rule.day = EconomySavedData.economyDay(now);
        rule.budget = buys ? total : 0L;
        put(rule);
        pending.add(rule.id);
        setDirty();
        return new RuleOutcome(RuleResult.OK, rule.view(), buys ? total : 0L, 0L, false);
    }

    /**
     * Raises a rule's total: more budget for a buy rule, escrowed here and now, or more quantity
     * for a sell rule.
     */
    public synchronized RuleOutcome raise(TradeEnv env, AccountId owner, UUID id, long extra) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (store == null || env == null || extra < 1L) return RuleOutcome.refused(RuleResult.INVALID);
        Rule rule = own(owner, id);
        if (rule == null) return RuleOutcome.refused(RuleResult.NOT_FOUND);
        if (Long.MAX_VALUE - rule.total < extra || (rule.side == RuleSide.SELL && rule.total + extra > MAX_TOTAL_QUANTITY))
            return RuleOutcome.refused(RuleResult.INVALID);
        boolean buys = rule.side == RuleSide.BUY;
        if (buys) {
            LedgerResult escrowed = store.execute(LedgerPlan.of(owner, SystemAccountId.ESCROW, extra,
                    TransactionType.MARKET_ESCROW));
            if (!escrowed.ok()) return RuleOutcome.refused(reason(escrowed));
            rule.budget += extra;
        }
        rule.total += extra;
        pending.add(rule.id);
        setDirty();
        return new RuleOutcome(RuleResult.OK, rule.view(), buys ? extra : 0L, 0L, false);
    }

    /**
     * Deletes a rule: its order leaves the book — a sell order's goods go back to the rule's
     * inventory — and whatever is left of a buy rule's budget is paid back to the owner's balance.
     */
    public synchronized RuleOutcome delete(TradeEnv env, AccountId owner, UUID id) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (store == null || env == null) return RuleOutcome.refused(RuleResult.INVALID);
        Rule rule = own(owner, id);
        if (rule == null) return RuleOutcome.refused(RuleResult.NOT_FOUND);
        withdraw(env, store, rule, inventory(env, store, rule));
        long refund = rule.budget;
        boolean toClaim = false;
        if (refund > 0L && !store.execute(LedgerPlan.of(SystemAccountId.ESCROW, owner, refund,
                TransactionType.ESCROW_REFUND)).ok()) {
            UUID player = Accounts.player(owner);
            if (player == null || !store.market().addCreditClaim(player, refund, TransactionType.ESCROW_REFUND)) {
                // The budget stays in escrow under the rule, which is kept, switched off, until it can be paid back.
                rule.paused = true;
                rule.status = RuleStatus.PAUSED;
                setDirty();
                return RuleOutcome.refused(RuleResult.REFUND_BLOCKED);
            }
            toClaim = true;
        }
        rule.budget = 0L;
        TradeRule view = rule.view();
        remove(rule);
        setDirty();
        return new RuleOutcome(RuleResult.OK, view, 0L, refund, toClaim);
    }

    /** Pauses or resumes one rule. A paused rule's order leaves the book at once. */
    public synchronized RuleOutcome setPaused(TradeEnv env, AccountId owner, UUID id, boolean paused) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (store == null || env == null) return RuleOutcome.refused(RuleResult.INVALID);
        Rule rule = own(owner, id);
        if (rule == null) return RuleOutcome.refused(RuleResult.NOT_FOUND);
        rule.paused = paused;
        if (paused) {
            withdraw(env, store, rule, inventory(env, store, rule));
            rule.status = RuleStatus.PAUSED;
        } else pending.add(rule.id);
        setDirty();
        return RuleOutcome.of(rule.view());
    }

    /**
     * The "pause all my rules" switch. Switched on, every order the account's rules keep leaves
     * the book at once; switched off, each rule is looked at again in its turn.
     *
     * @return how many rules the account has
     */
    public synchronized int setPausedAll(TradeEnv env, AccountId owner, boolean paused) {
        EconomyStore store = owner();
        if (store == null || env == null || owner == null || EconomyHealth.frozen()) return 0;
        if (!(paused ? pausedAll.add(owner) : pausedAll.remove(owner))) return count(owner);
        int touched = 0;
        for (Rule rule : rules.values()) {
            if (!rule.owner.equals(owner)) continue;
            touched++;
            if (paused) {
                withdraw(env, store, rule, inventory(env, store, rule));
                rule.status = RuleStatus.PAUSED;
            } else pending.add(rule.id);
        }
        setDirty();
        return touched;
    }

    private Rule own(AccountId owner, UUID id) {
        Rule rule = id == null || owner == null ? null : rules.get(id);
        return rule == null || !rule.owner.equals(owner) ? null : rule;
    }

    private static RuleResult reason(LedgerResult result) {
        return switch (result) {
            case FROZEN -> RuleResult.FROZEN;
            case FUNDS_LOCKED -> RuleResult.FUNDS_LOCKED;
            case INSUFFICIENT_FUNDS -> RuleResult.INSUFFICIENT_FUNDS;
            default -> RuleResult.INVALID;
        };
    }

    // --- evaluation --------------------------------------------------------------------------

    /** Puts every rule in the queue: what a simulation step does. A rule already waiting keeps its place. */
    public synchronized void enqueueAll() {
        boolean added = false;
        for (UUID id : rules.keySet()) added |= pending.add(id);
        if (added) setDirty();
    }

    /**
     * Puts one rule in the queue: what an Auto-Trader does when its slots change, or when its
     * chunk loads or unloads. A rule already waiting keeps its place; an unknown id does nothing.
     */
    public synchronized void enqueue(UUID id) {
        if (id != null && rules.containsKey(id) && pending.add(id)) setDirty();
    }

    /** The rule of {@code owner} that works from {@code binding}, or null. A block is bound to at most one. */
    public synchronized TradeRule boundTo(AccountId owner, String binding) {
        if (owner == null || binding == null) return null;
        for (Rule rule : rules.values())
            if (rule.owner.equals(owner) && rule.binding.equals(binding)) return rule.view();
        return null;
    }

    /** Every rule that works from {@code binding}, whoever owns it: for a block that has lost its owner. */
    public synchronized List<TradeRule> boundTo(String binding) {
        List<TradeRule> out = new ArrayList<>();
        if (binding == null) return out;
        for (Rule rule : rules.values()) if (rule.binding.equals(binding)) out.add(rule.view());
        return out;
    }

    /**
     * Evaluates rules from the head of the queue, at most {@code ruleEvaluationsPerTick} in one
     * tick however often it is called in that tick; the rest keep their place for the next one.
     *
     * @param tickId identifies the tick the budget is counted in: the server's tick count in game
     * @return how many rules were evaluated
     */
    public synchronized int evaluatePending(TradeEnv env, long tickId) {
        EconomyStore store = owner();
        // While the market is closed no rule trades; each keeps its place for when it opens.
        if (store == null || env == null || EconomyHealth.frozen() || marketClosed()) return 0;
        foldChanges(env);
        if (tickId != budgetTick) {
            budgetTick = tickId;
            budgetUsed = 0;
        }
        int allowed = Math.max(0, env.settings().ruleEvaluationsPerTick());
        int evaluated = 0;
        // Only what was waiting when the call began: a rule that queues itself again by acting
        // waits for the next call, so one call can never loop.
        List<UUID> turn = new ArrayList<>(pending);
        long now = store.ledger().now();
        for (UUID id : turn) {
            if (budgetUsed >= allowed) break;
            pending.remove(id);
            Rule rule = rules.get(id);
            if (rule == null) continue;
            budgetUsed++;
            evaluated++;
            evaluate(env, store, rule, now);
        }
        if (evaluated > 0 || !turn.isEmpty()) setDirty();
        return evaluated;
    }

    /** Queues every rule that trades an item the book changed for. */
    private void foldChanges(TradeEnv env) {
        if (dirtyItems.isEmpty()) return;
        for (String item : dirtyItems) {
            Set<UUID> direct = byItem.get(item);
            if (direct != null) pending.addAll(direct);
            if (byTag.isEmpty()) continue;
            // Sorted, so the queue's order never depends on how a set happens to iterate.
            for (String tag : new TreeSet<>(env.items().tagsOf(item))) {
                Set<UUID> tagged = byTag.get(tag);
                if (tagged != null) pending.addAll(tagged);
            }
        }
        dirtyItems.clear();
        setDirty();
    }

    private void evaluate(TradeEnv env, EconomyStore store, Rule rule, long now) {
        long today = EconomySavedData.economyDay(now);
        rollDay(rule, today);
        RuleInventory inventory = inventory(env, store, rule);
        RuleStatus status;
        if (rule.paused || pausedAll.contains(rule.owner)) {
            withdraw(env, store, rule, inventory);
            status = RuleStatus.PAUSED;
        } else if (!inventory.reachable()) {
            // A rule never loads a chunk: it takes its order off the book and waits.
            withdraw(env, store, rule, inventory);
            status = RuleStatus.UNREACHABLE;
        } else if (valueLeft(env, rule.owner, today) <= 0L) {
            withdraw(env, store, rule, inventory);
            status = RuleStatus.DAILY_CAP;
        } else if (rule.side == RuleSide.SELL) status = keepSelling(env, store, rule, inventory, today, now);
        else if (rule.tag) status = sweep(env, store, rule, inventory, today, now);
        else status = keepBuying(env, store, rule, inventory, today, now);
        setStatus(env, rule, status);
    }

    /** An item buy rule: one resting buy order for what is left of today's quantity and the budget. */
    private RuleStatus keepBuying(TradeEnv env, EconomyStore store, Rule rule, RuleInventory inventory, long today,
            long now) {
        BookSection book = store.book();
        if (rule.orderId != null) {
            if (book.buy(rule.orderId) != null) return resting(bestAsk(book, rule, now), rule.limitPrice, true);
            unlink(rule);
        }
        int todayLeft = rule.perDay - rule.filledToday;
        if (todayLeft <= 0) return RuleStatus.RUNNING;
        long fee = Fees.flat(env.settings().listingFee());
        if (rule.budget < fee || rule.budget - fee < rule.limitPrice) return RuleStatus.OUT_OF_BUDGET;
        long room = inventory.room(rule.target);
        if (room <= 0L) return RuleStatus.STORAGE_FULL;
        long quantity = Math.min(Math.min(todayLeft, (rule.budget - fee) / rule.limitPrice),
                Math.min(room, Math.min(valueLeft(env, rule.owner, today) / rule.limitPrice, BookSection.MAX_ORDER_QUANTITY)));
        if (quantity <= 0L) return RuleStatus.DAILY_CAP;
        UUID orderId = freshId();
        link(rule, orderId);
        Placed placed = book.placeBuy(env, rule.owner, rule.target, (int) quantity, rule.limitPrice, OrderSource.RULE,
                true, receiver(env, store, rule, inventory), orderId);
        if (!placed.ok()) {
            unlink(rule);
            return refused(placed.result());
        }
        // The order's escrow and its listing fee came out of the budget; what the fills did not
        // use was put back by the book as they happened.
        rule.budget -= placed.escrowed() + placed.listingFee();
        if (placed.rested() == 0) {
            unlink(rule);
            return RuleStatus.RUNNING;
        }
        return placed.filled() > 0 ? RuleStatus.RUNNING : resting(bestAsk(book, rule, now), rule.limitPrice, true);
    }

    /** A tag buy rule: nothing rests on the book; it takes crossing sell orders, cheapest first. */
    private RuleStatus sweep(TradeEnv env, EconomyStore store, Rule rule, RuleInventory inventory, long today, long now) {
        BookSection book = store.book();
        int todayLeft = rule.perDay - rule.filledToday;
        if (todayLeft <= 0) return RuleStatus.RUNNING;
        if (rule.budget < 1L) return RuleStatus.OUT_OF_BUDGET;
        List<SellOrder> candidates = new ArrayList<>();
        boolean dearer = false;
        for (String item : new TreeSet<>(book.itemsOnOffer())) {
            if (!env.items().inTag(item, rule.target) || !env.items().tradeable(item)) continue;
            for (SellOrder ask : book.asks(item)) {
                if (ask.owner().equals(rule.owner) || ask.expiresAt() <= now) continue;
                if (ask.unitPrice() > rule.limitPrice) {
                    dearer = true;
                    break;
                }
                candidates.add(ask);
            }
        }
        candidates.sort(Comparator.comparingLong(SellOrder::unitPrice).thenComparingLong(SellOrder::sequence)
                .thenComparing(SellOrder::id));
        boolean bought = false;
        boolean full = false;
        boolean affordable = false;
        int fills = 0;
        for (SellOrder ask : candidates) {
            if (todayLeft <= 0 || fills >= MAX_SWEEP_FILLS) break;
            long room = inventory.room(ask.item());
            if (room <= 0L) {
                full = true;
                continue;
            }
            long quantity = Math.min(Math.min(todayLeft, ask.lot().count()), Math.min(room,
                    Math.min(rule.budget / ask.unitPrice(), valueLeft(env, rule.owner, today) / ask.unitPrice())));
            if (quantity <= 0L) continue;
            affordable = true;
            Traded traded = book.sweep(env, rule.owner, rule.id, ask.id(), (int) quantity, rule.budget,
                    receiver(env, store, rule, inventory));
            if (!traded.ok()) continue;
            rule.budget -= traded.fill().buyerPaid();
            todayLeft -= traded.fill().quantity();
            fills++;
            bought = true;
        }
        if (bought) return RuleStatus.RUNNING;
        if (valueLeft(env, rule.owner, today) <= 0L) return RuleStatus.DAILY_CAP;
        if (!candidates.isEmpty() && !affordable) return full ? RuleStatus.STORAGE_FULL : RuleStatus.OUT_OF_BUDGET;
        return dearer && candidates.isEmpty() ? RuleStatus.PRICE_NOT_MET : RuleStatus.RUNNING;
    }

    /** A sell rule: one resting sell order of what its inventory holds, up to today's quantity. */
    private RuleStatus keepSelling(TradeEnv env, EconomyStore store, Rule rule, RuleInventory inventory, long today,
            long now) {
        BookSection book = store.book();
        if (rule.orderId != null) {
            if (book.sell(rule.orderId) != null) return resting(bestBid(book, rule, now), rule.limitPrice, false);
            unlink(rule);
        }
        long totalLeft = rule.total - rule.done;
        if (totalLeft <= 0L) return RuleStatus.FILLED;
        int todayLeft = rule.perDay - rule.filledToday;
        if (todayLeft <= 0) return RuleStatus.RUNNING;
        String item = rule.tag ? firstHeld(env, rule, inventory) : rule.target;
        long available = item == null ? 0L : inventory.available(item);
        if (available <= 0L) return RuleStatus.NOTHING_TO_SELL;
        long quantity = Math.min(Math.min(available, todayLeft), Math.min(totalLeft,
                Math.min(valueLeft(env, rule.owner, today) / rule.limitPrice, BookSection.MAX_ORDER_QUANTITY)));
        if (quantity <= 0L) return RuleStatus.DAILY_CAP;
        UUID orderId = freshId();
        link(rule, orderId);
        Placed placed = book.placeSell(env, rule.owner, Lot.of(item, (int) quantity), rule.limitPrice, OrderSource.RULE,
                origin(env, store, rule, inventory), orderId);
        if (!placed.ok()) {
            unlink(rule);
            return refused(placed.result());
        }
        if (placed.rested() == 0) {
            unlink(rule);
            return rule.total - rule.done <= 0L ? RuleStatus.FILLED : RuleStatus.RUNNING;
        }
        return placed.filled() > 0 ? RuleStatus.RUNNING : resting(bestBid(book, rule, now), rule.limitPrice, false);
    }

    /** The first item of the rule's tag that its inventory holds, by item id; null when it holds none. */
    private static String firstHeld(TradeEnv env, Rule rule, RuleInventory inventory) {
        for (Map.Entry<String, Long> held : new java.util.TreeMap<>(inventory.goods()).entrySet())
            if (held.getValue() > 0L && env.items().inTag(held.getKey(), rule.target) && env.items().tradeable(held.getKey()))
                return held.getKey();
        return null;
    }

    /** The status of a rule whose order rests: waiting on a price only when the other side is there at a worse one. */
    private static RuleStatus resting(long bestOther, long limit, boolean buying) {
        if (bestOther < 0L) return RuleStatus.RUNNING;
        return (buying ? bestOther > limit : bestOther < limit) ? RuleStatus.PRICE_NOT_MET : RuleStatus.RUNNING;
    }

    /** The cheapest open sell order of the rule's item that is not the owner's own; -1 for none. */
    private static long bestAsk(BookSection book, Rule rule, long now) {
        for (SellOrder ask : book.asks(rule.target))
            if (!ask.owner().equals(rule.owner) && ask.expiresAt() > now) return ask.unitPrice();
        return -1L;
    }

    /** The highest open buy order of the rule's order's item that is not the owner's own; -1 for none. */
    private static long bestBid(BookSection book, Rule rule, long now) {
        SellOrder own = book.sell(rule.orderId);
        if (own == null) return -1L;
        for (BuyOrder bid : book.bids(own.item()))
            if (!bid.owner().equals(rule.owner) && bid.expiresAt() > now) return bid.unitPrice();
        return -1L;
    }

    /** What a refused order means for the rule. */
    private static RuleStatus refused(BookResult result) {
        return switch (result) {
            case LIMIT_REACHED -> RuleStatus.ORDER_LIMIT;
            case STORAGE_FULL -> RuleStatus.STORAGE_FULL;
            case NOT_HOLDING, UNTRADEABLE, UNKNOWN_ITEM -> RuleStatus.NOTHING_TO_SELL;
            case FEE_UNPAID, INSUFFICIENT_FUNDS, FUNDS_LOCKED -> RuleStatus.OUT_OF_BUDGET;
            default -> RuleStatus.RUNNING;
        };
    }

    private void setStatus(TradeEnv env, Rule rule, RuleStatus status) {
        if (rule.status == status) return;
        rule.status = status;
        setDirty();
        EconomyAlerts.Kind alert = status.alert();
        if (alert != null) env.alerts().raise(rule.owner, alert);
    }

    // --- the rule's order and its inventory --------------------------------------------------

    /** Takes the rule's order off the book: its escrow is budget again, its goods go back to the inventory. */
    private void withdraw(TradeEnv env, EconomyStore store, Rule rule, RuleInventory inventory) {
        UUID orderId = rule.orderId;
        if (orderId == null) return;
        unlink(rule);
        BookSection.Released released = store.book().release(orderId);
        if (released == null) return;
        rule.budget += released.escrow();
        if (released.goods() != null) putBack(env, store, rule, inventory, released.goods());
        setDirty();
    }

    /** Goods for the rule's inventory; when that cannot be reached they go to the owner's vault, never nowhere. */
    private static void putBack(TradeEnv env, EconomyStore store, Rule rule, RuleInventory inventory, Lot goods) {
        if (inventory.reachable()) inventory.put(goods);
        else VaultGoods.deliver(store, env, rule.owner, goods);
    }

    private static RuleInventory inventory(TradeEnv env, EconomyStore store, Rule rule) {
        if (VAULT.equals(rule.binding)) return new VaultRuleInventory(store, env, rule.owner);
        RuleBinder binder = env.ruleBinder();
        RuleInventory bound = binder == null ? null : binder.resolve(store, env, rule.owner, rule.binding);
        return bound == null ? NOWHERE : bound;
    }

    private static GoodsReceiver receiver(TradeEnv env, EconomyStore store, Rule rule, RuleInventory inventory) {
        return new GoodsReceiver() {
            @Override public boolean canTake(Lot lot) { return inventory.reachable() && inventory.room(lot.item()) >= lot.count(); }
            @Override public boolean give(Lot lot) {
                putBack(env, store, rule, inventory, lot);
                return true;
            }
        };
    }

    private static GoodsOrigin origin(TradeEnv env, EconomyStore store, Rule rule, RuleInventory inventory) {
        return new GoodsOrigin() {
            @Override public boolean holds(Lot lot) { return lot.pristine() && inventory.available(lot.item()) >= lot.count(); }
            @Override public boolean take(Lot lot) { return inventory.take(lot); }
            @Override public void restore(Lot lot) { putBack(env, store, rule, inventory, lot); }
        };
    }

    private void link(Rule rule, UUID orderId) {
        rule.orderId = orderId;
        byOrder.put(orderId, rule.id);
    }

    private void unlink(Rule rule) {
        if (rule.orderId != null) byOrder.remove(rule.orderId);
        rule.orderId = null;
    }

    // --- days and the daily value cap --------------------------------------------------------

    private void rollDay(Rule rule, long today) {
        // Only ever onward: a simulation step replayed after a pause runs at its own, earlier time,
        // and what the rule filled since then must not be forgotten for it.
        if (today <= rule.day) return;
        rule.day = today;
        rule.filledToday = 0;
        setDirty();
    }

    /** What the account's rules may still fill today, in credits. */
    private long valueLeft(TradeEnv env, AccountId owner, long today) {
        DayValue used = dayValues.get(owner);
        // A day before the one recorded is counted in the recorded one (a replayed step), never afresh.
        long spent = used == null || used.day() < today ? 0L : used.value();
        return Math.max(0L, env.settings().ruleDailyValueCap() - spent);
    }

    private void addValue(AccountId owner, long today, long value) {
        DayValue used = dayValues.get(owner);
        long before = used == null || used.day() < today ? 0L : used.value();
        long day = used == null ? today : Math.max(today, used.day());
        dayValues.put(owner, new DayValue(day, Long.MAX_VALUE - before < value ? Long.MAX_VALUE : before + value));
    }

    // --- what the book tells the rules -------------------------------------------------------

    /** The rule an order id stands for: the rule's order, or the rule itself for a sweep. */
    private Rule byReference(UUID orderId) {
        if (orderId == null) return null;
        UUID ruleId = byOrder.get(orderId);
        return rules.get(ruleId != null ? ruleId : orderId);
    }

    @Override public synchronized int allowance(TradeEnv env, UUID orderId, long unitPrice, int wanted, long now) {
        Rule rule = byReference(orderId);
        if (rule == null) return wanted;
        if (unitPrice < 1L || wanted < 1) return 0;
        return (int) Math.min(wanted, valueLeft(env, rule.owner, EconomySavedData.economyDay(now)) / unitPrice);
    }

    @Override public synchronized void filled(UUID orderId, boolean buy, int quantity, long base, long paid, long refund,
            long now) {
        Rule rule = byReference(orderId);
        if (rule == null) return;
        long today = EconomySavedData.economyDay(now);
        rollDay(rule, today);
        rule.filledToday = (int) Math.min(Integer.MAX_VALUE, (long) rule.filledToday + quantity);
        if (buy) {
            rule.done = Long.MAX_VALUE - rule.done < paid ? Long.MAX_VALUE : rule.done + paid;
            rule.budget += Math.max(0L, refund);
        } else rule.done += quantity;
        addValue(rule.owner, today, base);
        // Its order may be complete, or its quantity for today: it is looked at again in its turn.
        pending.add(rule.id);
        setDirty();
    }

    @Override public synchronized boolean deliver(TradeEnv env, UUID orderId, Lot lot) {
        EconomyStore store = owner();
        Rule rule = byReference(orderId);
        if (rule == null || store == null) return false;
        putBack(env, store, rule, inventory(env, store, rule), lot);
        return true;
    }

    @Override public synchronized boolean closed(TradeEnv env, UUID orderId, long escrowBack, Lot goodsBack) {
        EconomyStore store = owner();
        UUID ruleId = orderId == null ? null : byOrder.get(orderId);
        Rule rule = ruleId == null ? null : rules.get(ruleId);
        if (rule == null || store == null) return false;
        unlink(rule);
        rule.budget += Math.max(0L, escrowBack);
        if (goodsBack != null) putBack(env, store, rule, inventory(env, store, rule), goodsBack);
        pending.add(rule.id);
        setDirty();
        return true;
    }

    @Override public synchronized boolean owns(UUID orderId, AccountId owner) {
        Rule rule = byReference(orderId);
        return rule != null && rule.owner.equals(owner);
    }

    @Override public synchronized void changed(String item) {
        if (item == null || rules.isEmpty() || (!byItem.containsKey(item) && byTag.isEmpty())) return;
        if (dirtyItems.add(item)) setDirty();
    }

    // --- erasure and export ------------------------------------------------------------------

    /**
     * Erases one player's rules. What is left of their buy rules' budgets moves to the Reserve as
     * an erasure sweep, in a row that names no player; the orders their rules kept are the order
     * book's to erase, and it does. Their pause switch and their day's total go too.
     *
     * @return the credits swept
     */
    public synchronized long forgetPlayer(UUID player) {
        if (player == null) return 0L;
        AccountId account = new PlayerAccountId(player);
        long swept = 0L;
        boolean changed = pausedAll.remove(account);
        changed |= dayValues.remove(account) != null;
        for (Rule rule : new ArrayList<>(rules.values())) {
            if (!rule.owner.equals(account)) continue;
            swept = EscrowMath.total(swept, rule.budget);
            remove(rule);
            changed = true;
        }
        EconomySavedData ledger = ledger();
        if (ledger != null && swept > 0L) ledger.sweepErasedEscrow(swept);
        if (changed) setDirty();
        return swept;
    }

    /** Data-subject export: the player's own rules, their pause switch and what their rules filled today. */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        AccountId account = Accounts.of(player);
        out.addProperty("rules_paused", pausedAll.contains(account));
        DayValue value = dayValues.get(account);
        if (value != null) {
            out.addProperty("value_day", value.day());
            out.addProperty("value_filled", value.value());
        }
        JsonArray own = new JsonArray();
        for (Rule rule : rules.values()) {
            if (!rule.owner.equals(account)) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", rule.id.toString());
            row.addProperty("side", rule.side.name());
            row.addProperty(rule.tag ? "tag" : "item", rule.target);
            row.addProperty("limit_price", rule.limitPrice);
            row.addProperty("per_day", rule.perDay);
            row.addProperty("total", rule.total);
            row.addProperty("done", rule.done);
            row.addProperty("filled_today", rule.filledToday);
            row.addProperty("budget", rule.budget);
            row.addProperty("binding", rule.binding);
            row.addProperty("paused", rule.paused);
            row.addProperty("status", rule.status.name());
            row.addProperty("created_at", rule.createdAt);
            own.add(row);
        }
        out.add("rules", own);
        return out;
    }

    // --- internals ---------------------------------------------------------------------------

    private UUID freshId() {
        UUID id;
        do id = UUID.randomUUID(); while (rules.containsKey(id) || byOrder.containsKey(id));
        return id;
    }

    private void put(Rule rule) {
        rules.put(rule.id, rule);
        counts.merge(rule.owner, 1, Integer::sum);
        (rule.tag ? byTag : byItem).computeIfAbsent(rule.target, ignored -> new LinkedHashSet<>()).add(rule.id);
        if (rule.orderId != null) byOrder.put(rule.orderId, rule.id);
    }

    private void remove(Rule rule) {
        if (rules.remove(rule.id) == null) return;
        counts.computeIfPresent(rule.owner, (owner, count) -> count <= 1 ? null : count - 1);
        Map<String, Set<UUID>> index = rule.tag ? byTag : byItem;
        Set<UUID> ids = index.get(rule.target);
        if (ids != null && ids.remove(rule.id) && ids.isEmpty()) index.remove(rule.target);
        if (rule.orderId != null) byOrder.remove(rule.orderId);
        pending.remove(rule.id);
    }

    // --- persistence -------------------------------------------------------------------------

    private record RuleRow(String id, String owner, String side, boolean tag, String target, long limit, int perDay,
            long total, String binding, boolean paused, String status, long day, int filledToday, long done,
            long budget, String order) {
        static final Codec<RuleRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(RuleRow::id),
                Codec.STRING.fieldOf("owner").forGetter(RuleRow::owner),
                Codec.STRING.fieldOf("side").forGetter(RuleRow::side),
                Codec.BOOL.optionalFieldOf("tag", false).forGetter(RuleRow::tag),
                Codec.STRING.fieldOf("target").forGetter(RuleRow::target),
                Codec.LONG.fieldOf("limit").forGetter(RuleRow::limit),
                Codec.INT.fieldOf("per_day").forGetter(RuleRow::perDay),
                Codec.LONG.fieldOf("total").forGetter(RuleRow::total),
                Codec.STRING.optionalFieldOf("binding", VAULT).forGetter(RuleRow::binding),
                Codec.BOOL.optionalFieldOf("paused", false).forGetter(RuleRow::paused),
                Codec.STRING.optionalFieldOf("status", RuleStatus.RUNNING.name()).forGetter(RuleRow::status),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(RuleRow::day),
                Codec.INT.optionalFieldOf("filled_today", 0).forGetter(RuleRow::filledToday),
                Codec.LONG.optionalFieldOf("done", 0L).forGetter(RuleRow::done),
                Codec.LONG.optionalFieldOf("budget", 0L).forGetter(RuleRow::budget),
                Codec.STRING.optionalFieldOf("order", "").forGetter(RuleRow::order))
                .apply(instance, RuleRow::new));
    }

    private record ValueRow(String account, long day, long value) {
        static final Codec<ValueRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("account").forGetter(ValueRow::account),
                Codec.LONG.fieldOf("day").forGetter(ValueRow::day),
                Codec.LONG.fieldOf("value").forGetter(ValueRow::value)).apply(instance, ValueRow::new));
    }

    /** The sequence, the creation time and the queue travel beside the rule rows; a record holds sixteen fields. */
    private record TimeRow(String id, long sequence, long created) {
        static final Codec<TimeRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(TimeRow::id),
                Codec.LONG.fieldOf("sequence").forGetter(TimeRow::sequence),
                Codec.LONG.optionalFieldOf("created", 0L).forGetter(TimeRow::created)).apply(instance, TimeRow::new));
    }

    private static Codec<RulesSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("next_sequence", 1L).forGetter(section -> section.nextSequence),
                RuleRow.CODEC.listOf().optionalFieldOf("rules", List.of()).forGetter(RulesSection::ruleRows),
                TimeRow.CODEC.listOf().optionalFieldOf("rule_times", List.of()).forGetter(RulesSection::timeRows),
                Codec.STRING.listOf().optionalFieldOf("paused_all", List.of()).forGetter(RulesSection::pausedRows),
                ValueRow.CODEC.listOf().optionalFieldOf("day_values", List.of()).forGetter(RulesSection::valueRows),
                Codec.STRING.listOf().optionalFieldOf("pending", List.of()).forGetter(RulesSection::pendingRows),
                Codec.STRING.listOf().optionalFieldOf("changed_items", List.of()).forGetter(RulesSection::dirtyRows))
                .apply(instance, RulesSection::fromRows));
    }

    private synchronized List<RuleRow> ruleRows() {
        List<RuleRow> rows = new ArrayList<>(rules.size());
        for (Rule rule : rules.values()) rows.add(new RuleRow(rule.id.toString(), rule.owner.stableKey(), rule.side.name(),
                rule.tag, rule.target, rule.limitPrice, rule.perDay, rule.total, rule.binding, rule.paused,
                rule.status.name(), rule.day, rule.filledToday, rule.done, rule.budget,
                rule.orderId == null ? "" : rule.orderId.toString()));
        return rows;
    }

    private synchronized List<TimeRow> timeRows() {
        List<TimeRow> rows = new ArrayList<>(rules.size());
        for (Rule rule : rules.values()) rows.add(new TimeRow(rule.id.toString(), rule.sequence, rule.createdAt));
        return rows;
    }

    private synchronized List<String> pausedRows() { return pausedAll.stream().map(AccountId::stableKey).toList(); }

    private synchronized List<ValueRow> valueRows() {
        List<ValueRow> rows = new ArrayList<>(dayValues.size());
        dayValues.forEach((account, value) -> rows.add(new ValueRow(account.stableKey(), value.day(), value.value())));
        return rows;
    }

    private synchronized List<String> pendingRows() { return pending.stream().map(UUID::toString).toList(); }

    private synchronized List<String> dirtyRows() { return List.copyOf(dirtyItems); }

    private static RulesSection fromRows(long nextSequence, List<RuleRow> ruleRows, List<TimeRow> timeRows,
            List<String> pausedRows, List<ValueRow> valueRows, List<String> pendingRows, List<String> dirtyRows) {
        RulesSection section = new RulesSection();
        Map<String, TimeRow> times = new HashMap<>();
        for (TimeRow row : timeRows) times.put(row.id(), row);
        long highest = Math.max(1L, nextSequence);
        // A row that is not a rule is skipped; every other rule still loads.
        for (RuleRow row : ruleRows) try {
            AccountId owner = Accounts.parse(row.owner());
            UUID id = UUID.fromString(row.id());
            if (owner == null || section.rules.containsKey(id) || row.limit() < 1L || row.perDay() < 1
                    || row.total() < 1L || row.target().isBlank()) continue;
            TimeRow time = times.get(row.id());
            Rule rule = new Rule(id, owner, RuleSide.valueOf(row.side()), row.tag(), row.target(), row.limit(),
                    row.perDay(), row.total(), row.binding().isBlank() ? VAULT : row.binding(),
                    time == null ? highest : Math.max(0L, time.sequence()), time == null ? 0L : time.created());
            rule.paused = row.paused();
            rule.status = status(row.status());
            rule.day = row.day();
            rule.filledToday = Math.max(0, row.filledToday());
            rule.done = Math.max(0L, row.done());
            rule.budget = Math.max(0L, row.budget());
            rule.orderId = row.order().isBlank() ? null : UUID.fromString(row.order());
            section.put(rule);
            highest = Math.max(highest, rule.sequence + 1L);
        } catch (IllegalArgumentException ignored) { }
        section.nextSequence = highest;
        for (String key : pausedRows) {
            AccountId account = Accounts.parse(key);
            if (account != null) section.pausedAll.add(account);
        }
        for (ValueRow row : valueRows) {
            AccountId account = Accounts.parse(row.account());
            if (account != null && row.value() >= 0L) section.dayValues.put(account, new DayValue(row.day(), row.value()));
        }
        for (String id : pendingRows) try {
            UUID rule = UUID.fromString(id);
            if (section.rules.containsKey(rule)) section.pending.add(rule);
        } catch (IllegalArgumentException ignored) { }
        for (String item : dirtyRows) if (item != null && !item.isBlank()) section.dirtyItems.add(item);
        return section;
    }

    private static RuleStatus status(String name) {
        for (RuleStatus value : RuleStatus.values()) if (value.name().equals(name)) return value;
        return RuleStatus.RUNNING;
    }

    /** Drops the day totals of earlier days; called on the day roll-over so the map never grows. */
    public synchronized void purgeDayValues(long now) {
        long today = EconomySavedData.economyDay(now);
        Iterator<DayValue> values = dayValues.values().iterator();
        boolean changed = false;
        while (values.hasNext()) if (values.next().day() < today) {
            values.remove();
            changed = true;
        }
        if (changed) setDirty();
    }
}
