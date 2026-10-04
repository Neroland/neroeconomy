package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * The exchange section of the economy store: the price state of every company that is or was
 * listed, and the index fund's level.
 *
 * <p><b>What is here.</b> One {@link StockState} per company, keyed by its id, the
 * {@link IndexState}, and the last step the price model ran. Which companies exist and how they
 * behave is data ({@link StockRegistry}); this section only remembers where each price has got to.
 * Beside the prices it keeps what accounts hold: the real-money desk ({@link #desk()}), the
 * practice book ({@link #practice()}) and what the last dividend day came to.
 *
 * <p><b>Listing and delisting.</b> {@link #reconcile} brings the section in line with the
 * registry: a company the section has not seen starts at its start price; one whose data file has
 * gone is <em>delisted</em> — its state stays, frozen at its last price and flagged, so its holders
 * can be paid out at that price before {@link #removeDelisted} drops it; one that comes back
 * carries on from where it stopped.
 *
 * <p><b>One step.</b> {@link #step} moves every listed company through {@link PriceModel} and then
 * the index. It is the only thing that changes a price. A step index at or below the last one run
 * is ignored, so no step can ever be applied twice.
 *
 * <p><b>Share splits.</b> At the roll into a new economy-day a company whose price is at or above
 * 2000 credits splits 10-for-1, and the index fund does at 20000: the price and its history are
 * divided by ten and every holding's units, real and practice, are multiplied by ten in the same
 * operation, at the cost they had. It depends on nothing but the price and the day, so a replayed
 * step splits exactly as a live one.
 *
 * <p><b>Bounds.</b> At most {@link #MAX_STATES} companies are remembered, listed and delisted
 * together; a company that would exceed it is not listed. Each state's history is two fixed rings.
 * Portfolios are bounded by {@link ExchangeDesk} and {@link PracticeBook}.
 * <b>Purge.</b> {@link #removeDelisted}, {@link #settle}. <b>Erasure</b> {@link #forgetPlayer}.
 * <b>Export</b> {@link #exportFor}. The price state itself is about no player.
 *
 * <p><b>Invariant C.</b> {@link #held()} is what the {@code system:exchange} account must hold; the
 * store checks the two against each other.
 */
public final class ExchangeSection extends EconomySection {
    public static final Codec<ExchangeSection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // Every field is optional with a safe default: a store saved before the exchange existed
            // loads as one where nothing is listed yet.
            Codec.LONG.optionalFieldOf("last_step", StockState.NEVER).forGetter(section -> section.lastStep),
            StockState.CODEC.listOf().optionalFieldOf("stocks", List.of()).forGetter(ExchangeSection::stateRows),
            IndexState.CODEC.optionalFieldOf("index").forGetter(ExchangeSection::indexRow),
            // Added with trading, so optional: a store saved before it loads with nobody holding anything.
            ExchangeDesk.Row.CODEC.listOf().optionalFieldOf("portfolios", List.of())
                    .forGetter(section -> section.desk.rows()),
            PracticeBook.Row.CODEC.listOf().optionalFieldOf("practice", List.of())
                    .forGetter(section -> section.practice.rows()),
            DividendRun.CODEC.optionalFieldOf("dividends", DividendRun.NONE).forGetter(section -> section.dividends))
            .apply(instance, ExchangeSection::fromData));
    /** The most companies remembered, listed and delisted together. */
    public static final int MAX_STATES = 256;

    /** What a step needs from outside the section, per company. A test supplies its own. */
    public interface Inputs {
        /** A standard normal number for this company in this step. */
        double gaussian(String stockId);

        /** Credits of counted trade in the company's linked commodities since the step before. */
        default long volume(StockDefinition definition) { return 0L; }

        /** The active market-shock multiplier for the company; 1 for none. */
        default double shock(StockDefinition definition) { return 1.0D; }
    }

    /**
     * A purchase older than this counts for every dividend still to come even when no dividend day
     * has been paid for a while: the simulation never replays more than three days.
     */
    private static final long SEASONED_AFTER_MILLIS = 4L * PriceModel.DAY_MILLIS;

    private final Map<String, StockState> states = new LinkedHashMap<>();
    private IndexState index = new IndexState();
    private long lastStep = StockState.NEVER;
    /** Every account's real positions; the only code on the exchange that moves a credit. */
    private final ExchangeDesk desk = new ExchangeDesk(this);
    /** Practice mode. It is handed this narrow view of the section and nothing else. */
    private final PracticeBook practice = new PracticeBook(new PracticeBook.Market() {
        @Override public Listing listing(ExchangeEnv env, String stockId) { return ExchangeSection.this.listing(env, stockId); }
        @Override public long now() { return ExchangeSection.this.now(); }
        @Override public long seasonedBefore(long now) { return ExchangeSection.this.seasonedBefore(now); }
        @Override public long[] dividendTerms(StockRegistry registry, String stockId) {
            return ExchangeSection.this.dividendTerms(registry, stockId);
        }
        @Override public boolean frozen() { return EconomyHealth.frozen(); }
        @Override public void changed() { setDirty(); }
    }, this);
    /** What the last dividend day came to. */
    private DividendRun dividends = DividendRun.NONE;

    public ExchangeSection() { }

    /** The exchange section of the world's economy store. */
    public static ExchangeSection get(MinecraftServer server) { return EconomyStore.get(server).exchange(); }

    // --- listing -----------------------------------------------------------------------------

    /**
     * Brings the section in line with the listed companies: new ones start at their start price,
     * missing ones are delisted and keep their state, returning ones carry on.
     *
     * @return whether anything changed
     */
    public synchronized boolean reconcile(StockRegistry registry) {
        if (registry == null || !registry.available()) return false;
        boolean changed = false;
        for (StockDefinition definition : registry.stocks()) {
            StockState state = states.get(definition.id());
            if (state == null) {
                // Full: the company is simply not listed. Nothing that is remembered is dropped for it.
                if (states.size() >= MAX_STATES) continue;
                states.put(definition.id(), StockState.listed(definition));
                changed = true;
            } else if (state.delisted || !state.nameKey.equals(definition.nameKey())) {
                // Back on the exchange, or renamed by its data file: it carries on from its last price.
                state.delisted = false;
                state.nameKey = definition.nameKey();
                changed = true;
            }
        }
        for (StockState state : states.values()) {
            if (state.delisted || registry.get(state.id) != null) continue;
            state.delisted = true;
            // A delisted company is not halted: it is gone, and stays at its last price.
            state.haltedUntil = StockState.NEVER;
            changed = true;
        }
        if (changed) setDirty();
        return changed;
    }

    /**
     * Drops a delisted company's state, once nobody holds it any more.
     *
     * @return false when there is no such company, it is still listed, or somebody still holds it
     */
    public synchronized boolean removeDelisted(String id) {
        StockState state = id == null ? null : states.get(id);
        if (state == null || !state.delisted || desk.anyHolds(id) || practice.anyHolds(id)) return false;
        states.remove(id);
        setDirty();
        return true;
    }

    // --- the step ----------------------------------------------------------------------------

    /**
     * Runs the price model for one step: every listed company, in id order, then the index. Does
     * nothing when the exchange is switched off, the step has already run, or the company data
     * could not be read.
     *
     * @return whether the step ran
     */
    public synchronized boolean step(StockRegistry registry, ExchangeSettings settings, long step, long stepMillis,
            Inputs inputs) {
        if (registry == null || !registry.available() || settings == null || inputs == null || !settings.enabled()
                || stepMillis < 1L || step < 0L || step <= lastStep) return false;
        reconcile(registry);
        long day = PriceModel.dayOf(step, stepMillis);
        double ratios = 0.0D;
        int members = 0;
        for (StockDefinition definition : registry.stocks()) {
            StockState state = states.get(definition.id());
            if (state == null || state.delisted) continue;
            // A new economy-day for this company: a price that has grown unwieldy splits first.
            if (state.day != StockState.NO_DAY && state.day != day && state.splitDue()) {
                state.split(day);
                desk.split(state.id, StockState.SPLIT_FACTOR);
                practice.split(state.id, StockState.SPLIT_FACTOR);
            }
            long before = state.price;
            PriceModel.advance(definition, state, settings, step, stepMillis, inputs.gaussian(definition.id()),
                    inputs.volume(definition), inputs.shock(definition));
            ratios += (double) state.price / before;
            members++;
        }
        if (index.splitDue(day)) {
            index.split(day);
            desk.split(IndexState.ID, StockState.SPLIT_FACTOR);
            practice.split(IndexState.ID, StockState.SPLIT_FACTOR);
        }
        index.advance(day, members, members == 0 ? 1.0D : ratios / members);
        lastStep = step;
        setDirty();
        return true;
    }

    // --- trading -----------------------------------------------------------------------------

    /** The real-money desk: every account's real shares, index units and Savings Bonds. */
    public ExchangeDesk desk() { return desk; }

    /** Practice mode: every account's sandbox of play credits. It can reach no real credit. */
    public PracticeBook practice() { return practice; }

    /** What {@code system:exchange} must hold: Invariant C. See {@link ExchangeDesk#held()}. */
    public long held() { return desk.held(); }

    /** What the last dividend day came to, with the factor that was applied. */
    public synchronized DividendRun dividends() { return dividends; }

    /**
     * Whether a stock — or the index fund, under {@link IndexState#ID} — can be traded right now,
     * its model price and its spread: what both the desk and the practice book price a trade from.
     */
    public synchronized Listing listing(ExchangeEnv env, String stockId) {
        if (env == null || stockId == null) return Listing.refused(TradeResult.UNKNOWN_STOCK);
        TradingTerms terms = env.terms();
        if (IndexState.ID.equals(stockId)) {
            // The index fund is a basket of what is listed: with nothing listed there is nothing to buy.
            for (StockState state : states.values())
                if (!state.delisted) return new Listing(TradeResult.OK, index.price, terms.spreadIndexBps());
            return Listing.refused(TradeResult.UNKNOWN_STOCK);
        }
        StockState state = states.get(stockId);
        if (state == null) return Listing.refused(TradeResult.UNKNOWN_STOCK);
        // A delisted company is priced as a steady share: its holders are paid out at that bid.
        if (state.delisted) return new Listing(TradeResult.DELISTED, state.price, terms.spreadSteadyBps());
        StockDefinition definition = env.registry().get(stockId);
        if (definition == null) return Listing.refused(TradeResult.UNKNOWN_STOCK);
        return new Listing(state.halted(lastStep) ? TradeResult.HALTED : TradeResult.OK, state.price,
                terms.spreadBps(definition.type()));
    }

    /**
     * What a dividend on the stock is worked out from: its price in hundredths of a credit and its
     * yield in hundredths of a percent per economy-week. Null when it pays none: the index fund, a
     * company with no yield, one that is not listed.
     */
    synchronized long[] dividendTerms(StockRegistry registry, String stockId) {
        StockState state = stockId == null ? null : states.get(stockId);
        if (state == null || state.delisted || registry == null) return null;
        StockDefinition definition = registry.get(stockId);
        if (definition == null || !(definition.dividendYield() > 0.0D)) return null;
        return new long[] {state.price, Math.round(definition.dividendYield() * 100.0D)};
    }

    /**
     * The economy time up to which a purchase counts for every dividend still to come: lots bought
     * at or before it are no longer told apart (see {@link Holding#settle}). It is the start of the
     * last dividend day that was paid, or four days back when none has been paid for longer.
     */
    synchronized long seasonedBefore(long now) {
        long paid = dividends.happened() ? dividends.day() * PriceModel.DAY_MILLIS : Long.MIN_VALUE;
        return Math.max(paid, now - SEASONED_AFTER_MILLIS);
    }

    /**
     * The dividend day: once per economy-day, the first time a step of that day runs. Real holders
     * are paid from the Reserve under the exchange budget, all by the same factor when that is
     * short; practice holders are credited play credits in full. Does nothing when {@code day}
     * was already paid, the exchange is switched off, the company data could not be read or the
     * economy is frozen.
     *
     * @return what the day came to; the stored result of the last one when nothing was paid now
     */
    public synchronized DividendRun payDividends(ExchangeEnv env, long day) {
        if (env == null || owner() == null || EconomyHealth.frozen() || !env.terms().enabled()) return dividends;
        // Company data that could not be read says nothing about who pays what: the day waits for it.
        if (!env.registry().available()) return dividends;
        if (dividends.happened() && day <= dividends.day()) return dividends;
        dividends = desk.payDividends(env, day);
        practice.payDividends(env, day);
        setDirty();
        // Lots bought before this day roll now count alike for every dividend to come: merge them.
        settle(env);
        return dividends;
    }

    /**
     * Pays the holders of every delisted company out at its last bid, real and practice alike, and
     * drops the company once nobody holds it. A real payout the ledger refuses is tried again at
     * the next step.
     *
     * @return how many real holdings were paid out
     */
    public synchronized int payOutDelisted(ExchangeEnv env) {
        if (env == null || owner() == null || EconomyHealth.frozen()) return 0;
        int paid = 0;
        for (String id : delisted()) {
            StockState state = states.get(id);
            long bid = ExchangeMath.bid(state.price, env.terms().spreadSteadyBps());
            paid += desk.payOutDelisted(env, id, bid);
            practice.payOutDelisted(env, id, bid);
            removeDelisted(id);
        }
        return paid;
    }

    /**
     * Pays out every bond that has matured at {@code time}, real ones into their owners' balances
     * and practice ones into the play balances. @return how many real bonds were paid out
     */
    public synchronized int matureBonds(ExchangeEnv env, long time) {
        if (owner() == null || EconomyHealth.frozen()) return 0;
        practice.matureBonds(time);
        return desk.matureBonds(env, time);
    }

    /** The purge: merges old lots in every holding, and drops practice accounts nobody has used for a month. */
    public synchronized void settle(ExchangeEnv env) {
        if (env == null || owner() == null) return;
        long now = now();
        desk.settle(env.terms(), now);
        practice.settle(env.terms(), now);
        practice.purgeIdle(Math.floorDiv(now, PriceModel.DAY_MILLIS));
    }

    /**
     * Erasure: the player's real positions are swept to the Reserve ({@link ExchangeDesk#forgetPlayer})
     * and their practice account is dropped. Works while frozen.
     *
     * @return the credits swept from the exchange account
     */
    public synchronized long forgetPlayer(UUID player) {
        practice.forget(player);
        return desk.forgetPlayer(player);
    }

    /** Data-subject export: the subject's real holdings and bonds, and their practice account. */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = desk.exportFor(player);
        out.add("practice", practice.exportFor(player));
        return out;
    }

    /** The store this section belongs to; null for a section that stands alone. */
    EconomyStore store() { return owner(); }

    /** Economy time, from the ledger's clock; 0 for a section that stands alone. */
    private long now() {
        EconomySavedData ledger = ledger();
        return ledger == null ? 0L : ledger.now();
    }

    // --- reads -------------------------------------------------------------------------------

    /** The last step the price model ran, or {@link StockState#NEVER}. */
    public synchronized long lastStep() { return lastStep; }

    /**
     * The live state of one company, or null. For the trading rules, which must read the price
     * and the halt flag in the same operation that moves credits; do not keep it.
     */
    public synchronized StockState state(String id) { return id == null ? null : states.get(id); }

    /** The index fund's live state. */
    public synchronized IndexState index() { return index; }

    /** How many companies are remembered, listed and delisted together. */
    public synchronized int size() { return states.size(); }

    /** The ids of the delisted companies whose state is still kept. */
    public synchronized List<String> delisted() {
        List<String> out = new ArrayList<>();
        for (StockState state : states.values()) if (state.delisted) out.add(state.id);
        return out;
    }

    /** Whether trading in the company is halted now. Unknown and delisted companies are not "halted". */
    public synchronized boolean halted(String id) {
        StockState state = id == null ? null : states.get(id);
        return state != null && !state.delisted && state.halted(lastStep);
    }

    /**
     * Every company the section remembers, as a copy: the listed ones in id order with what their
     * definition says about them, then the delisted ones.
     */
    public synchronized List<ExchangeApi.StockQuote> quotes(StockRegistry registry) {
        List<ExchangeApi.StockQuote> out = new ArrayList<>(states.size());
        if (registry != null) for (StockDefinition definition : registry.stocks()) {
            StockState state = states.get(definition.id());
            if (state != null && !state.delisted) out.add(quote(state, definition));
        }
        for (StockState state : states.values()) if (state.delisted) out.add(quote(state, null));
        // A company that is listed but not in the registry handed in is still reported, by its state alone.
        for (StockState state : states.values())
            if (!state.delisted && (registry == null || registry.get(state.id) == null)) out.add(quote(state, null));
        return out;
    }

    /** One company as a copy, or empty when the section does not know it. */
    public synchronized Optional<ExchangeApi.StockQuote> quote(String id, StockRegistry registry) {
        StockState state = id == null ? null : states.get(id);
        if (state == null) return Optional.empty();
        return Optional.of(quote(state, registry == null || state.delisted ? null : registry.get(id)));
    }

    private ExchangeApi.StockQuote quote(StockState state, StockDefinition definition) {
        boolean halted = !state.delisted && state.halted(lastStep);
        // A delisted company has no definition any more: it is named from what its state kept.
        if (definition == null)
            return new ExchangeApi.StockQuote(state.id, state.nameKey, "", "", StockType.STEADY, state.price,
                    state.dayOpen, state.dayChangeBps(), halted, state.delisted, 0.0D, state.startCents);
        return new ExchangeApi.StockQuote(state.id, definition.nameKey(), definition.descriptionKey(),
                definition.sector(), definition.type(), state.price, state.dayOpen, state.dayChangeBps(), halted,
                state.delisted, definition.dividendYield(), state.startCents);
    }

    /** The index fund as a copy. {@code members} is how many listed companies it averages. */
    public synchronized ExchangeApi.IndexQuote indexQuote() {
        int members = 0;
        for (StockState state : states.values()) if (!state.delisted) members++;
        return new ExchangeApi.IndexQuote(IndexState.ID, IndexState.NAME_KEY, IndexState.DESCRIPTION_KEY, index.price,
                index.dayOpen, index.dayChangeBps(), members);
    }

    /** The price history of a company, or of the index under {@link IndexState#ID}, as a copy. */
    public synchronized Optional<ExchangeApi.History> history(String id) {
        if (IndexState.ID.equals(id)) return Optional.of(history(index.history));
        StockState state = id == null ? null : states.get(id);
        return state == null ? Optional.empty() : Optional.of(history(state.history));
    }

    private static ExchangeApi.History history(PriceHistory history) {
        return new ExchangeApi.History(boxed(history.recent()), boxed(history.daily()));
    }

    private static List<Long> boxed(long[] values) {
        List<Long> out = new ArrayList<>(values.length);
        for (long value : values) out.add(value);
        return List.copyOf(out);
    }

    /** Nothing was ever listed, no step has run and nobody holds anything. */
    @Override public synchronized boolean isEmpty() {
        return states.isEmpty() && lastStep == StockState.NEVER && index.untouched() && desk.isEmpty()
                && practice.isEmpty() && !dividends.happened();
    }

    // --- persistence -------------------------------------------------------------------------

    private synchronized List<StockState> stateRows() { return new ArrayList<>(states.values()); }

    private synchronized Optional<IndexState> indexRow() {
        return index.untouched() ? Optional.empty() : Optional.of(index);
    }

    private static ExchangeSection fromData(long lastStep, List<StockState> rows, Optional<IndexState> index,
            List<ExchangeDesk.Row> portfolios, List<PracticeBook.Row> practiceRows, DividendRun dividends) {
        ExchangeSection section = new ExchangeSection();
        section.desk.load(portfolios);
        section.practice.load(practiceRows);
        section.dividends = dividends == null ? DividendRun.NONE : dividends;
        section.lastStep = Math.max(StockState.NEVER, lastStep);
        // A row that is not a company is skipped; every other one still loads.
        for (StockState row : rows) {
            if (row == null || !StockDefinition.validId(row.id) || section.states.containsKey(row.id)) continue;
            if (section.states.size() >= MAX_STATES) break;
            section.states.put(row.id, row);
        }
        index.ifPresent(saved -> section.index = saved);
        return section;
    }
}
