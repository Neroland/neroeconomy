package za.co.neroland.neroeconomy.book;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.BudgetKind;
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
import za.co.neroland.neroeconomy.goods.LotHolder;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.MarketLimits;
import za.co.neroland.neroeconomy.market.Settlement;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * The order book section of the economy store: every open sell order and buy order, and the one
 * matching and settlement engine behind the Market Board, the Trading Terminal, the order
 * commands, the companion app and the auto-trade rules.
 *
 * <p><b>Orders.</b> A {@link SellOrder} is one lot the book holds, at a price per unit. A
 * {@link BuyOrder} is a quantity of one item at up to a price per unit, with the credits for all
 * of it in the ledger's escrow account. Both keep a stable id, a sequence number (their place in
 * the queue) and an expiry.
 *
 * <p><b>Matching.</b> An order that crosses the other side fills as it is placed: best price
 * first, then oldest first, in parts if need be, at the <em>resting</em> order's price. Only
 * unmodified lots match buy orders; a modified lot is listed and can be bought directly. An
 * account never matches itself: its own orders on the other side are passed over, not waited on.
 * A buyer whose limit was above the price gets the difference back from escrow in the same
 * operation.
 *
 * <p><b>Price modifiers</b> change the buyer's price only ({@code BuyerPricing}). A sell order
 * crosses a buy order when the seller's price is within the buyer's limit <em>and</em> the buyer's
 * modified price is too; a discount is paid by the Reserve — never more than the market fee the
 * same sale pays it, and skipped when it cannot pay — and a surcharge goes to it. When the buy order is the resting one and a surcharge applies, the sale
 * settles at the highest seller's price that keeps the buyer within their limit. Orders of system
 * accounts and orders placed by rules are never given a discount.
 *
 * <p><b>Settlement.</b> Every fill is one ledger plan ({@link #settle}): the buyer's credits, from
 * their order's escrow or from their balance, become the seller's credit claim less the market
 * fee, which goes to the Reserve. There is one implementation; matching, buying from a named sell
 * order and filling a named buy order all go through it. Goods go to the buyer's inventory when
 * they placed the order in game and it fits, and to their vault otherwise.
 *
 * <p><b>Bounds.</b> Per-account caps on open orders ({@code maxListingsPerPlayer},
 * {@code maxBuyOffersPerPlayer}), a largest order quantity, and exact arithmetic throughout.
 * <b>Purge.</b> {@link #expire}. <b>Erasure.</b> {@link #forgetPlayer}: the erased player's orders
 * go, their goods are destroyed and their escrow moves to the Reserve. <b>Export.</b>
 * {@link #exportFor}.
 *
 * <p>The section holds lots, never item stacks, so it loads, saves and is tested without the
 * game's registries. It only works as part of a store: its credits are in the store's ledger, its
 * sellers' proceeds are credit claims in the market section and its buyers' goods go to the vault.
 */
public final class BookSection extends EconomySection implements LotHolder, EscrowHolder {
    public static final Codec<BookSection> CODEC = codec();
    /** The most one order may sell or ask for: thirty-six stacks. */
    public static final int MAX_ORDER_QUANTITY = 2304;
    /** How many recent changes are remembered for {@link #changedSince}; older ones count as "anything". */
    private static final int CHANGE_LOG = 512;

    private static final Comparator<SellOrder> ASKS = Comparator.comparingLong(SellOrder::unitPrice)
            .thenComparingLong(SellOrder::sequence).thenComparing(SellOrder::id);
    private static final Comparator<BuyOrder> BIDS = Comparator.comparingLong(BuyOrder::unitPrice).reversed()
            .thenComparingLong(BuyOrder::sequence).thenComparing(BuyOrder::id);

    /** One side of a fill: who, through which order, placed how. */
    private record Party(AccountId account, UUID orderId, OrderSource source) {
        boolean rule() { return source == OrderSource.RULE; }
    }

    /** One remembered change: the revision it made, and the item it was about. */
    private record Change(long revision, String item) { }

    /** What an order held when it was taken off the book for its rule. */
    public record Released(long escrow, Lot goods) { }

    private final Map<UUID, SellOrder> sells = new LinkedHashMap<>();
    private final Map<UUID, BuyOrder> buys = new LinkedHashMap<>();
    /** Matchable sell orders per item, cheapest and oldest first. */
    private final Map<String, TreeSet<SellOrder>> asks = new HashMap<>();
    /** Buy orders per item, highest and oldest first. */
    private final Map<String, TreeSet<BuyOrder>> bids = new HashMap<>();
    private final Map<AccountId, Integer> sellCounts = new HashMap<>();
    private final Map<AccountId, Integer> buyCounts = new HashMap<>();
    private long nextSequence = 1L;
    /** Bumped on every change to the book, so an open menu knows when to look again. Not saved. */
    private long revision;
    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    /** What was filled since the exchange last asked. Not saved; see {@link TradedVolume}. */
    private final TradedVolume volume = new TradedVolume();

    public BookSection() { }

    // --- reads -------------------------------------------------------------------------------

    public synchronized SellOrder sell(UUID id) { return id == null ? null : sells.get(id); }
    public synchronized BuyOrder buy(UUID id) { return id == null ? null : buys.get(id); }
    public synchronized int sellCount(AccountId owner) { return sellCounts.getOrDefault(owner, 0); }
    public synchronized int buyCount(AccountId owner) { return buyCounts.getOrDefault(owner, 0); }
    public synchronized int size() { return sells.size() + buys.size(); }

    /** Every account that has an open order, in the order their oldest orders were placed. */
    public synchronized Set<AccountId> owners() {
        Set<AccountId> out = new java.util.LinkedHashSet<>();
        for (SellOrder order : sells.values()) out.add(order.owner());
        for (BuyOrder order : buys.values()) out.add(order.owner());
        return out;
    }

    /** The account's own open sell orders, oldest first. */
    public synchronized List<SellOrder> sellsOf(AccountId owner) {
        List<SellOrder> out = new ArrayList<>();
        for (SellOrder order : sells.values()) if (order.owner().equals(owner)) out.add(order);
        return out;
    }

    /** The account's own open buy orders, oldest first. */
    public synchronized List<BuyOrder> buysOf(AccountId owner) {
        List<BuyOrder> out = new ArrayList<>();
        for (BuyOrder order : buys.values()) if (order.owner().equals(owner)) out.add(order);
        return out;
    }

    /** Every open sell order whose item id passes {@code item} and that has not lapsed, by item, price, age. */
    public synchronized List<SellOrder> sellsMatching(Predicate<String> item, long now) {
        List<SellOrder> out = new ArrayList<>();
        for (SellOrder order : sells.values())
            if (order.expiresAt() > now && item.test(order.item())) out.add(order);
        out.sort(Comparator.comparing(SellOrder::item).thenComparing(ASKS));
        return out;
    }

    /** Every open buy order whose item id passes {@code item} and that has not lapsed, by item, price, age. */
    public synchronized List<BuyOrder> buysMatching(Predicate<String> item, long now) {
        List<BuyOrder> out = new ArrayList<>();
        for (BuyOrder order : buys.values())
            if (order.expiresAt() > now && item.test(order.item())) out.add(order);
        out.sort(Comparator.comparing(BuyOrder::item).thenComparing(BIDS));
        return out;
    }

    /** The matchable sell orders for one item, cheapest and oldest first. */
    public synchronized List<SellOrder> asks(String item) {
        TreeSet<SellOrder> side = asks.get(item);
        return side == null ? List.of() : List.copyOf(side);
    }

    /** The buy orders for one item, highest and oldest first. */
    public synchronized List<BuyOrder> bids(String item) {
        TreeSet<BuyOrder> side = bids.get(item);
        return side == null ? List.of() : List.copyOf(side);
    }

    /** Every item id that has a matchable sell order. */
    public synchronized Set<String> itemsOnOffer() { return Set.copyOf(asks.keySet()); }

    @Override public synchronized void forEachLot(Consumer<Lot> visitor) {
        for (SellOrder order : sells.values()) visitor.accept(order.lot());
    }

    /** The escrow behind every open buy order. */
    @Override public synchronized long escrowObligations() {
        long total = 0L;
        for (BuyOrder order : buys.values()) total = EscrowMath.total(total, order.escrow());
        return total;
    }

    @Override public synchronized boolean isEmpty() { return sells.isEmpty() && buys.isEmpty() && nextSequence == 1L; }

    // --- live updates ------------------------------------------------------------------------

    /** Changes each time the book changes; never the same twice in one session. */
    public synchronized long revision() { return revision; }

    /**
     * Whether anything about an item passing {@code item} changed after {@code since}. A menu
     * keeps the revision it last showed and asks this once a tick; when the answer is yes it sends
     * its page again. Changes older than the book remembers count as a change to everything.
     */
    public synchronized boolean changedSince(long since, Predicate<String> item) {
        if (since >= revision) return false;
        Change oldest = changes.peekFirst();
        if (oldest == null || oldest.revision() > since + 1L) return true;
        for (Change change : changes) if (change.revision() > since && item.test(change.item())) return true;
        return false;
    }

    /**
     * The items that changed after {@code since}, or null when more changed than the book
     * remembers and everything has to be looked at again.
     */
    public synchronized Set<String> changedItemsSince(long since) {
        if (since >= revision) return Set.of();
        Change oldest = changes.peekFirst();
        if (oldest == null || oldest.revision() > since + 1L) return null;
        Set<String> items = new HashSet<>();
        for (Change change : changes) if (change.revision() > since) items.add(change.item());
        return items;
    }

    // --- traded volume -----------------------------------------------------------------------

    /**
     * What was filled on the book since this was last called, per item: how many changed hands and
     * for how many credits (the sellers' side of each fill). The tally is emptied by the call, so
     * it has exactly one reader: the exchange's price step, once per simulation step.
     *
     * <p>Two caps are applied to what is counted (see {@link TradedVolume}): fills between the same
     * two player accounts count up to {@code pairCap} credits and one player account's fills up to
     * {@code accountCap}. A self-trade is never in it, because an account never matches itself.
     * The result names items only.
     */
    public synchronized Map<String, ItemVolume> drainTradedVolume(long pairCap, long accountCap) {
        return volume.drain(Math.max(0L, pairCap), Math.max(0L, accountCap));
    }

    private void touch(String item) {
        changes.addLast(new Change(++revision, item));
        if (changes.size() > CHANGE_LOG) changes.removeFirst();
        setDirty();
        // Rules that trade this item are looked at again on the next tick.
        EconomyStore store = owner();
        RuleOrders rules = store == null ? null : store.ruleOrders();
        if (rules != null) rules.changed(item);
    }

    // --- placing a sell order ----------------------------------------------------------------

    /**
     * Places a sell order: {@code lot} leaves {@code origin}, fills whatever buy orders it crosses
     * at their prices, and what is left rests on the book.
     *
     * <p>Refused before anything moves when the owner is at their cap, does not hold the goods,
     * cannot pay the listing fee, or could not be credited the proceeds.
     *
     * @param presetId the id the order is to have, or null for a fresh one
     */
    public synchronized Placed placeSell(TradeEnv env, AccountId owner, Lot lot, long unitPrice, OrderSource source,
            GoodsOrigin origin, UUID presetId) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return Placed.refused(BookResult.FROZEN);
        if (store == null || env == null || owner == null || lot == null || source == null || origin == null
                || unitPrice < 1L || lot.count() > MAX_ORDER_QUANTITY || !mayOwn(env, owner, source)
                || overflows(unitPrice, lot.count()) || taken(presetId) || !ruleOwned(store, source, presetId, owner))
            return Placed.refused(BookResult.INVALID);
        boolean player = owner instanceof PlayerAccountId;
        if (player && !MarketLimits.withinCap(sellCount(owner), 1, env.settings().maxSellOrders()))
            return Placed.refused(BookResult.LIMIT_REACHED);
        if (!env.items().tradeable(lot.item())) return Placed.refused(BookResult.UNTRADEABLE);
        if (!origin.holds(lot)) return Placed.refused(BookResult.NOT_HOLDING);
        // The most the order could earn at once has to fit the seller's claim before anything moves.
        if (player && !canBook(store, owner, unitPrice * lot.count())) return Placed.refused(BookResult.PAYOUT_BLOCKED);
        if (!origin.take(lot)) return Placed.refused(BookResult.NOT_HOLDING);
        // The listing fee is charged after every other check and is not refunded.
        long fee = player ? Fees.flat(env.settings().listingFee()) : 0L;
        if (fee > 0L) {
            LedgerResult charged = store.execute(LedgerPlan.of(owner, SystemAccountId.RESERVE, fee,
                    TransactionType.FEE_LISTING));
            if (!charged.ok()) {
                origin.restore(lot);
                return Placed.refused(charged == LedgerResult.FROZEN ? BookResult.FROZEN : BookResult.FEE_UNPAID);
            }
        }
        long now = store.ledger().now();
        UUID id = presetId != null ? presetId : freshId();
        List<Fill> fills = new ArrayList<>();
        int left = lot.count();
        if (lot.pristine()) left = matchSell(env, store, new Party(owner, id, source), lot.item(), unitPrice, left, now, fills);
        if (left > 0) putSell(new SellOrder(id, owner, lot.withCount(left), unitPrice, nextSequence++,
                expiry(env, now), source));
        touch(lot.item());
        return new Placed(BookResult.OK, id, left, List.copyOf(fills), fee, 0L, false);
    }

    /** Fills {@code left} units of an incoming sell order against the bids, best first. @return what is left */
    private int matchSell(TradeEnv env, EconomyStore store, Party seller, String item, long ask, int left, long now,
            List<Fill> fills) {
        TreeSet<BuyOrder> side = bids.get(item);
        if (side == null) return left;
        List<BuyOrder> crossing = new ArrayList<>();
        // Bids are sorted highest first, so the first one below the ask ends the walk.
        for (BuyOrder bid : side) {
            if (bid.unitPrice() < ask) break;
            crossing.add(bid);
        }
        for (BuyOrder bid : crossing) {
            if (left <= 0) break;
            // An account never matches itself, and an order past its expiry only waits to be swept.
            if (bid.owner().equals(seller.account()) || bid.expiresAt() <= now) continue;
            Party buyer = new Party(bid.owner(), bid.id(), bid.source());
            // The resting order's price, unless a surcharge on the buyer forces it lower.
            long base = restingBuyBase(env, seller, buyer, item, bid.unitPrice(), left);
            if (base < ask) continue;
            int quantity = allowed(env, seller, buyer, base, Math.min(left, bid.remaining()), now);
            if (quantity < 0) break;
            if (quantity == 0) continue;
            Fill fill = settle(env, store, seller, buyer, item, Lot.PRISTINE, quantity, base, true, bid.unitPrice(),
                    bid.escrow(), now, null);
            if (fill == null) continue;
            fills.add(fill);
            left -= quantity;
            // Delivered before the order is taken off the book: a rule's order that this fill
            // completes is still the rule's while its goods go to the rule's own inventory.
            deliverToBuyer(env, store, bid, Lot.of(item, quantity));
            afterBuyFill(env, store, bid, quantity);
        }
        return left;
    }

    // --- placing a buy order -----------------------------------------------------------------

    /**
     * Places a buy order: the credits for all of it move into escrow, it fills whatever sell
     * orders it crosses at their prices — the difference to the limit comes straight back — and
     * what is left rests on the book.
     *
     * <p>Refused before anything moves when the owner is at their cap, cannot pay, or the goods
     * the order would receive at once could not be stored by {@code receiver}.
     *
     * @param preEscrowed true when the credits are in the escrow account already and recorded by
     *                    the caller (a rule's budget); the caller then takes {@code escrowed} plus
     *                    the listing fee off its own record when the result is OK
     * @param presetId    the id the order is to have, or null for a fresh one
     */
    public synchronized Placed placeBuy(TradeEnv env, AccountId owner, String item, int quantity, long unitPrice,
            OrderSource source, boolean preEscrowed, GoodsReceiver receiver, UUID presetId) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return Placed.refused(BookResult.FROZEN);
        if (store == null || env == null || owner == null || item == null || item.isBlank() || source == null
                || receiver == null || quantity < 1 || quantity > MAX_ORDER_QUANTITY || unitPrice < 1L
                || item.length() > Lot.MAX_ITEM_LENGTH || !mayOwn(env, owner, source) || overflows(unitPrice, quantity)
                || taken(presetId) || preEscrowed != (source == OrderSource.RULE)
                || !ruleOwned(store, source, presetId, owner)) return Placed.refused(BookResult.INVALID);
        if (!env.items().known(item)) return Placed.refused(BookResult.UNKNOWN_ITEM);
        if (!env.items().tradeable(item)) return Placed.refused(BookResult.UNTRADEABLE);
        boolean player = owner instanceof PlayerAccountId;
        if (player && !MarketLimits.withinCap(buyCount(owner), 1, env.settings().maxBuyOrders()))
            return Placed.refused(BookResult.LIMIT_REACHED);
        long now = store.ledger().now();
        UUID id = presetId != null ? presetId : freshId();
        Party buyer = new Party(owner, id, source);
        // What could fill at once must have somewhere to go before any credit moves.
        int atOnce = crossingStock(env, buyer, item, unitPrice, quantity, now);
        if (atOnce > 0 && !receiver.canTake(Lot.of(item, atOnce))) return Placed.refused(BookResult.STORAGE_FULL);
        long total = unitPrice * quantity;
        long fee = player ? Fees.flat(env.settings().listingFee()) : 0L;
        LedgerPlan.Builder funding = LedgerPlan.builder();
        // The escrow is promised to another player, so it cannot come out of an unvested starting
        // balance; the fee can. A rule's order is paid for out of its budget, already in escrow.
        if (!preEscrowed) funding.move(owner, SystemAccountId.ESCROW, total, TransactionType.MARKET_ESCROW);
        if (fee > 0L) funding.move(preEscrowed ? SystemAccountId.ESCROW : owner, SystemAccountId.RESERVE, fee,
                TransactionType.FEE_LISTING);
        LedgerResult funded = store.execute(funding.build());
        if (!funded.ok()) return Placed.refused(switch (funded) {
            case FROZEN -> BookResult.FROZEN;
            case FUNDS_LOCKED -> BookResult.FUNDS_LOCKED;
            case INSUFFICIENT_FUNDS -> preEscrowed ? BookResult.FEE_UNPAID : BookResult.INSUFFICIENT_FUNDS;
            default -> BookResult.INVALID;
        });
        List<Fill> fills = new ArrayList<>();
        long escrow = total;
        int left = quantity;
        boolean toVault = false;
        TreeSet<SellOrder> side = asks.get(item);
        List<SellOrder> crossing = new ArrayList<>();
        // Asks are sorted cheapest first, so the first one above the limit ends the walk.
        if (side != null) for (SellOrder ask : side) {
            if (ask.unitPrice() > unitPrice) break;
            crossing.add(ask);
        }
        for (SellOrder ask : crossing) {
            if (left <= 0) break;
            if (ask.owner().equals(owner) || ask.expiresAt() <= now) continue;
            Party seller = new Party(ask.owner(), ask.id(), ask.source());
            int amount = allowed(env, buyer, seller, ask.unitPrice(), Math.min(left, ask.lot().count()), now);
            if (amount < 0) break;
            if (amount == 0) continue;
            Fill fill = settle(env, store, seller, buyer, item, Lot.PRISTINE, amount, ask.unitPrice(), true, unitPrice,
                    escrow, now, null);
            if (fill == null) continue;
            fills.add(fill);
            left -= amount;
            escrow -= unitPrice * amount;
            afterSellFill(env, ask, amount);
            toVault |= receiver.give(Lot.of(item, amount));
        }
        if (left > 0) putBuy(new BuyOrder(id, owner, item, left, unitPrice, escrow, nextSequence++, expiry(env, now),
                source));
        touch(item);
        return new Placed(BookResult.OK, id, left, List.copyOf(fills), fee, total, toVault);
    }

    /** How many units a new buy order could fill at once; an upper bound, for the storage check. */
    private int crossingStock(TradeEnv env, Party buyer, String item, long limit, int wanted, long now) {
        TreeSet<SellOrder> side = asks.get(item);
        if (side == null) return 0;
        int found = 0;
        for (SellOrder ask : side) {
            if (ask.unitPrice() > limit || found >= wanted) break;
            if (ask.owner().equals(buyer.account()) || ask.expiresAt() <= now) continue;
            found += Math.min(wanted - found, ask.lot().count());
        }
        return found;
    }

    // --- trading against one named order -----------------------------------------------------

    /**
     * Buys from one sell order, paying from the balance: the terminal's "Buy", and the companion
     * app's. Works for a modified lot too. {@code ceiling} is the unit price the buyer agreed to;
     * settling above it is refused, settling below it charges the lower price.
     */
    public synchronized Traded buyFrom(TradeEnv env, AccountId buyer, UUID sellId, int quantity, long ceiling,
            GoodsReceiver receiver) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return Traded.refused(BookResult.FROZEN);
        if (store == null || env == null || buyer == null || sellId == null || receiver == null || quantity < 1
                || ceiling < 1L) return Traded.refused(BookResult.INVALID);
        long now = store.ledger().now();
        SellOrder ask = sells.get(sellId);
        if (ask == null || ask.expiresAt() <= now) return Traded.refused(BookResult.NOT_FOUND);
        if (ask.owner().equals(buyer)) return Traded.refused(BookResult.OWN_ORDER);
        if (quantity > ask.lot().count()) return Traded.refused(BookResult.OUT_OF_STOCK);
        Lot goods = ask.lot().withCount(quantity);
        if (!receiver.canTake(goods)) return Traded.refused(BookResult.STORAGE_FULL);
        Party seller = new Party(ask.owner(), ask.id(), ask.source());
        Party taker = new Party(buyer, null, OrderSource.HAND);
        if (allowed(env, taker, seller, ask.unitPrice(), quantity, now) < quantity)
            return Traded.refused(BookResult.OUT_OF_STOCK);
        BookResult[] why = new BookResult[1];
        Fill fill = settle(env, store, seller, taker, ask.item(), ask.lot().payload(), quantity, ask.unitPrice(),
                false, ceiling, 0L, now, why);
        if (fill == null) return Traded.refused(why[0] == null ? BookResult.INTERNAL : why[0]);
        afterSellFill(env, ask, quantity);
        boolean toVault = receiver.give(goods);
        touch(ask.item());
        return new Traded(BookResult.OK, fill, toVault);
    }

    /**
     * Fills one buy order with {@code lot}, taken from {@code origin}: the terminal's "fill this
     * buy order". Only unmodified goods of the item the order names are accepted. The proceeds,
     * less the market fee, become the seller's credit claim.
     */
    public synchronized Traded sellTo(TradeEnv env, AccountId seller, UUID buyId, Lot lot, GoodsOrigin origin) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return Traded.refused(BookResult.FROZEN);
        if (store == null || env == null || seller == null || buyId == null || lot == null || origin == null)
            return Traded.refused(BookResult.INVALID);
        long now = store.ledger().now();
        BuyOrder bid = buys.get(buyId);
        if (bid == null || bid.expiresAt() <= now) return Traded.refused(BookResult.NOT_FOUND);
        if (bid.owner().equals(seller)) return Traded.refused(BookResult.OWN_ORDER);
        if (!bid.item().equals(lot.item())) return Traded.refused(BookResult.WRONG_ITEM);
        if (!lot.pristine()) return Traded.refused(BookResult.MODIFIED_ITEM);
        if (lot.count() > bid.remaining()) return Traded.refused(BookResult.NOT_ENOUGH);
        if (!env.items().tradeable(lot.item())) return Traded.refused(BookResult.UNTRADEABLE);
        if (!origin.holds(lot)) return Traded.refused(BookResult.NOT_HOLDING);
        Party giver = new Party(seller, null, OrderSource.HAND);
        Party buyer = new Party(bid.owner(), bid.id(), bid.source());
        long base = restingBuyBase(env, giver, buyer, lot.item(), bid.unitPrice(), lot.count());
        if (base < 1L) return Traded.refused(BookResult.PRICE_MOVED);
        if (allowed(env, giver, buyer, base, lot.count(), now) < lot.count()) return Traded.refused(BookResult.NOT_ENOUGH);
        if (!origin.take(lot)) return Traded.refused(BookResult.NOT_HOLDING);
        BookResult[] why = new BookResult[1];
        Fill fill = settle(env, store, giver, buyer, lot.item(), Lot.PRISTINE, lot.count(), base, true,
                bid.unitPrice(), bid.escrow(), now, why);
        if (fill == null) {
            origin.restore(lot);
            return Traded.refused(why[0] == null ? BookResult.INTERNAL : why[0]);
        }
        // Delivered before the order is taken off the book; see matchSell.
        deliverToBuyer(env, store, bid, lot);
        afterBuyFill(env, store, bid, lot.count());
        touch(lot.item());
        return new Traded(BookResult.OK, fill, true);
    }

    /**
     * Buys from one sell order with credits that are already in the escrow account and recorded
     * by the caller: an auto-trade rule sweeping the book out of its budget. The price is the
     * seller's; no discount applies. The caller takes {@code fill.buyerPaid()} off its own record.
     *
     * @param budget the most the caller may spend
     */
    public synchronized Traded sweep(TradeEnv env, AccountId buyer, UUID ruleOrderId, UUID sellId, int quantity,
            long budget, GoodsReceiver receiver) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return Traded.refused(BookResult.FROZEN);
        if (store == null || env == null || buyer == null || ruleOrderId == null || sellId == null || receiver == null
                || quantity < 1 || budget < 1L || !ruleOwned(store, OrderSource.RULE, ruleOrderId, buyer))
            return Traded.refused(BookResult.INVALID);
        long now = store.ledger().now();
        SellOrder ask = sells.get(sellId);
        if (ask == null || ask.expiresAt() <= now || !ask.matchable()) return Traded.refused(BookResult.NOT_FOUND);
        if (ask.owner().equals(buyer)) return Traded.refused(BookResult.OWN_ORDER);
        if (quantity > ask.lot().count()) return Traded.refused(BookResult.OUT_OF_STOCK);
        Lot goods = ask.lot().withCount(quantity);
        if (!receiver.canTake(goods)) return Traded.refused(BookResult.STORAGE_FULL);
        Party seller = new Party(ask.owner(), ask.id(), ask.source());
        Party taker = new Party(buyer, ruleOrderId, OrderSource.RULE);
        if (allowed(env, taker, seller, ask.unitPrice(), quantity, now) < quantity)
            return Traded.refused(BookResult.OUT_OF_STOCK);
        // The buyer's price can only be the seller's or a surcharge on it; it is the limit as well,
        // so nothing is refunded and what the fill reports as paid is what the budget loses.
        long unit = buyerUnitPrice(env, seller, taker, ask.item(), ask.unitPrice(), quantity);
        if (overflows(unit, quantity) || unit * quantity > budget) return Traded.refused(BookResult.INSUFFICIENT_FUNDS);
        BookResult[] why = new BookResult[1];
        Fill fill = settle(env, store, seller, taker, ask.item(), Lot.PRISTINE, quantity, ask.unitPrice(), true, unit,
                budget, now, why);
        if (fill == null) return Traded.refused(why[0] == null ? BookResult.INTERNAL : why[0]);
        afterSellFill(env, ask, quantity);
        receiver.give(goods);
        touch(ask.item());
        return new Traded(BookResult.OK, fill, true);
    }

    // --- the one settlement ------------------------------------------------------------------

    /**
     * Settles one fill as a single ledger plan, or does nothing.
     *
     * <p>With {@code base} the seller's side ({@code unitBase × quantity}), {@code fee} the market
     * fee on it and {@code paid} the buyer's side after any price modifier:
     * <ul>
     *   <li>the seller is owed {@code base − fee}: a player as a credit claim (the credits stay in
     *       the escrow account until collected), a system account straight into its balance;</li>
     *   <li>the fee goes to the Reserve;</li>
     *   <li>a surcharge ({@code paid > base}) goes to the Reserve; a discount ({@code paid < base})
     *       is paid by the Reserve, and when it cannot pay, the fill settles at {@code base};</li>
     *   <li>a buyer paying from escrow held {@code limit × quantity} for these units; what the
     *       fill does not use comes back to them at once (for a rule, it stays in escrow as the
     *       rule's budget).</li>
     * </ul>
     * A plan changes at most one player's balance — the buyer's — so it is always one the ledger
     * can report.
     *
     * @param fromEscrow  true when the buyer's credits are in escrow already (an order, a rule's
     *                    budget); false when they pay from their balance now
     * @param limit       the most the buyer pays per unit
     * @param escrowHeld  with {@code fromEscrow}, how much of the buyer's escrow is there to draw on
     * @param why         when not null, told the reason a fill was refused
     * @return the fill, or null when nothing moved
     */
    private Fill settle(TradeEnv env, EconomyStore store, Party seller, Party buyer, String item, int payload,
            int quantity, long unitBase, boolean fromEscrow, long limit, long escrowHeld, long now, BookResult[] why) {
        if (quantity < 1 || unitBase < 1L || overflows(unitBase, quantity) || overflows(limit, quantity))
            return refuse(why, BookResult.INVALID);
        long base = unitBase * quantity;
        long held = limit * quantity;
        if (fromEscrow && (unitBase > limit || held > escrowHeld)) return refuse(why, BookResult.PRICE_MOVED);
        long unit = buyerUnitPrice(env, seller, buyer, item, unitBase, quantity);
        if (unit > limit || overflows(unit, quantity)) return refuse(why, BookResult.PRICE_MOVED);
        long fee = Fees.fee(base, env.settings().marketFeePercent());
        long net = base - fee;
        boolean playerSeller = seller.account() instanceof PlayerAccountId;
        if (playerSeller && net > 0L && !canBook(store, seller.account(), net)) return refuse(why, BookResult.PAYOUT_BLOCKED);
        long paid = unit * quantity;
        LedgerResult result = store.execute(plan(seller, buyer, base, paid, fee, net, held, fromEscrow, playerSeller));
        boolean discountSkipped = false;
        if (result == LedgerResult.RESERVE_SHORT && paid < base) {
            // The Reserve cannot pay the discount. The seller's own price still stands, as long as
            // it is not more than the buyer agreed to.
            if (unitBase > limit) return refuse(why, BookResult.PRICE_MOVED);
            paid = base;
            discountSkipped = true;
            result = store.execute(plan(seller, buyer, base, paid, fee, net, held, fromEscrow, playerSeller));
        }
        if (!result.ok()) return refuse(why, switch (result) {
            case FROZEN -> BookResult.FROZEN;
            case FUNDS_LOCKED -> BookResult.FUNDS_LOCKED;
            case INSUFFICIENT_FUNDS -> BookResult.INSUFFICIENT_FUNDS;
            default -> BookResult.INTERNAL;
        });
        // The seller's proceeds are in the escrow account now; this records whose they are.
        if (playerSeller) bookClaim(store, seller.account(), TransactionType.MARKET_PAYOUT, net);
        long refund = fromEscrow ? held - Math.max(paid, base) : 0L;
        RuleOrders rules = store.ruleOrders();
        if (rules != null) {
            if (seller.rule() && seller.orderId() != null)
                rules.filled(seller.orderId(), false, quantity, base, paid, 0L, now);
            if (buyer.rule() && buyer.orderId() != null)
                rules.filled(buyer.orderId(), true, quantity, base, paid, refund, now);
        }
        // Counted toward the traded volume the exchange's price model reads once per step.
        volume.record(seller.account(), buyer.account(), item, quantity, base);
        return new Fill(seller.orderId(), buyer.orderId(), seller.account(), buyer.account(), item, payload, quantity,
                unitBase, base, paid, fee, net, refund, discountSkipped);
    }

    private static Fill refuse(BookResult[] why, BookResult reason) {
        if (why != null) why[0] = reason;
        return null;
    }

    /** The ledger plan of one fill; see {@link #settle}. */
    private static LedgerPlan plan(Party seller, Party buyer, long base, long paid, long fee, long net, long held,
            boolean fromEscrow, boolean playerSeller) {
        LedgerPlan.Builder plan = LedgerPlan.builder();
        AccountId payer = buyer.account();
        // A discount is the Reserve's to pay, and it pays before the buyer does.
        if (paid < base) plan.pay(BudgetKind.UNBUDGETED, payer, base - paid, TransactionType.PRICE_ADJUSTMENT);
        if (!fromEscrow) plan.move(payer, SystemAccountId.ESCROW, base, TransactionType.MARKET_PURCHASE);
        if (fee > 0L) plan.move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee, TransactionType.FEE_MARKET);
        if (paid > base) {
            // A surcharge is part of the price of buying from another player, so it is player-facing.
            if (fromEscrow) plan.move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, paid - base,
                    TransactionType.PRICE_ADJUSTMENT);
            else plan.movePlayerFacing(payer, SystemAccountId.RESERVE, paid - base, TransactionType.PRICE_ADJUSTMENT);
        }
        // The part of the buyer's escrow this fill did not need. A rule's stays in escrow as its budget.
        long refund = fromEscrow ? held - Math.max(paid, base) : 0L;
        if (refund > 0L && !buyer.rule()) plan.move(SystemAccountId.ESCROW, payer, refund, TransactionType.ESCROW_REFUND);
        if (!playerSeller && net > 0L) plan.move(SystemAccountId.ESCROW, seller.account(), net,
                TransactionType.MARKET_PAYOUT);
        return plan.build();
    }

    /**
     * What the buyer pays per unit for goods offered at {@code unitBase}. Price modifiers apply
     * between two players only; an order placed by a rule is never given a discount, so that two
     * rules trading with each other can never come out ahead at the Reserve's expense. Between two
     * players a discount is cut back to the fee the sale pays, for the same reason.
     */
    private static long buyerUnitPrice(TradeEnv env, Party seller, Party buyer, String item, long unitBase,
            int available) {
        if (!(seller.account() instanceof PlayerAccountId) || !(buyer.account() instanceof PlayerAccountId))
            return unitBase;
        UUID listing = seller.orderId() != null ? seller.orderId() : buyer.orderId();
        long unit;
        try {
            unit = Math.max(1L, env.pricing().unitPrice(buyer.account(), listing == null ? new UUID(0L, 0L) : listing,
                    item, unitBase, Math.max(1, available)));
        } catch (RuntimeException failure) {
            // A modifier that throws changes nothing: the seller's price stands.
            return unitBase;
        }
        if (unit >= unitBase) return unit;
        if (seller.rule() || buyer.rule()) return unitBase;
        // A discount is the Reserve's to pay, and never more than the fee this sale pays it.
        return Settlement.cappedUnitPrice(unitBase, unit, env.settings().marketFeePercent());
    }

    /**
     * The seller's price a fill against a resting buy order settles at: the order's own price,
     * unless a surcharge on the buyer would take them past it — then the highest price that keeps
     * the buyer within their limit. 0 when there is none.
     */
    private static long restingBuyBase(TradeEnv env, Party seller, Party buyer, String item, long limit, int available) {
        if (buyerUnitPrice(env, seller, buyer, item, limit, available) <= limit) return limit;
        long low = 1L;
        long high = limit - 1L;
        long best = 0L;
        while (low <= high) {
            long middle = low + (high - low) / 2L;
            if (buyerUnitPrice(env, seller, buyer, item, middle, available) <= limit) {
                best = middle;
                low = middle + 1L;
            } else high = middle - 1L;
        }
        return best;
    }

    /**
     * How many of {@code wanted} units may fill between an incoming party and a resting one, given
     * the daily value cap of whichever of them is a rule.
     *
     * @return the quantity; 0 when the resting order must be passed over; -1 when the incoming
     *         side itself may not fill any more, which ends the matching
     */
    private int allowed(TradeEnv env, Party incoming, Party resting, long unitBase, int wanted, long now) {
        EconomyStore store = owner();
        RuleOrders rules = store == null ? null : store.ruleOrders();
        if (rules == null) return wanted;
        int quantity = wanted;
        if (incoming.rule() && incoming.orderId() != null) {
            quantity = Math.min(quantity, rules.allowance(env, incoming.orderId(), unitBase, quantity, now));
            if (quantity <= 0) return -1;
        }
        if (resting.rule() && resting.orderId() != null)
            quantity = Math.min(quantity, rules.allowance(env, resting.orderId(), unitBase, quantity, now));
        return Math.max(0, quantity);
    }

    // --- after a fill ------------------------------------------------------------------------

    /** A resting sell order sold {@code quantity}: it shrinks, or leaves the book when nothing is left. */
    private void afterSellFill(TradeEnv env, SellOrder ask, int quantity) {
        removeSell(ask);
        int left = ask.lot().count() - quantity;
        if (left > 0) {
            putSell(ask.withCount(left));
            return;
        }
        EconomyStore store = owner();
        RuleOrders rules = store == null ? null : store.ruleOrders();
        if (ask.source() == OrderSource.RULE && rules != null) rules.closed(env, ask.id(), 0L, null);
    }

    /** A resting buy order bought {@code quantity}: it shrinks, or leaves the book when it is complete. */
    private void afterBuyFill(TradeEnv env, EconomyStore store, BuyOrder bid, int quantity) {
        removeBuy(bid);
        int left = bid.remaining() - quantity;
        long escrow = bid.escrow() - bid.unitPrice() * quantity;
        if (left > 0) {
            putBuy(bid.after(left, escrow));
            return;
        }
        // A completely filled order has used its escrow exactly. Should a row from an older save
        // hold more than its quantity was worth, the rest is the buyer's and goes back to them; if
        // even that cannot be booked it goes to the Reserve rather than stay in escrow unrecorded.
        if (!closeBuy(env, store, bid, escrow) && escrow > 0L) store.execute(LedgerPlan.of(SystemAccountId.ESCROW,
                SystemAccountId.RESERVE, escrow, TransactionType.ESCROW_REFUND));
    }

    /** Goods a resting buy order bought: a fill of a standing order always books, whatever the cap. */
    private static void deliverToBuyer(TradeEnv env, EconomyStore store, BuyOrder bid, Lot goods) {
        RuleOrders rules = store.ruleOrders();
        if (bid.source() == OrderSource.RULE && rules != null && rules.deliver(env, bid.id(), goods)) return;
        if (bid.owner() instanceof SystemAccountId system) {
            SystemDelivery delivery = env.systemDelivery();
            if (delivery != null && delivery.handles(system)) {
                delivery.deliver(system, goods);
                return;
            }
        }
        VaultGoods.deliver(store, env, bid.owner(), goods);
    }

    // --- cancelling and expiry ---------------------------------------------------------------

    /**
     * Cancels the caller's own order: a sell order's goods go to their vault, a buy order's
     * unspent escrow becomes their credit claim. Idempotent: a second call finds nothing. An order
     * a rule keeps up is not cancelled here — the rule would only place it again; pause or delete
     * the rule instead.
     */
    public synchronized CancelResult cancel(TradeEnv env, UUID id, AccountId actor) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return CancelResult.FROZEN;
        if (store == null || env == null || id == null || actor == null) return CancelResult.NOT_FOUND;
        SellOrder ask = sells.get(id);
        if (ask != null) {
            if (!ask.owner().equals(actor)) return CancelResult.NOT_FOUND;
            if (ask.source() == OrderSource.RULE) return CancelResult.NOT_CANCELLABLE;
            if (actor instanceof PlayerAccountId && !store.vault().canAccept(actor, List.of(ask.lot()),
                    env.settings().vaultTradeCap(), env.limit())) return CancelResult.CLAIMS_FULL;
            removeSell(ask);
            closeSell(env, store, ask);
            touch(ask.item());
            return CancelResult.CANCELLED;
        }
        BuyOrder bid = buys.get(id);
        if (bid == null || !bid.owner().equals(actor)) return CancelResult.NOT_FOUND;
        if (bid.source() == OrderSource.RULE) return CancelResult.NOT_CANCELLABLE;
        if (actor instanceof PlayerAccountId && bid.escrow() > 0L && !canBook(store, actor, bid.escrow()))
            return CancelResult.REFUND_BLOCKED;
        removeBuy(bid);
        if (!closeBuy(env, store, bid, bid.escrow())) {
            putBuy(bid);
            return CancelResult.REFUND_BLOCKED;
        }
        touch(bid.item());
        return CancelResult.CANCELLED;
    }

    /**
     * Takes a rule's own order off the book and hands back what it held. Only the rules section
     * calls this: the escrow stays in the escrow account and becomes the rule's budget again, and
     * the goods are the rule's to put back. Nothing else is told.
     *
     * @return what the order held, or null when there is no such order of a rule's
     */
    public synchronized Released release(UUID id) {
        SellOrder ask = id == null ? null : sells.get(id);
        if (ask != null && ask.source() == OrderSource.RULE) {
            removeSell(ask);
            touch(ask.item());
            return new Released(0L, ask.lot());
        }
        BuyOrder bid = id == null ? null : buys.get(id);
        if (bid == null || bid.source() != OrderSource.RULE) return null;
        removeBuy(bid);
        touch(bid.item());
        return new Released(bid.escrow(), null);
    }

    /**
     * Closes every order past its expiry: goods back to the owner's vault, escrow back as a
     * credit claim. Nothing lapses while the economy is frozen. @return how many orders closed
     */
    public synchronized int expire(TradeEnv env, long now) {
        EconomyStore store = owner();
        if (store == null || env == null || EconomyHealth.frozen()) return 0;
        int closed = 0;
        for (SellOrder ask : new ArrayList<>(sells.values())) {
            if (ask.expiresAt() > now || !canClose(env, ask.owner())) continue;
            removeSell(ask);
            closeSell(env, store, ask);
            touch(ask.item());
            closed++;
        }
        for (BuyOrder bid : new ArrayList<>(buys.values())) {
            if (bid.expiresAt() > now || !canClose(env, bid.owner())) continue;
            removeBuy(bid);
            // The order only goes once its escrow has actually been booked back.
            if (!closeBuy(env, store, bid, bid.escrow())) {
                putBuy(bid);
                continue;
            }
            touch(bid.item());
            closed++;
        }
        return closed;
    }

    /** A system account's order can only be closed while something keeps that account's stock. */
    private static boolean canClose(TradeEnv env, AccountId owner) {
        if (!(owner instanceof SystemAccountId system)) return true;
        SystemDelivery delivery = env.systemDelivery();
        return delivery != null && delivery.handles(system);
    }

    /** The goods of a sell order that left the book unsold go back to whoever they belong to. */
    private static void closeSell(TradeEnv env, EconomyStore store, SellOrder ask) {
        RuleOrders rules = store.ruleOrders();
        if (ask.source() == OrderSource.RULE && rules != null && rules.closed(env, ask.id(), 0L, ask.lot())) return;
        if (ask.owner() instanceof SystemAccountId system) {
            SystemDelivery delivery = env.systemDelivery();
            if (delivery != null && delivery.handles(system)) {
                delivery.deliver(system, ask.lot());
                return;
            }
        }
        // A refund always books, even past the vault's cap.
        VaultGoods.deliver(store, env, ask.owner(), ask.lot());
    }

    /**
     * The escrow of a buy order that left the book goes back to whoever it belongs to: a rule's
     * budget, a player's credit claim, a system account's balance.
     *
     * @return false, with nothing changed, when it could not be booked back
     */
    private static boolean closeBuy(TradeEnv env, EconomyStore store, BuyOrder bid, long escrow) {
        RuleOrders rules = store.ruleOrders();
        if (bid.source() == OrderSource.RULE && rules != null && rules.closed(env, bid.id(), Math.max(0L, escrow), null))
            return true;
        if (escrow <= 0L) return true;
        if (bid.owner() instanceof PlayerAccountId player)
            return store.market().addCreditClaim(player.playerId(), escrow, TransactionType.ESCROW_REFUND);
        return store.execute(LedgerPlan.of(SystemAccountId.ESCROW, bid.owner(), escrow, TransactionType.ESCROW_REFUND)).ok();
    }

    // --- claims ------------------------------------------------------------------------------

    private static boolean canBook(EconomyStore store, AccountId account, long amount) {
        return !(account instanceof PlayerAccountId player) || amount <= 0L
                || store.market().canAcceptCreditClaim(player.playerId(), amount);
    }

    /**
     * Records that credits already in the escrow account are owed to a player. Every caller checks
     * {@link #canBook} before it moves anything, so a refusal here is not expected; if it happens
     * anyway the credits go to the Reserve rather than stay in escrow with no owner on record.
     */
    private static void bookClaim(EconomyStore store, AccountId account, TransactionType reason, long amount) {
        if (amount <= 0L || !(account instanceof PlayerAccountId player)) return;
        if (store.market().addCreditClaim(player.playerId(), amount, reason)) return;
        NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A credit claim could not be recorded; the amount was "
                + "returned to the Reserve.");
        store.execute(LedgerPlan.of(SystemAccountId.ESCROW, SystemAccountId.RESERVE, amount, TransactionType.ESCROW_REFUND));
    }

    // --- migration ---------------------------------------------------------------------------

    /**
     * Adopts a Market Board listing from before the book existed as a sell order with the same id,
     * price and expiry. The goods are already the store's; nothing is charged and nothing matches.
     *
     * @return false when the id is taken or the row is not an order
     */
    public synchronized boolean adoptSell(UUID id, AccountId owner, Lot lot, long unitPrice, long expiresAt) {
        if (id == null || owner == null || lot == null || unitPrice < 1L || taken(id)) return false;
        putSell(new SellOrder(id, owner, lot, unitPrice, nextSequence++, expiresAt, OrderSource.HAND));
        touch(lot.item());
        return true;
    }

    /**
     * Adopts a buy offer from before the book existed as a buy order with the same id, price,
     * expiry and escrow. The escrow is already in the escrow account, so Invariant B is unchanged.
     *
     * @return false when the id is taken or the row is not an order
     */
    public synchronized boolean adoptBuy(UUID id, AccountId owner, String item, int remaining, long unitPrice,
            long escrow, long expiresAt) {
        if (id == null || owner == null || item == null || item.isBlank() || remaining < 1 || unitPrice < 1L
                || escrow < 0L || taken(id)) return false;
        putBuy(new BuyOrder(id, owner, item, remaining, unitPrice, escrow, nextSequence++, expiresAt, OrderSource.HAND));
        touch(item);
        return true;
    }

    // --- erasure and export ------------------------------------------------------------------

    /**
     * Erases one player's orders. Their goods are destroyed and the credits behind their buy
     * orders move to the Reserve as an erasure sweep, in a row that names no player. Nothing owed
     * to anyone else is touched: a seller's proceeds from the erased player's past purchases are
     * credit claims in the market section and stay there.
     *
     * @return the goods that were destroyed, so a caller that counts goods can account for them
     */
    public synchronized List<Lot> forgetPlayer(UUID player) {
        if (player == null) return List.of();
        AccountId account = new PlayerAccountId(player);
        // The traded-volume tally is only in memory, but it names accounts until it is next read.
        volume.forget(account);
        List<Lot> destroyed = new ArrayList<>();
        for (SellOrder ask : new ArrayList<>(sells.values())) {
            if (!ask.owner().equals(account)) continue;
            removeSell(ask);
            destroyed.add(ask.lot());
            touch(ask.item());
        }
        long escrow = 0L;
        for (BuyOrder bid : new ArrayList<>(buys.values())) {
            if (!bid.owner().equals(account)) continue;
            removeBuy(bid);
            escrow = EscrowMath.total(escrow, bid.escrow());
            touch(bid.item());
        }
        EconomySavedData ledger = ledger();
        if (ledger != null && escrow > 0L) ledger.sweepErasedEscrow(escrow);
        return destroyed;
    }

    /**
     * Data-subject export: the player's own open orders. Items are reported as id and count only,
     * and no row names anyone else.
     */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        AccountId account = Accounts.of(player);
        JsonArray selling = new JsonArray();
        for (SellOrder ask : sells.values()) {
            if (!ask.owner().equals(account)) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", ask.id().toString());
            row.addProperty("item", ask.item());
            row.addProperty("count", ask.lot().count());
            row.addProperty("unit_price", ask.unitPrice());
            row.addProperty("expires_at", ask.expiresAt());
            row.addProperty("source", ask.source().name());
            selling.add(row);
        }
        out.add("sell_orders", selling);
        JsonArray buying = new JsonArray();
        for (BuyOrder bid : buys.values()) {
            if (!bid.owner().equals(account)) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", bid.id().toString());
            row.addProperty("item", bid.item());
            row.addProperty("remaining", bid.remaining());
            row.addProperty("unit_price", bid.unitPrice());
            row.addProperty("escrow", bid.escrow());
            row.addProperty("expires_at", bid.expiresAt());
            row.addProperty("source", bid.source().name());
            buying.add(row);
        }
        out.add("buy_orders", buying);
        return out;
    }

    // --- internals ---------------------------------------------------------------------------

    /**
     * Anything placed as a rule's has to be a rule's: the id is one a rule of that owner has put
     * its name to. Everything else passes.
     */
    private static boolean ruleOwned(EconomyStore store, OrderSource source, UUID orderId, AccountId owner) {
        if (source != OrderSource.RULE) return true;
        RuleOrders rules = store.ruleOrders();
        return rules != null && orderId != null && rules.owns(orderId, owner);
    }

    /** A player owns ordinary orders; a system account only House orders, and only while its stock is kept. */
    private static boolean mayOwn(TradeEnv env, AccountId owner, OrderSource source) {
        if (owner instanceof PlayerAccountId) return source != OrderSource.HOUSE;
        if (!(owner instanceof SystemAccountId system) || source != OrderSource.HOUSE) return false;
        SystemDelivery delivery = env.systemDelivery();
        return system.isHouse() && delivery != null && delivery.handles(system);
    }

    private static boolean overflows(long unitPrice, int quantity) {
        return unitPrice < 0L || quantity < 0 || (quantity > 0 && unitPrice > Long.MAX_VALUE / quantity);
    }

    private static long expiry(TradeEnv env, long now) {
        long duration = Math.max(1L, env.settings().orderDurationMillis());
        return Long.MAX_VALUE - now < duration ? Long.MAX_VALUE : now + duration;
    }

    private boolean taken(UUID id) { return id != null && (sells.containsKey(id) || buys.containsKey(id)); }

    private UUID freshId() {
        UUID id;
        do id = UUID.randomUUID(); while (taken(id));
        return id;
    }

    private void putSell(SellOrder order) {
        sells.put(order.id(), order);
        sellCounts.merge(order.owner(), 1, Integer::sum);
        if (order.matchable()) asks.computeIfAbsent(order.item(), ignored -> new TreeSet<>(ASKS)).add(order);
    }

    private void removeSell(SellOrder order) {
        if (sells.remove(order.id()) == null) return;
        sellCounts.computeIfPresent(order.owner(), (owner, count) -> count <= 1 ? null : count - 1);
        TreeSet<SellOrder> side = asks.get(order.item());
        if (side != null && side.remove(order) && side.isEmpty()) asks.remove(order.item());
    }

    private void putBuy(BuyOrder order) {
        buys.put(order.id(), order);
        buyCounts.merge(order.owner(), 1, Integer::sum);
        bids.computeIfAbsent(order.item(), ignored -> new TreeSet<>(BIDS)).add(order);
    }

    private void removeBuy(BuyOrder order) {
        if (buys.remove(order.id()) == null) return;
        buyCounts.computeIfPresent(order.owner(), (owner, count) -> count <= 1 ? null : count - 1);
        TreeSet<BuyOrder> side = bids.get(order.item());
        if (side != null && side.remove(order) && side.isEmpty()) bids.remove(order.item());
    }

    // --- persistence -------------------------------------------------------------------------

    private record SellRow(String id, String owner, String item, int count, int payload, long price, long sequence,
            long expires, String source) {
        static final Codec<SellRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(SellRow::id),
                Codec.STRING.fieldOf("owner").forGetter(SellRow::owner),
                Codec.STRING.fieldOf("item").forGetter(SellRow::item),
                Codec.INT.fieldOf("count").forGetter(SellRow::count),
                // Left out for an unmodified lot, which is nearly all of them.
                Codec.INT.optionalFieldOf("payload", Lot.PRISTINE).forGetter(SellRow::payload),
                Codec.LONG.fieldOf("price").forGetter(SellRow::price),
                Codec.LONG.optionalFieldOf("sequence", 0L).forGetter(SellRow::sequence),
                Codec.LONG.fieldOf("expires").forGetter(SellRow::expires),
                Codec.STRING.optionalFieldOf("source", OrderSource.HAND.name()).forGetter(SellRow::source))
                .apply(instance, SellRow::new));
    }

    private record BuyRow(String id, String owner, String item, int remaining, long price, long escrow, long sequence,
            long expires, String source) {
        static final Codec<BuyRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(BuyRow::id),
                Codec.STRING.fieldOf("owner").forGetter(BuyRow::owner),
                Codec.STRING.fieldOf("item").forGetter(BuyRow::item),
                Codec.INT.fieldOf("remaining").forGetter(BuyRow::remaining),
                Codec.LONG.fieldOf("price").forGetter(BuyRow::price),
                Codec.LONG.fieldOf("escrow").forGetter(BuyRow::escrow),
                Codec.LONG.optionalFieldOf("sequence", 0L).forGetter(BuyRow::sequence),
                Codec.LONG.fieldOf("expires").forGetter(BuyRow::expires),
                Codec.STRING.optionalFieldOf("source", OrderSource.HAND.name()).forGetter(BuyRow::source))
                .apply(instance, BuyRow::new));
    }

    private static Codec<BookSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("next_sequence", 1L).forGetter(section -> section.nextSequence),
                SellRow.CODEC.listOf().optionalFieldOf("sells", List.of()).forGetter(BookSection::sellRows),
                BuyRow.CODEC.listOf().optionalFieldOf("buys", List.of()).forGetter(BookSection::buyRows))
                .apply(instance, BookSection::fromRows));
    }

    private synchronized List<SellRow> sellRows() {
        List<SellRow> rows = new ArrayList<>(sells.size());
        for (SellOrder ask : sells.values()) rows.add(new SellRow(ask.id().toString(), ask.owner().stableKey(),
                ask.item(), ask.lot().count(), ask.lot().payload(), ask.unitPrice(), ask.sequence(), ask.expiresAt(),
                ask.source().name()));
        return rows;
    }

    private synchronized List<BuyRow> buyRows() {
        List<BuyRow> rows = new ArrayList<>(buys.size());
        for (BuyOrder bid : buys.values()) rows.add(new BuyRow(bid.id().toString(), bid.owner().stableKey(), bid.item(),
                bid.remaining(), bid.unitPrice(), bid.escrow(), bid.sequence(), bid.expiresAt(), bid.source().name()));
        return rows;
    }

    private static BookSection fromRows(long nextSequence, List<SellRow> sellRows, List<BuyRow> buyRows) {
        BookSection section = new BookSection();
        long highest = Math.max(1L, nextSequence);
        // A row that is not an order is skipped; every other order still loads.
        for (SellRow row : sellRows) try {
            AccountId owner = Accounts.parse(row.owner());
            if (owner == null || !Lot.valid(row.item(), row.count(), row.payload()) || row.price() < 1L) continue;
            UUID id = UUID.fromString(row.id());
            if (section.taken(id)) continue;
            section.putSell(new SellOrder(id, owner, new Lot(row.item(), row.count(), row.payload()), row.price(),
                    Math.max(0L, row.sequence()), row.expires(), OrderSource.parse(row.source())));
            highest = Math.max(highest, row.sequence() + 1L);
        } catch (IllegalArgumentException ignored) { }
        for (BuyRow row : buyRows) try {
            AccountId owner = Accounts.parse(row.owner());
            if (owner == null || row.remaining() < 1 || row.price() < 1L || row.escrow() < 0L) continue;
            UUID id = UUID.fromString(row.id());
            if (section.taken(id)) continue;
            section.putBuy(new BuyOrder(id, owner, row.item(), row.remaining(), row.price(), row.escrow(),
                    Math.max(0L, row.sequence()), row.expires(), OrderSource.parse(row.source())));
            highest = Math.max(highest, row.sequence() + 1L);
        } catch (IllegalArgumentException ignored) { }
        section.nextSequence = highest;
        return section;
    }
}
