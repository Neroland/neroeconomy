package za.co.neroland.neroeconomy.house;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.Fill;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.book.HouseOrders;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.Reserve;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.LotHolder;
import za.co.neroland.neroeconomy.guard.ArbitrageGuard;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * The Merchant Houses' section of the economy store: per commodity, what the House that trades it
 * has in stock, what it sold and bought today, the pressure on its prices, the reference price as it
 * has drifted, and the House orders it keeps on the book; per account, what it traded with the
 * Houses today; and the player-to-player trade counted toward reference drift since the last step.
 *
 * <p><b>Each simulation step</b> ({@link #step}, autonomous only): the day rolls over; references
 * drift toward what players paid each other (E3) when enough distinct pairs of players traded, within
 * a daily reach; once per economy-day, and on every step in which a reference moved, the arbitrage
 * guard runs again at the drifted prices and any failing NPC buy side is switched off; pressure decays; stock
 * is restocked toward a day's supply; each House's working capital is topped up from the Reserve
 * under the NPC budget; every commodity is re-quoted on the book — at most one resting sell order
 * and one resting buy order per covered item; and anything a House holds above its working capital
 * is swept back to the Reserve. Between steps a commodity that was traded is re-quoted on the
 * server tick ({@link #requoteDirty}), so its price walks with each chunk that fills.
 *
 * <p><b>Money.</b> A House's credits are the balance of {@code system:house/<id>} plus the escrow of
 * its open buy orders. Houses never mint: a top-up is a transfer from the Reserve counted against
 * {@code npcDailyBudget}, and a sweep a transfer back.
 *
 * <p><b>Goods.</b> House stock is kept as plain counts per item id: it is always unmodified. It is
 * reported to the store as lots, so it is one of the places goods can be. Restocking creates goods
 * and goods bought beyond the stock cap are destroyed (a sink); both are counted per item, all-time,
 * in {@link #created} and {@link #destroyed}.
 *
 * <p><b>Bounds.</b> At most {@link #MAX_COMMODITIES} commodities, {@link #MAX_ITEMS} items quoted per
 * commodity, {@link #MAX_ACCOUNT_ROWS} (account, commodity) rows and {@link #MAX_REFERENCE_ACCOUNTS}
 * reference rows per day — an account that would need a row past the bound is not served by the
 * Houses that day — and {@link #MAX_PENDING_ITEMS} items in the drift tally. Per-account rows are
 * purged at the day roll. <b>Erasure</b> {@link #forgetPlayer}; <b>export</b> {@link #exportFor}.
 * Admin views carry aggregates only.
 */
public final class HouseSection extends EconomySection implements LotHolder, HouseOrders {
    public static final Codec<HouseSection> CODEC = codec();
    public static final int MAX_COMMODITIES = 4_096;
    /** The most items of one (tag) commodity a House bids on and keeps stock of. */
    public static final int MAX_ITEMS = 16;
    public static final int MAX_ACCOUNT_ROWS = 32_768;
    public static final int MAX_REFERENCE_ACCOUNTS = 8_192;
    public static final int MAX_PENDING_ITEMS = 4_096;
    /** The most counterparty pairs remembered per item between two steps. */
    public static final int MAX_PAIRS_PER_ITEM = 256;
    /** A House's order is at most this share of the day's cap, so its price walks as it fills. */
    public static final int CHUNKS = 8;
    /** The ids of House orders: this high half, and a counter in the low half. */
    private static final long ORDER_ID_HIGH = 0x4E45524F484F5553L;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long NEVER = Long.MIN_VALUE;

    /**
     * A commodity's limits and terms as its data said at the last step.
     *
     * @param stockCap the most units the House keeps (two days' supply); bought goods beyond it are destroyed
     * @param sells    whether the House sells it now (data and guard)
     * @param buys     whether the House buys it now (data and guard, before the daily re-check)
     */
    public record Limits(long supply, long demand, long accountBuy, long accountSell, long stockCap, double elasticity,
            double recovery, double restockPerDay, int spreadBps, boolean sells, boolean buys) {
        public static final Limits NONE = new Limits(0L, 0L, 0L, 0L, 0L, 0.0D, 1.0D, 0.0D,
                CommodityDefinition.MIN_SPREAD_BPS, false, false);
        static final Codec<Limits> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("supply", 0L).forGetter(Limits::supply),
                Codec.LONG.optionalFieldOf("demand", 0L).forGetter(Limits::demand),
                Codec.LONG.optionalFieldOf("account_buy", 0L).forGetter(Limits::accountBuy),
                Codec.LONG.optionalFieldOf("account_sell", 0L).forGetter(Limits::accountSell),
                Codec.LONG.optionalFieldOf("stock_cap", 0L).forGetter(Limits::stockCap),
                Codec.DOUBLE.optionalFieldOf("elasticity", 0.0D).forGetter(Limits::elasticity),
                Codec.DOUBLE.optionalFieldOf("recovery", 1.0D).forGetter(Limits::recovery),
                Codec.DOUBLE.optionalFieldOf("restock", 0.0D).forGetter(Limits::restockPerDay),
                Codec.INT.optionalFieldOf("spread", CommodityDefinition.MIN_SPREAD_BPS).forGetter(Limits::spreadBps),
                Codec.BOOL.optionalFieldOf("sells", false).forGetter(Limits::sells),
                Codec.BOOL.optionalFieldOf("buys", false).forGetter(Limits::buys))
                .apply(instance, Limits::new));

        static Limits of(CommodityDefinition commodity, HouseDefinition house, boolean sells, boolean buys) {
            // Two days' supply; goods of a commodity the House never sells are not kept at all.
            long cap = Math.multiplyExact(commodity.npcDailySupply(), 2L);
            return new Limits(commodity.npcDailySupply(), commodity.npcDailyDemand(), commodity.perAccountDailyBuy(),
                    commodity.perAccountDailySell(), cap, commodity.elasticity(), commodity.recovery(),
                    house.restockPerDay(), commodity.spreadBps(), sells, buys);
        }
    }

    /** One commodity's House state. */
    private static final class State {
        final String key;
        String house;
        long dataReference;
        long refMillis;
        final TreeMap<String, Long> stock = new TreeMap<>();
        long sold;
        long bought;
        long value;
        long askPressure;
        long bidPressure;
        Limits limits = Limits.NONE;
        UUID askOrder;
        final TreeMap<String, UUID> bids = new TreeMap<>();
        /** Traded since it was last quoted: the server tick quotes it again. */
        boolean dirty;
        /** The reference when the economy-day began; a day's drift stays within reach of it. */
        long dayOpen;

        State(String key) { this.key = key; }

        long stockTotal() {
            long total = 0L;
            for (long count : stock.values()) total += count;
            return total;
        }
    }

    /** What one House did today. */
    private static final class HouseDay {
        long paid;
        long received;
    }

    private final TreeMap<String, State> states = new TreeMap<>();
    /** House order id to its commodity. Rebuilt on load. */
    private final Map<UUID, State> byOrder = new HashMap<>();
    /** Item id to the commodity whose stock it is. Rebuilt whenever the states change. */
    private final Map<String, State> byItem = new HashMap<>();
    private final TreeMap<String, HouseDay> houseDays = new TreeMap<>();
    /** Per account, per commodity: units bought from and sold to the Houses today. */
    private final Map<UUID, TreeMap<String, long[]>> accounts = new LinkedHashMap<>();
    private int accountRows;
    /** Per account: credits of player-to-player trade counted toward reference drift today. */
    private final Map<UUID, Long> referenceCounted = new LinkedHashMap<>();
    /** Per item: units and credits of counted player-to-player trade since the last step. */
    private final TreeMap<String, long[]> pending = new TreeMap<>();
    /**
     * Per item: {@code [units, credits]} of that counted trade per counterparty pair, keyed by
     * {@link ReferenceDrift#pairKey}. Memory only and cleared every step, so who traded with whom never
     * outlives a step; after a restart the step in flight simply does not move a reference.
     */
    private final Map<String, Map<Long, long[]>> pendingPairs = new HashMap<>();
    /** Per item, all-time: units restocked (created) and units destroyed. */
    private final TreeMap<String, long[]> goods = new TreeMap<>();
    /** Commodities whose buy side the daily guard re-check switched off. */
    private final TreeSet<String> driftBuyOff = new TreeSet<>();
    /** Every House that was ever funded, so one that leaves the data still has its credits swept back. */
    private final TreeSet<String> funded = new TreeSet<>();
    private long day = NEVER;
    private long guardDay = NEVER;
    /** The economy-day of the last step's quotes. */
    private long quotedDay = NEVER;
    private long orderSequence;
    private long minTradeValue = HouseSettings.DEFAULTS.minTradeValue();
    private long accountDailyCap = HouseSettings.DEFAULTS.accountDailyCap();

    public HouseSection() { }

    // --- reads -------------------------------------------------------------------------------

    @Override public synchronized boolean isEmpty() {
        return states.isEmpty() && accounts.isEmpty() && referenceCounted.isEmpty() && pending.isEmpty()
                && goods.isEmpty() && funded.isEmpty() && orderSequence == 0L;
    }

    @Override public synchronized void forEachLot(Consumer<Lot> visitor) {
        for (State state : states.values()) for (Map.Entry<String, Long> entry : state.stock.entrySet()) {
            long left = entry.getValue();
            while (left > 0L) {
                int count = (int) Math.min(Integer.MAX_VALUE, left);
                visitor.accept(Lot.of(entry.getKey(), count));
                left -= count;
            }
        }
    }

    /** Units of {@code item} ever restocked by a House: goods that came into being. */
    public synchronized long created(String item) {
        long[] row = goods.get(item);
        return row == null ? 0L : row[0];
    }

    /** Units of {@code item} ever destroyed by a House: bought beyond its stock cap, or left when a commodity went. */
    public synchronized long destroyed(String item) {
        long[] row = goods.get(item);
        return row == null ? 0L : row[1];
    }

    /** How many commodities the Houses keep state for. */
    public synchronized int size() { return states.size(); }

    /** The economy-day the daily counts are for. */
    public synchronized long day() { return day; }

    /** The keys of the commodities whose NPC buy side the daily guard re-check has switched off. */
    public synchronized Set<String> driftBuyOff() { return Set.copyOf(driftBuyOff); }

    /**
     * Drops the memory-only pair tally of the step in flight: exactly what a server stop does to it,
     * because the tally is never saved (who traded with whom must not reach the disk, not even as a
     * hash; see {@link #pendingPairs}). After a stop, clean or crashed, the step in flight therefore
     * moves no reference. The game never calls this, since a stopped section is simply discarded;
     * a test that compares a run that restarted with one that did not calls it at the restart point
     * of the run that did not, so the two differ only in what a restart really changes.
     */
    public synchronized void forgetPairTally() { pendingPairs.clear(); }

    /** The current reference price of a commodity, in milli-credits; -1 when the Houses have no state for it. */
    public synchronized long referenceMillis(String key) {
        State state = key == null ? null : states.get(key);
        return state == null ? -1L : state.refMillis;
    }

    /**
     * A commodity as its House quotes it now.
     *
     * @param referenceMillis the current reference price, in milli-credits
     * @param ask             what the House sells one unit for now, pressure included
     * @param bid             what it pays for one now; 0 = nothing
     * @param sells           whether it sells it at all now
     * @param buys            whether it buys it at all now
     * @param stock           units it holds, in stock and on the book
     * @param askQuantity     units on its resting sell order
     * @param bidQuantity     units its resting buy orders still want
     * @param soldToday       units it sold today
     * @param boughtToday     units it bought today
     */
    public record Quote(String key, String house, long referenceMillis, long ask, long bid, boolean sells,
            boolean buys, long stock, long askQuantity, long bidQuantity, long soldToday, long boughtToday) { }

    /** How {@code key}'s House quotes it now; empty when the Houses have no state for it. */
    public synchronized Optional<Quote> quote(String key) {
        State state = key == null ? null : states.get(key);
        if (state == null) return Optional.empty();
        BookSection book = book();
        long askQuantity = 0L;
        long bidQuantity = 0L;
        if (book != null) {
            SellOrder ask = state.askOrder == null ? null : book.sell(state.askOrder);
            if (ask != null) askQuantity = ask.lot().count();
            for (UUID id : state.bids.values()) {
                BuyOrder bid = book.buy(id);
                if (bid != null) bidQuantity += bid.remaining();
            }
        }
        boolean buys = state.limits.buys() && !driftBuyOff.contains(key);
        return Optional.of(new Quote(state.key, state.house, state.refMillis, askPrice(state), buys ? bidPrice(state) : 0L,
                state.limits.sells(), buys && bidPrice(state) >= 1L, state.stockTotal() + askQuantity, askQuantity,
                bidQuantity, state.sold, state.bought));
    }

    /**
     * What {@code player} may still trade of {@code key} with the Houses today: {@code [buy, sell]}
     * units, the smaller of their own cap and what is left of the commodity's. {@code [0, 0]} for
     * a commodity the Houses have no state for.
     */
    public synchronized long[] remainingToday(UUID player, String key, long now) {
        State state = key == null ? null : states.get(key);
        if (player == null || state == null) return new long[] {0L, 0L};
        long today = EconomySavedData.economyDay(now);
        long[] mine = day == today ? row(player, key, false) : null;
        long sold = day == today ? state.sold : 0L;
        long bought = day == today ? state.bought : 0L;
        long buy = Math.max(0L, Math.min(state.limits.accountBuy() - (mine == null ? 0L : mine[0]),
                state.limits.supply() - sold));
        long sell = Math.max(0L, Math.min(state.limits.accountSell() - (mine == null ? 0L : mine[1]),
                state.limits.demand() - bought));
        return new long[] {state.limits.sells() ? buy : 0L, state.limits.buys() ? sell : 0L};
    }

    /**
     * One House as an operator sees it: aggregates only.
     *
     * @param capital      balance plus the escrow of its open buy orders
     * @param paidToday    credits it paid for goods today
     * @param receivedToday credits it received for goods today, after the market fee
     * @param top          its commodities by credits traded today, most first, at most five
     */
    public record HouseReport(String id, long capital, long balance, long escrowed, long paidToday, long receivedToday,
            List<Volume> top) { }

    /** Credits and units one commodity traded with its House today. */
    public record Volume(String key, long units, long value) { }

    /** Every House that has state or credits, with its aggregates. */
    public synchronized List<HouseReport> report(HouseRegistry houses) {
        Set<String> ids = new TreeSet<>(funded);
        for (State state : states.values()) ids.add(state.house);
        if (houses != null) for (HouseDefinition house : houses.houses()) ids.add(house.id());
        List<HouseReport> out = new ArrayList<>();
        for (String id : ids) {
            long[] capital = capital(id);
            HouseDay today = houseDays.get(id);
            List<Volume> top = new ArrayList<>();
            for (State state : states.values())
                if (state.house.equals(id) && state.value > 0L)
                    top.add(new Volume(state.key, state.sold + state.bought, state.value));
            top.sort(Comparator.comparingLong(Volume::value).reversed().thenComparing(Volume::key));
            out.add(new HouseReport(id, capital[0] + capital[1], capital[0], capital[1], today == null ? 0L : today.paid,
                    today == null ? 0L : today.received, List.copyOf(top.subList(0, Math.min(5, top.size())))));
        }
        return out;
    }

    // --- the book's questions ----------------------------------------------------------------

    @Override public synchronized int allowance(UUID houseOrder, AccountId counterparty, int wanted, long now) {
        if (houseOrder == null) return 0;
        State state = byOrder.get(houseOrder);
        if (state == null || wanted <= 0 || !(counterparty instanceof PlayerAccountId player)) return 0;
        roll(EconomySavedData.economyDay(now));
        long[] mine = row(player.playerId(), state.key, false);
        if (mine == null && accountRows >= MAX_ACCOUNT_ROWS) return 0;
        long room;
        if (houseOrder.equals(state.askOrder))
            room = Math.min(state.limits.accountBuy() - (mine == null ? 0L : mine[0]), state.limits.supply() - state.sold);
        else room = Math.min(state.limits.accountSell() - (mine == null ? 0L : mine[1]),
                state.limits.demand() - state.bought);
        return (int) Math.max(0L, Math.min(wanted, room));
    }

    @Override public synchronized void filled(Fill fill, long now) {
        if (fill == null || fill.quantity() < 1) return;
        roll(EconomySavedData.economyDay(now));
        State selling = fill.sellOrder() == null ? null : byOrder.get(fill.sellOrder());
        State buying = fill.buyOrder() == null ? null : byOrder.get(fill.buyOrder());
        if (selling != null && fill.buyer() instanceof PlayerAccountId player) {
            // A House sold to a player: the ask walks up.
            selling.sold += fill.quantity();
            selling.value = add(selling.value, fill.base());
            selling.askPressure = HousePricing.add(selling.askPressure,
                    HousePricing.pressureFor(selling.limits.elasticity(), fill.quantity(), selling.limits.supply()));
            selling.dirty = true;
            long[] mine = row(player.playerId(), selling.key, true);
            if (mine != null) mine[0] += fill.quantity();
            HouseDay today = houseDays.computeIfAbsent(selling.house, ignored -> new HouseDay());
            today.received = add(today.received, fill.sellerReceives());
            setDirty();
            return;
        }
        if (buying != null && fill.seller() instanceof PlayerAccountId player) {
            // A House bought from a player: the bid walks down.
            buying.bought += fill.quantity();
            buying.value = add(buying.value, fill.base());
            buying.bidPressure = HousePricing.add(buying.bidPressure,
                    HousePricing.pressureFor(buying.limits.elasticity(), fill.quantity(), buying.limits.demand()));
            buying.dirty = true;
            long[] mine = row(player.playerId(), buying.key, true);
            if (mine != null) mine[1] += fill.quantity();
            HouseDay today = houseDays.computeIfAbsent(buying.house, ignored -> new HouseDay());
            today.paid = add(today.paid, fill.base());
            setDirty();
            return;
        }
        if (selling != null || buying != null) return;
        // Between two players: counted toward reference drift, within the caps.
        if (!(fill.seller() instanceof PlayerAccountId seller) || !(fill.buyer() instanceof PlayerAccountId buyer)
                || seller.equals(buyer) || fill.payload() != Lot.PRISTINE || fill.base() < Math.max(1L, minTradeValue))
            return;
        long counted = Math.min(fill.base(), Math.min(referenceRoom(seller.playerId()), referenceRoom(buyer.playerId())));
        if (counted <= 0L) return;
        long[] tally = pending.get(fill.item());
        if (tally == null) {
            if (pending.size() >= MAX_PENDING_ITEMS) return;
            tally = new long[2];
            pending.put(fill.item(), tally);
        }
        long units = counted == fill.base() ? fill.quantity()
                : Math.max(1L, (long) ((double) fill.quantity() * counted / fill.base()));
        tally[0] = add(tally[0], units);
        tally[1] = add(tally[1], counted);
        Map<Long, long[]> pairs = pendingPairs.computeIfAbsent(fill.item(), ignored -> new HashMap<>());
        long pairKey = ReferenceDrift.pairKey(seller.playerId(), buyer.playerId());
        long[] pair = pairs.get(pairKey);
        if (pair == null && pairs.size() < MAX_PAIRS_PER_ITEM) {
            pair = new long[2];
            pairs.put(pairKey, pair);
        }
        if (pair != null) {
            pair[0] = add(pair[0], units);
            pair[1] = add(pair[1], counted);
        }
        referenceCounted.merge(seller.playerId(), counted, HouseSection::add);
        referenceCounted.merge(buyer.playerId(), counted, HouseSection::add);
        setDirty();
    }

    /** Credits of this player's trade that may still count toward reference drift today; 0 when no row can be kept. */
    private long referenceRoom(UUID player) {
        Long used = referenceCounted.get(player);
        if (used == null && referenceCounted.size() >= MAX_REFERENCE_ACCOUNTS) return 0L;
        return Math.max(0L, accountDailyCap - (used == null ? 0L : used));
    }

    // --- the stock ---------------------------------------------------------------------------

    /**
     * Goods the book owes a House: what it bought, or the unsold rest of its sell order. Booked into
     * the stock of the commodity the item belongs to, up to the stock cap; the rest is destroyed.
     * Never refuses.
     */
    public synchronized void deliver(SystemAccountId house, Lot lot) {
        if (house == null || lot == null) return;
        State state = byItem.get(lot.item());
        long keep = 0L;
        if (state != null && lot.pristine() && house.equals(SystemAccountId.house(state.house))) {
            long room = Math.max(0L, state.limits.stockCap() - held(state));
            keep = Math.min(room, lot.count());
            if (keep > 0L) state.stock.merge(lot.item(), keep, HouseSection::add);
        }
        long gone = lot.count() - keep;
        if (gone > 0L) goodsRow(lot.item())[1] += gone;
        setDirty();
    }

    /**
     * Goods that come back to a House outside the book: the lot of an auction nobody bid on, or
     * what a worker delivered for the House's job. They go into the stock of the commodity the item
     * belongs to when that House still trades it — whatever the stock cap, because nothing is
     * thrown away that the House itself sent out or paid for — and are destroyed (and counted)
     * otherwise. Never refuses.
     */
    public synchronized void returnStock(String house, Lot lot) {
        if (house == null || lot == null) return;
        State state = byItem.get(lot.item());
        if (state != null && lot.pristine() && state.house.equals(house)) state.stock.merge(lot.item(), (long) lot.count(), HouseSection::add);
        else goodsRow(lot.item())[1] += lot.count();
        setDirty();
    }

    /**
     * Takes up to {@code count} units of {@code item} out of a commodity's stock for an auction —
     * only from what lies in stock, never from the House's resting sell order.
     *
     * @return the goods taken, or null when there are none
     */
    public synchronized Lot takeStock(String key, String item, int count) {
        State state = key == null ? null : states.get(key);
        if (state == null || item == null || count < 1) return null;
        long held = state.stock.getOrDefault(item, 0L);
        int taken = (int) Math.min(count, held);
        if (taken < 1) return null;
        state.stock.put(item, held - taken);
        setDirty();
        return Lot.of(item, taken);
    }

    /**
     * One commodity as the Houses' auctions and jobs see it.
     *
     * @param item      the item it holds most of in stock, or its first item when it holds none
     * @param stock     units of {@code item} lying in stock (not on the book)
     * @param held      units of the whole commodity in stock and on its resting sell order
     * @param bid       what the House pays for one unit now, pressure included; 0 = nothing
     * @param bidAtReference what it would pay at its reference price, without pressure
     * @param demandLeft units it will still buy today
     */
    public record Position(String key, String house, String item, long stock, long held, long referenceMillis,
            long bid, long bidAtReference, boolean buys, boolean sells, long supply, long demandLeft) { }

    /** Every commodity the Houses keep, in key order. */
    public synchronized List<Position> positions() {
        List<Position> out = new ArrayList<>(states.size());
        for (State state : states.values()) {
            if (state.stock.isEmpty()) continue;
            String item = null;
            long most = 0L;
            for (Map.Entry<String, Long> entry : state.stock.entrySet())
                if (entry.getValue() > most) { most = entry.getValue(); item = entry.getKey(); }
            if (item == null) item = state.stock.firstKey();
            boolean buys = state.limits.buys() && !driftBuyOff.contains(state.key) && bidPrice(state) >= 1L;
            out.add(new Position(state.key, state.house, item, most, held(state), state.refMillis,
                    buys ? bidPrice(state) : 0L, HousePricing.bid(state.refMillis, state.limits.spreadBps()), buys,
                    state.limits.sells(), state.limits.supply(), Math.max(0L, state.limits.demand() - state.bought)));
        }
        return out;
    }

    /** What the book delivers to the Houses' accounts goes to this section's stock. */
    public SystemDelivery delivery() {
        return new SystemDelivery() {
            @Override public boolean handles(SystemAccountId account) { return account != null && account.isHouse(); }
            @Override public void deliver(SystemAccountId account, Lot lot) { HouseSection.this.deliver(account, lot); }
        };
    }

    /** Units of the commodity the House holds: in stock and on its resting sell order. */
    private long held(State state) {
        long total = state.stockTotal();
        BookSection book = book();
        SellOrder ask = book == null || state.askOrder == null ? null : book.sell(state.askOrder);
        return ask == null ? total : total + ask.lot().count();
    }

    private GoodsOrigin origin(State state) {
        return new GoodsOrigin() {
            @Override public boolean holds(Lot lot) {
                return lot.pristine() && state.stock.getOrDefault(lot.item(), 0L) >= lot.count();
            }
            @Override public boolean take(Lot lot) {
                if (!holds(lot)) return false;
                long left = state.stock.get(lot.item()) - lot.count();
                state.stock.put(lot.item(), left);
                setDirty();
                return true;
            }
            @Override public void restore(Lot lot) {
                state.stock.merge(lot.item(), (long) lot.count(), HouseSection::add);
                setDirty();
            }
        };
    }

    private GoodsReceiver receiver(String house) {
        SystemAccountId account = SystemAccountId.house(house);
        return new GoodsReceiver() {
            @Override public boolean canTake(Lot lot) { return true; }
            @Override public boolean give(Lot lot) {
                deliver(account, lot);
                return false;
            }
        };
    }

    // --- the step ----------------------------------------------------------------------------

    /**
     * One simulation step of the Merchant Houses. Does nothing while the economy is frozen or when
     * the commodity data could not be read at all.
     *
     * @param today      the economy-day of the step
     * @param stepMillis the length of a step
     */
    public synchronized void step(HouseEnv env, long today, long stepMillis) {
        EconomyStore store = owner();
        // Nor while an operator has closed the market: the Houses neither restock nor quote until it opens.
        if (store == null || env == null || EconomyHealth.frozen() || marketClosed() || !env.commodities().available())
            return;
        roll(today);
        boolean newDay = quotedDay == NEVER || today > quotedDay;
        quotedDay = today;
        minTradeValue = env.settings().minTradeValue();
        accountDailyCap = env.settings().accountDailyCap();
        sync(env);
        boolean moved = drift(env);
        if (guardDay == NEVER || today > guardDay) {
            guardDay = today;
            recheck(env);
        } else if (moved) {
            // N2: a reference that moved may open a recipe chain; it is checked now, not tomorrow.
            recheck(env);
        }
        for (State state : states.values()) {
            state.askPressure = HousePricing.decay(state.askPressure, state.limits.recovery());
            state.bidPressure = HousePricing.decay(state.bidPressure, state.limits.recovery());
            restock(state, stepMillis);
        }
        fund(env);
        // On a new day every order is placed afresh, so the caps that start again are matched against
        // what rests on the book; otherwise only what changed is replaced.
        for (State state : states.values()) requote(env, state, newDay);
        sweep(env);
        setDirty();
    }

    /** Re-quotes up to {@code max} commodities that were traded since they were last quoted. @return how many */
    public synchronized int requoteDirty(HouseEnv env, int max) {
        if (owner() == null || env == null || EconomyHealth.frozen() || marketClosed()) return 0;
        int done = 0;
        for (State state : states.values()) {
            if (done >= max) break;
            if (!state.dirty) continue;
            requote(env, state, false);
            done++;
        }
        return done;
    }

    /** Whether any commodity waits to be re-quoted. */
    public synchronized boolean hasDirty() {
        for (State state : states.values()) if (state.dirty) return true;
        return false;
    }

    /** States follow the commodity data: new ones are made, changed ones updated, gone ones closed. */
    private void sync(HouseEnv env) {
        CommodityRegistry registry = env.commodities();
        Set<String> live = new HashSet<>();
        for (CommodityDefinition commodity : registry.commodities()) {
            if (!commodity.npcSells() && !commodity.npcBuys()) continue;
            HouseDefinition house = env.houses().houseFor(commodity);
            State state = states.get(commodity.key());
            if (state == null) {
                if (states.size() >= MAX_COMMODITIES) continue;
                state = new State(commodity.key());
                state.house = house.id();
                state.dataReference = commodity.referencePrice();
                state.refMillis = Math.multiplyExact(commodity.referencePrice(), HousePricing.MILLI);
                state.dayOpen = state.refMillis;
                states.put(commodity.key(), state);
            }
            if (!state.house.equals(house.id())) {
                // The data gave the commodity to another House: the old one's orders go.
                closeOrders(env, state);
                state.house = house.id();
            }
            if (state.dataReference != commodity.referencePrice()) {
                state.dataReference = commodity.referencePrice();
                state.refMillis = Math.multiplyExact(commodity.referencePrice(), HousePricing.MILLI);
                state.dayOpen = state.refMillis;
            }
            state.limits = Limits.of(commodity, house, registry.npcSells(commodity), registry.npcBuys(commodity));
            List<String> items = registry.itemsOf(commodity.key());
            for (int index = 0; index < items.size() && index < MAX_ITEMS; index++)
                state.stock.putIfAbsent(items.get(index), 0L);
            live.add(commodity.key());
        }
        for (State state : new ArrayList<>(states.values())) {
            if (live.contains(state.key)) continue;
            // The commodity left the data: its orders close and what the House held is destroyed.
            closeOrders(env, state);
            for (Map.Entry<String, Long> entry : state.stock.entrySet())
                if (entry.getValue() > 0L) goodsRow(entry.getKey())[1] += entry.getValue();
            states.remove(state.key);
        }
        index();
    }

    /**
     * E3: each reference moves toward what players paid each other since the last step, counted per
     * pair of players (N1: too few pairs move nothing, no pair outweighs the rest) and within the
     * day's reach. @return whether any reference moved
     */
    private boolean drift(HouseEnv env) {
        if (pending.isEmpty()) {
            pendingPairs.clear();
            return false;
        }
        HouseSettings settings = env.settings();
        Map<State, long[]> totals = new LinkedHashMap<>();
        for (String item : pending.keySet()) {
            CommodityDefinition commodity = env.commodities().forItem(item);
            State state = commodity == null ? null : states.get(commodity.key());
            if (state == null) continue;
            Map<Long, long[]> pairs = pendingPairs.get(item);
            long[] counted = ReferenceDrift.counted(pairs == null ? List.of() : pairs.values(), settings.minPairs());
            long[] total = totals.computeIfAbsent(state, ignored -> new long[2]);
            total[0] = add(total[0], counted[0]);
            total[1] = add(total[1], counted[1]);
        }
        pending.clear();
        pendingPairs.clear();
        boolean moved = false;
        for (Map.Entry<State, long[]> entry : totals.entrySet()) {
            State state = entry.getKey();
            long[] total = entry.getValue();
            ReferenceDrift.Bounds bounds = ReferenceDrift.bounds(state.dataReference, settings.driftStepBps(),
                    settings.bandMinBps(), settings.bandMaxBps()).today(state.dayOpen, settings.driftDayBps());
            long next = ReferenceDrift.next(state.refMillis, total[0], total[1],
                    ReferenceDrift.liquidity(state.dataReference, state.limits.supply(), state.limits.demand()), bounds);
            if (next != state.refMillis) moved = true;
            state.refMillis = next;
        }
        return moved;
    }

    /** The arbitrage guard again, at the references as they have drifted. */
    private void recheck(HouseEnv env) {
        Map<String, Long> references = new HashMap<>();
        for (State state : states.values()) references.put(state.key, state.refMillis);
        ArbitrageGuard.Quotes quotes = new ArbitrageGuard.Quotes() {
            @Override public long ask(CommodityDefinition commodity) {
                Long reference = references.get(commodity.key());
                return HousePricing.ask(reference != null ? reference : commodity.referencePrice() * HousePricing.MILLI,
                        commodity.spreadBps());
            }
            @Override public long bid(CommodityDefinition commodity) {
                Long reference = references.get(commodity.key());
                return HousePricing.bid(reference != null ? reference : commodity.referencePrice() * HousePricing.MILLI,
                        commodity.spreadBps());
            }
        };
        Set<String> off;
        try {
            off = env.guard().buyOff(quotes);
        } catch (RuntimeException failure) {
            // Nothing could be proved: keep what the last check found.
            return;
        }
        driftBuyOff.clear();
        if (off != null) for (String key : off) if (states.containsKey(key)) driftBuyOff.add(key);
    }

    private static void restockInto(State state, long units) {
        String first = state.stock.isEmpty() ? null : state.stock.firstKey();
        if (first != null && units > 0L) state.stock.merge(first, units, HouseSection::add);
    }

    private void restock(State state, long stepMillis) {
        if (!state.limits.sells() || state.limits.supply() <= 0L || state.stock.isEmpty()) return;
        double perStep = state.limits.supply() * state.limits.restockPerDay() * stepMillis / DAY_MILLIS;
        long units = (long) Math.ceil(perStep);
        long room = state.limits.supply() - held(state);
        units = Math.min(units, room);
        if (units <= 0L) return;
        restockInto(state, units);
        goodsRow(state.stock.firstKey())[0] += units;
    }

    // --- money -------------------------------------------------------------------------------

    /** {@code [balance, escrow of open buy orders]} of one House. */
    private long[] capital(String house) {
        EconomyStore store = owner();
        if (store == null) return new long[] {0L, 0L};
        long balance = store.ledger().balance(SystemAccountId.house(house));
        long escrowed = 0L;
        BookSection book = store.book();
        for (State state : states.values()) {
            if (!state.house.equals(house)) continue;
            for (UUID id : state.bids.values()) {
                BuyOrder bid = book.buy(id);
                if (bid != null) escrowed = add(escrowed, bid.escrow());
            }
        }
        return new long[] {balance, escrowed};
    }

    /** Each House is topped up toward its working capital, pro rata when the budget or the Reserve is short. */
    private void fund(HouseEnv env) {
        EconomyStore store = owner();
        Map<AccountId, Long> shortfalls = new LinkedHashMap<>();
        for (String id : housesInUse(env)) {
            HouseDefinition house = env.houses().get(id);
            if (house == null) continue;
            long[] capital = capital(id);
            long short_ = house.workingCapital() - add(capital[0], capital[1]);
            if (short_ > 0L) shortfalls.put(SystemAccountId.house(id), short_);
        }
        if (shortfalls.isEmpty()) return;
        EconomySavedData ledger = store.ledger();
        long available = Math.min(ledger.budgetRemaining(BudgetKind.NPC), ledger.balance(SystemAccountId.RESERVE));
        Reserve.ProRata split = Reserve.shares(shortfalls, available);
        split.shares().forEach((account, amount) -> {
            if (amount <= 0L) return;
            if (store.execute(LedgerPlan.pay(BudgetKind.NPC, account, amount, TransactionType.HOUSE_FUNDING)).ok()
                    && account instanceof SystemAccountId system)
                funded.add(system.value().substring("house/".length()));
        });
    }

    /** Whatever a House holds above its working capital goes back to the Reserve; a House no longer in the data keeps nothing. */
    private void sweep(HouseEnv env) {
        EconomyStore store = owner();
        Set<String> ids = new TreeSet<>(funded);
        ids.addAll(housesInUse(env));
        for (String id : ids) {
            HouseDefinition house = env.houses().get(id);
            long cap = house == null ? 0L : house.workingCapital();
            long[] capital = capital(id);
            long excess = Math.min(capital[0], add(capital[0], capital[1]) - cap);
            if (excess > 0L) store.execute(LedgerPlan.of(SystemAccountId.house(id), SystemAccountId.RESERVE, excess,
                    TransactionType.HOUSE_SWEEP));
            if (house == null && capital[1] == 0L && store.ledger().balance(SystemAccountId.house(id)) == 0L)
                funded.remove(id);
        }
    }

    private Set<String> housesInUse(HouseEnv env) {
        Set<String> ids = new TreeSet<>();
        for (State state : states.values()) ids.add(state.house);
        return ids;
    }

    // --- quoting -----------------------------------------------------------------------------

    private static long askPrice(State state) {
        return HousePricing.askWithPressure(HousePricing.ask(state.refMillis, state.limits.spreadBps()), state.askPressure);
    }

    private static long bidPrice(State state) {
        return HousePricing.bidWithPressure(HousePricing.bid(state.refMillis, state.limits.spreadBps()), state.bidPressure);
    }

    private static long chunk(long dailyCap) {
        return Math.max(1L, Math.min(BookSection.MAX_ORDER_QUANTITY, Math.ceilDiv(Math.max(1L, dailyCap), (long) CHUNKS)));
    }

    /** Puts the commodity's orders on the book as they should be now, changing only what differs. */
    private void requote(HouseEnv env, State state, boolean afresh) {
        state.dirty = false;
        EconomyStore store = owner();
        CommodityDefinition commodity = env.commodities().get(state.key);
        if (store == null || commodity == null) return;
        BookSection book = store.book();
        TradeEnv trade = env.trade();
        SystemAccountId account = SystemAccountId.house(state.house);
        long now = store.ledger().now();

        // The sell side: one order, on the item the House holds most of.
        long ask = askPrice(state);
        SellOrder current = state.askOrder == null ? null : book.sell(state.askOrder);
        String item = richest(state, current);
        long wanted = 0L;
        if (state.limits.sells() && item != null) {
            long available = state.stock.getOrDefault(item, 0L)
                    + (current != null && current.item().equals(item) ? current.lot().count() : 0L);
            wanted = Math.min(Math.min(chunk(state.limits.supply()), available), state.limits.supply() - state.sold);
        }
        boolean keep = !afresh && current != null && wanted > 0L && current.item().equals(item) && current.unitPrice() == ask
                && current.lot().count() == wanted && current.expiresAt() > now;
        if (!keep) {
            if (current != null) book.cancel(trade, current.id(), account);
            forgetOrder(state.askOrder);
            state.askOrder = null;
            if (wanted > 0L) {
                long left = state.stock.getOrDefault(item, 0L);
                int quantity = (int) Math.min(wanted, left);
                if (quantity > 0) {
                    UUID id = nextOrderId(book);
                    state.askOrder = id;
                    byOrder.put(id, state);
                    Placed placed = book.placeSell(trade, account, Lot.of(item, quantity), ask, OrderSource.HOUSE,
                            origin(state), id);
                    if (!placed.ok() || placed.rested() <= 0) {
                        forgetOrder(id);
                        if (id.equals(state.askOrder)) state.askOrder = null;
                    }
                }
            }
        }

        // The buy side: one order per covered item, all sharing the day's demand and the capital.
        boolean buys = state.limits.buys() && !driftBuyOff.contains(state.key);
        long bid = buys ? bidPrice(state) : 0L;
        List<String> items = new ArrayList<>(state.stock.keySet());
        for (String covered : new ArrayList<>(state.bids.keySet())) if (!items.contains(covered)) items.add(covered);
        for (String covered : items) {
            UUID existing = state.bids.get(covered);
            BuyOrder resting = existing == null ? null : book.buy(existing);
            long balance = store.ledger().balance(account) + (resting != null ? resting.escrow() : 0L);
            long want = 0L;
            if (buys && bid >= 1L && state.stock.containsKey(covered)) {
                want = Math.min(chunk(state.limits.demand()), state.limits.demand() - state.bought);
                want = Math.min(want, balance / bid);
            }
            boolean same = !afresh && resting != null && want > 0L && resting.unitPrice() == bid && resting.remaining() == want
                    && resting.expiresAt() > now;
            if (same) continue;
            if (resting != null) book.cancel(trade, resting.id(), account);
            forgetOrder(existing);
            state.bids.remove(covered);
            want = Math.min(want, bid >= 1L ? store.ledger().balance(account) / bid : 0L);
            if (want <= 0L) continue;
            UUID id = nextOrderId(book);
            state.bids.put(covered, id);
            byOrder.put(id, state);
            Placed placed = book.placeBuy(trade, account, covered, (int) want, bid, OrderSource.HOUSE, false,
                    receiver(state.house), id);
            if (!placed.ok() || placed.rested() <= 0) {
                forgetOrder(id);
                if (id.equals(state.bids.get(covered))) state.bids.remove(covered);
            }
        }
        setDirty();
    }

    /** The item a House sells: the one it holds most of, the current order's goods counted; first by id on a tie. */
    private static String richest(State state, SellOrder current) {
        String best = null;
        long most = 0L;
        for (Map.Entry<String, Long> entry : state.stock.entrySet()) {
            long count = entry.getValue()
                    + (current != null && current.item().equals(entry.getKey()) ? current.lot().count() : 0L);
            if (count > most) {
                most = count;
                best = entry.getKey();
            }
        }
        return best;
    }

    /** Takes every House order of a commodity off the book: goods back to stock, escrow back to the House. */
    private void closeOrders(HouseEnv env, State state) {
        BookSection book = book();
        SystemAccountId account = SystemAccountId.house(state.house);
        if (book != null && state.askOrder != null && book.sell(state.askOrder) != null)
            book.cancel(env.trade(), state.askOrder, account);
        forgetOrder(state.askOrder);
        state.askOrder = null;
        for (UUID id : state.bids.values()) {
            if (book != null && book.buy(id) != null) book.cancel(env.trade(), id, account);
            forgetOrder(id);
        }
        state.bids.clear();
    }

    private void forgetOrder(UUID id) { if (id != null) byOrder.remove(id); }

    private UUID nextOrderId(BookSection book) {
        UUID id;
        do id = new UUID(ORDER_ID_HIGH, ++orderSequence);
        while (book.sell(id) != null || book.buy(id) != null || byOrder.containsKey(id));
        return id;
    }

    // --- days, rows, indexes -----------------------------------------------------------------

    /** A new economy-day: every daily count starts again and every per-account row goes. @return whether it rolled */
    private boolean roll(long today) {
        if (day != NEVER && today <= day) return false;
        day = today;
        for (State state : states.values()) {
            state.sold = 0L;
            state.bought = 0L;
            state.value = 0L;
            state.dayOpen = state.refMillis;
        }
        houseDays.clear();
        accounts.clear();
        accountRows = 0;
        referenceCounted.clear();
        setDirty();
        return true;
    }

    private long[] row(UUID player, String key, boolean create) {
        TreeMap<String, long[]> rows = accounts.get(player);
        long[] row = rows == null ? null : rows.get(key);
        if (row != null || !create || accountRows >= MAX_ACCOUNT_ROWS) return row;
        row = new long[2];
        accounts.computeIfAbsent(player, ignored -> new TreeMap<>()).put(key, row);
        accountRows++;
        return row;
    }

    private long[] goodsRow(String item) { return goods.computeIfAbsent(item, ignored -> new long[2]); }

    private void index() {
        byOrder.clear();
        byItem.clear();
        for (State state : states.values()) {
            if (state.askOrder != null) byOrder.put(state.askOrder, state);
            for (UUID id : state.bids.values()) byOrder.put(id, state);
            for (String item : state.stock.keySet()) byItem.putIfAbsent(item, state);
        }
    }

    private BookSection book() {
        EconomyStore store = owner();
        return store == null ? null : store.book();
    }

    private static long add(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    // --- erasure and export ------------------------------------------------------------------

    /** Erases what the Houses recorded about one player: today's trades with them and the drift count. */
    public synchronized void forgetPlayer(UUID player) {
        if (player == null) return;
        TreeMap<String, long[]> rows = accounts.remove(player);
        if (rows != null) accountRows -= rows.size();
        if (referenceCounted.remove(player) != null || rows != null) setDirty();
    }

    /** Data-subject export: what the player traded with the Houses today, and what of their trade counted toward drift. */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        out.addProperty("economy_day", day == NEVER ? 0L : day);
        JsonArray rows = new JsonArray();
        TreeMap<String, long[]> mine = player == null ? null : accounts.get(player);
        if (mine != null) mine.forEach((key, row) -> {
            JsonObject value = new JsonObject();
            value.addProperty("commodity", key);
            value.addProperty("bought_from_houses", row[0]);
            value.addProperty("sold_to_houses", row[1]);
            rows.add(value);
        });
        out.add("traded_with_houses_today", rows);
        Long counted = player == null ? null : referenceCounted.get(player);
        out.addProperty("counted_toward_reference_prices_today", counted == null ? 0L : counted);
        return out;
    }

    // --- persistence -------------------------------------------------------------------------

    private record Count(String item, long count) {
        static final Codec<Count> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("item").forGetter(Count::item),
                Codec.LONG.fieldOf("count").forGetter(Count::count)).apply(instance, Count::new));
    }

    private record Bid(String item, String order) {
        static final Codec<Bid> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("item").forGetter(Bid::item),
                Codec.STRING.fieldOf("order").forGetter(Bid::order)).apply(instance, Bid::new));
    }

    private record StateRow(String key, String house, long dataReference, long refMillis, List<Count> stock, long sold,
            long bought, long value, long askPressure, long bidPressure, Limits limits, Optional<String> askOrder,
            List<Bid> bids, boolean dirty, Optional<Long> dayOpen) {
        static final Codec<StateRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("key").forGetter(StateRow::key),
                Codec.STRING.fieldOf("house").forGetter(StateRow::house),
                Codec.LONG.fieldOf("data_reference").forGetter(StateRow::dataReference),
                Codec.LONG.fieldOf("reference_millis").forGetter(StateRow::refMillis),
                Count.CODEC.listOf().optionalFieldOf("stock", List.of()).forGetter(StateRow::stock),
                Codec.LONG.optionalFieldOf("sold", 0L).forGetter(StateRow::sold),
                Codec.LONG.optionalFieldOf("bought", 0L).forGetter(StateRow::bought),
                Codec.LONG.optionalFieldOf("value", 0L).forGetter(StateRow::value),
                Codec.LONG.optionalFieldOf("ask_pressure", 0L).forGetter(StateRow::askPressure),
                Codec.LONG.optionalFieldOf("bid_pressure", 0L).forGetter(StateRow::bidPressure),
                Limits.CODEC.optionalFieldOf("limits", Limits.NONE).forGetter(StateRow::limits),
                Codec.STRING.optionalFieldOf("ask_order").forGetter(StateRow::askOrder),
                Bid.CODEC.listOf().optionalFieldOf("bids", List.of()).forGetter(StateRow::bids),
                Codec.BOOL.optionalFieldOf("dirty", false).forGetter(StateRow::dirty),
                Codec.LONG.optionalFieldOf("day_open").forGetter(StateRow::dayOpen))
                .apply(instance, StateRow::new));
    }

    private record DayRow(String house, long paid, long received) {
        static final Codec<DayRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("house").forGetter(DayRow::house),
                Codec.LONG.optionalFieldOf("paid", 0L).forGetter(DayRow::paid),
                Codec.LONG.optionalFieldOf("received", 0L).forGetter(DayRow::received)).apply(instance, DayRow::new));
    }

    private record AccountRow(String player, String key, long bought, long sold) {
        static final Codec<AccountRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(AccountRow::player),
                Codec.STRING.fieldOf("key").forGetter(AccountRow::key),
                Codec.LONG.optionalFieldOf("bought", 0L).forGetter(AccountRow::bought),
                Codec.LONG.optionalFieldOf("sold", 0L).forGetter(AccountRow::sold)).apply(instance, AccountRow::new));
    }

    private record Tally(String id, long first, long second) {
        static final Codec<Tally> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Tally::id),
                Codec.LONG.optionalFieldOf("a", 0L).forGetter(Tally::first),
                Codec.LONG.optionalFieldOf("b", 0L).forGetter(Tally::second)).apply(instance, Tally::new));
    }

    private record Settings(long minTradeValue, long accountDailyCap, long orderSequence, long day, long guardDay,
            long quotedDay) {
        static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("min_trade_value", HouseSettings.DEFAULTS.minTradeValue())
                        .forGetter(Settings::minTradeValue),
                Codec.LONG.optionalFieldOf("account_daily_cap", HouseSettings.DEFAULTS.accountDailyCap())
                        .forGetter(Settings::accountDailyCap),
                Codec.LONG.optionalFieldOf("order_sequence", 0L).forGetter(Settings::orderSequence),
                Codec.LONG.optionalFieldOf("day", NEVER).forGetter(Settings::day),
                Codec.LONG.optionalFieldOf("guard_day", NEVER).forGetter(Settings::guardDay),
                Codec.LONG.optionalFieldOf("quoted_day", NEVER).forGetter(Settings::quotedDay))
                .apply(instance, Settings::new));
    }

    private static Codec<HouseSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Settings.CODEC.optionalFieldOf("settings", new Settings(HouseSettings.DEFAULTS.minTradeValue(),
                        HouseSettings.DEFAULTS.accountDailyCap(), 0L, NEVER, NEVER, NEVER)).forGetter(HouseSection::settingsRow),
                StateRow.CODEC.listOf().optionalFieldOf("commodities", List.of()).forGetter(HouseSection::stateRows),
                DayRow.CODEC.listOf().optionalFieldOf("houses_today", List.of()).forGetter(HouseSection::dayRows),
                AccountRow.CODEC.listOf().optionalFieldOf("accounts_today", List.of()).forGetter(HouseSection::accountRows),
                Tally.CODEC.listOf().optionalFieldOf("reference_counted_today", List.of())
                        .forGetter(HouseSection::referenceRows),
                Tally.CODEC.listOf().optionalFieldOf("reference_pending", List.of()).forGetter(HouseSection::pendingRows),
                Tally.CODEC.listOf().optionalFieldOf("goods_all_time", List.of()).forGetter(HouseSection::goodsRows),
                Codec.STRING.listOf().optionalFieldOf("drift_buy_off", List.of())
                        .forGetter(section -> List.copyOf(section.driftBuyOff)),
                Codec.STRING.listOf().optionalFieldOf("funded", List.of()).forGetter(section -> List.copyOf(section.funded)))
                .apply(instance, HouseSection::fromRows));
    }

    private synchronized Settings settingsRow() {
        return new Settings(minTradeValue, accountDailyCap, orderSequence, day, guardDay, quotedDay);
    }

    private synchronized List<StateRow> stateRows() {
        List<StateRow> rows = new ArrayList<>(states.size());
        for (State state : states.values()) {
            List<Count> stock = new ArrayList<>();
            state.stock.forEach((item, count) -> stock.add(new Count(item, count)));
            List<Bid> bids = new ArrayList<>();
            state.bids.forEach((item, id) -> bids.add(new Bid(item, id.toString())));
            rows.add(new StateRow(state.key, state.house, state.dataReference, state.refMillis, stock, state.sold,
                    state.bought, state.value, state.askPressure, state.bidPressure, state.limits,
                    Optional.ofNullable(state.askOrder).map(UUID::toString), bids, state.dirty,
                    // Written only when the day has moved the reference, so an unchanged store saves as before.
                    state.dayOpen == state.refMillis ? Optional.empty() : Optional.of(state.dayOpen)));
        }
        return rows;
    }

    private synchronized List<DayRow> dayRows() {
        List<DayRow> rows = new ArrayList<>();
        houseDays.forEach((id, today) -> rows.add(new DayRow(id, today.paid, today.received)));
        return rows;
    }

    private synchronized List<AccountRow> accountRows() {
        List<AccountRow> rows = new ArrayList<>();
        accounts.forEach((player, keys) -> keys.forEach((key, row) ->
                rows.add(new AccountRow(player.toString(), key, row[0], row[1]))));
        return rows;
    }

    private synchronized List<Tally> referenceRows() {
        List<Tally> rows = new ArrayList<>();
        referenceCounted.forEach((player, value) -> rows.add(new Tally(player.toString(), value, 0L)));
        return rows;
    }

    private synchronized List<Tally> pendingRows() {
        List<Tally> rows = new ArrayList<>();
        pending.forEach((item, tally) -> rows.add(new Tally(item, tally[0], tally[1])));
        return rows;
    }

    private synchronized List<Tally> goodsRows() {
        List<Tally> rows = new ArrayList<>();
        goods.forEach((item, tally) -> rows.add(new Tally(item, tally[0], tally[1])));
        return rows;
    }

    private static HouseSection fromRows(Settings settings, List<StateRow> stateRows, List<DayRow> dayRows,
            List<AccountRow> accountRows, List<Tally> referenceRows, List<Tally> pendingRows, List<Tally> goodsRows,
            List<String> buyOff, List<String> funded) {
        HouseSection section = new HouseSection();
        section.minTradeValue = Math.max(0L, settings.minTradeValue());
        section.accountDailyCap = Math.max(0L, settings.accountDailyCap());
        section.orderSequence = Math.max(0L, settings.orderSequence());
        section.day = settings.day();
        section.guardDay = settings.guardDay();
        section.quotedDay = settings.quotedDay();
        // A row that is not valid is skipped; every other row still loads.
        for (StateRow row : stateRows) {
            if (row.key() == null || !CommodityDefinition.validKey(row.key()) || !CommodityDefinition.validHouse(row.house())
                    || section.states.containsKey(row.key()) || section.states.size() >= MAX_COMMODITIES) continue;
            State state = new State(row.key());
            state.house = row.house();
            state.dataReference = Math.max(1L, row.dataReference());
            state.refMillis = Math.max(1L, row.refMillis());
            for (Count count : row.stock())
                if (count.item() != null && !count.item().isBlank() && state.stock.size() < MAX_ITEMS)
                    state.stock.put(count.item(), Math.max(0L, count.count()));
            state.sold = Math.max(0L, row.sold());
            state.bought = Math.max(0L, row.bought());
            state.value = Math.max(0L, row.value());
            state.askPressure = Math.max(0L, Math.min(HousePricing.MAX_PRESSURE, row.askPressure()));
            state.bidPressure = Math.max(0L, Math.min(HousePricing.MAX_PRESSURE, row.bidPressure()));
            state.limits = row.limits();
            state.dirty = row.dirty();
            // Saves from before the daily reach start the day where the reference is now.
            state.dayOpen = row.dayOpen().filter(open -> open >= 1L).orElse(state.refMillis);
            state.askOrder = row.askOrder().map(HouseSection::uuid).orElse(null);
            for (Bid bid : row.bids()) {
                UUID id = uuid(bid.order());
                if (id != null && bid.item() != null) state.bids.put(bid.item(), id);
            }
            section.states.put(state.key, state);
        }
        for (DayRow row : dayRows) {
            if (row.house() == null) continue;
            HouseDay today = new HouseDay();
            today.paid = Math.max(0L, row.paid());
            today.received = Math.max(0L, row.received());
            section.houseDays.put(row.house(), today);
        }
        for (AccountRow row : accountRows) {
            UUID player = uuid(row.player());
            if (player == null || row.key() == null || section.accountRows >= MAX_ACCOUNT_ROWS) continue;
            long[] counts = section.row(player, row.key(), true);
            if (counts == null) continue;
            counts[0] = Math.max(0L, row.bought());
            counts[1] = Math.max(0L, row.sold());
        }
        for (Tally row : referenceRows) {
            UUID player = uuid(row.id());
            if (player != null && section.referenceCounted.size() < MAX_REFERENCE_ACCOUNTS)
                section.referenceCounted.put(player, Math.max(0L, row.first()));
        }
        for (Tally row : pendingRows)
            if (row.id() != null && section.pending.size() < MAX_PENDING_ITEMS)
                section.pending.put(row.id(), new long[] {Math.max(0L, row.first()), Math.max(0L, row.second())});
        for (Tally row : goodsRows)
            if (row.id() != null) section.goods.put(row.id(), new long[] {Math.max(0L, row.first()), Math.max(0L, row.second())});
        for (String key : buyOff) if (key != null) section.driftBuyOff.add(key);
        for (String id : funded) if (CommodityDefinition.validHouse(id)) section.funded.add(id);
        section.index();
        return section;
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
