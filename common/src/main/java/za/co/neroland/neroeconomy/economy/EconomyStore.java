package za.co.neroland.neroeconomy.economy;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import za.co.neroland.nerolandcore.data.SavedDataRecovery;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.RuleOrders;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.direct.TradeEscrowSection;
import za.co.neroland.neroeconomy.exchange.ExchangeSection;
import za.co.neroland.neroeconomy.goods.GoodsSection;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.LotHolder;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketBuyOffer;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.StoredStacks;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.offers.OffersSection;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.simulation.SimulationSection;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.trader.AutoTraderSection;
import za.co.neroland.neroeconomy.vault.VaultSection;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * The one economy store, {@code neroeconomy:economy}: every section of the economy in a single
 * saved-data file under Core's backup guard, so a save can never persist half an operation.
 *
 * <p><b>Sections.</b> Each section is one optional field of {@link #CODEC} and one accessor here.
 * A new section is added by writing an {@link EconomySection}, giving it a codec, and adding one
 * {@code optionalFieldOf} line, one field, one accessor and one entry in {@link #sections()} to this
 * class; a store saved before the section existed simply loads it empty. A section that escrows
 * credits implements {@link EscrowHolder} and one that holds goods implements {@link LotHolder};
 * nothing else has to be told about either.
 *
 * <p><b>First load.</b> A world with no economy store has its four older stores (ledger, market,
 * obligations, shop owners) read through their old types and adopted as sections. The old files are
 * left untouched until the combined store has been read back from disk on a later start — only then
 * are they emptied, so a crash during the very first save cannot lose both copies. The combined
 * store carries the schema it was migrated to, which is what stops a second import.
 *
 * <p><b>Freeze.</b> A separate marker ({@link EconomyMarker}) records that an economy exists. It
 * is written on the first start that reads the combined store back from disk, never in the session
 * that created the store, so a crash during a world's first save cannot leave a marker with nothing
 * behind it. If the marker is there and the store is missing or unreadable — or the store was
 * written by a newer version — the economy is frozen for the session (see {@link EconomyHealth}):
 * the accessors hand out an empty store that is never installed or saved, so neither the file on
 * disk nor Core's last backup of it is overwritten.
 */
public final class EconomyStore extends SavedData {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "economy");
    /** The schema this build writes. 0 is a store that has never been set up. */
    public static final int SCHEMA = 1;

    static final Codec<EconomyStore> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema", 0).forGetter(store -> store.schema),
            EconomySavedData.CODEC.optionalFieldOf("ledger").forGetter(store -> Optional.of(store.ledger)),
            // The sections below hold item stacks, whose codec needs the game's registries; resolving
            // them lazily keeps the store itself loadable without them.
            Codec.lazyInitialized(() -> MarketSavedData.CODEC).optionalFieldOf("market")
                    .forGetter(store -> present(store.market)),
            Codec.lazyInitialized(() -> ObligationSavedData.CODEC).optionalFieldOf("obligations")
                    .forGetter(store -> present(store.obligations)),
            Codec.lazyInitialized(() -> ShopOwnershipState.CODEC).optionalFieldOf("shop_owners")
                    .forGetter(store -> present(store.shopOwners)),
            // Added with the vault and the simulation, so optional: an older store loads them empty.
            Codec.lazyInitialized(() -> GoodsSection.CODEC).optionalFieldOf("goods")
                    .forGetter(store -> present(store.goods)),
            VaultSection.CODEC.optionalFieldOf("vault").forGetter(store -> present(store.vault)),
            SimulationSection.CODEC.optionalFieldOf("simulation").forGetter(store -> present(store.simulation)),
            // Added with the order book, so optional: an older store loads it empty and its Market
            // Board rows, still in the market section, are adopted as orders.
            BookSection.CODEC.optionalFieldOf("book").forGetter(store -> present(store.book)),
            OffersSection.CODEC.optionalFieldOf("offers").forGetter(store -> present(store.offers)),
            RulesSection.CODEC.optionalFieldOf("rules").forGetter(store -> present(store.rules)),
            // Added with direct trade, so optional. It only ever holds anything between a save and
            // the next load: a load returns what it finds here to its owners' vaults.
            TradeEscrowSection.CODEC.optionalFieldOf("trade_escrow").forGetter(store -> present(store.tradeEscrow)),
            // Added with the Auto-Trader block, so optional: who owns the block at each position.
            AutoTraderSection.CODEC.optionalFieldOf("auto_traders").forGetter(store -> present(store.autoTraders)),
            // Added with the exchange, so optional: an older store loads with nothing listed yet.
            ExchangeSection.CODEC.optionalFieldOf("exchange").forGetter(store -> present(store.exchange)))
            .apply(instance, EconomyStore::new));
    public static final SavedDataType<EconomyStore> TYPE = new SavedDataType<>(ID, EconomyStore::new, CODEC, null);

    /** What one running server has resolved; never outlives it. */
    private static final class Session {
        private final WeakReference<MinecraftServer> server;
        /** The empty stand-in handed out while frozen; null when the real store is in use. */
        private EconomyStore frozen;
        private Session(MinecraftServer server) { this.server = new WeakReference<>(server); }
    }

    private static volatile Session session;

    private int schema;
    /** True for a store decoded from its file, as opposed to one created empty in this session. */
    private final boolean loadedFromDisk;
    private EconomySavedData ledger;
    private MarketSavedData market;
    private ObligationSavedData obligations;
    private ShopOwnershipState shopOwners;
    private final GoodsSection goods;
    private final VaultSection vault;
    private final SimulationSection simulation;
    private final BookSection book;
    private final OffersSection offers;
    private final RulesSection rules;
    private final TradeEscrowSection tradeEscrow;
    private final AutoTraderSection autoTraders;
    private final ExchangeSection exchange;

    public EconomyStore() {
        this(0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private EconomyStore(int schema, Optional<EconomySavedData> ledger, Optional<MarketSavedData> market,
            Optional<ObligationSavedData> obligations, Optional<ShopOwnershipState> shopOwners,
            Optional<GoodsSection> goods, Optional<VaultSection> vault, Optional<SimulationSection> simulation,
            Optional<BookSection> book, Optional<OffersSection> offers, Optional<RulesSection> rules,
            Optional<TradeEscrowSection> tradeEscrow, Optional<AutoTraderSection> autoTraders,
            Optional<ExchangeSection> exchange) {
        this(schema, ledger, market, obligations, shopOwners, goods, vault, simulation, book, offers, rules,
                tradeEscrow, autoTraders, exchange, true);
    }

    private EconomyStore(int schema, Optional<EconomySavedData> ledger, Optional<MarketSavedData> market,
            Optional<ObligationSavedData> obligations, Optional<ShopOwnershipState> shopOwners,
            Optional<GoodsSection> goods, Optional<VaultSection> vault, Optional<SimulationSection> simulation,
            Optional<BookSection> book, Optional<OffersSection> offers, Optional<RulesSection> rules,
            Optional<TradeEscrowSection> tradeEscrow, Optional<AutoTraderSection> autoTraders,
            Optional<ExchangeSection> exchange, boolean loadedFromDisk) {
        this.schema = Math.max(0, schema);
        this.loadedFromDisk = loadedFromDisk;
        this.ledger = adopt(ledger.orElseGet(EconomySavedData::new));
        this.market = adopt(market.orElseGet(MarketSavedData::new));
        this.obligations = adopt(obligations.orElseGet(ObligationSavedData::new));
        this.shopOwners = adopt(shopOwners.orElseGet(ShopOwnershipState::new));
        this.goods = adopt(goods.orElseGet(GoodsSection::new));
        this.vault = adopt(vault.orElseGet(VaultSection::new));
        this.simulation = adopt(simulation.orElseGet(SimulationSection::new));
        this.book = adopt(book.orElseGet(BookSection::new));
        this.offers = adopt(offers.orElseGet(OffersSection::new));
        this.rules = adopt(rules.orElseGet(RulesSection::new));
        this.tradeEscrow = adopt(tradeEscrow.orElseGet(TradeEscrowSection::new));
        this.autoTraders = adopt(autoTraders.orElseGet(AutoTraderSection::new));
        this.exchange = adopt(exchange.orElseGet(ExchangeSection::new));
        // A store written before the vault existed still holds item claims in its market section.
        adoptItemClaims(false);
        // And one written before the order book existed still holds its Market Board rows there.
        adoptBoardRows(false);
    }

    private <T extends EconomySection> T adopt(T section) {
        section.attach(this);
        return section;
    }

    private static <T extends EconomySection> Optional<T> present(T section) {
        return section.isEmpty() ? Optional.empty() : Optional.of(section);
    }

    // --- sections ----------------------------------------------------------------------------

    public EconomySavedData ledger() { return ledger; }
    public MarketSavedData market() { return market; }
    public ObligationSavedData obligations() { return obligations; }
    public ShopOwnershipState shopOwners() { return shopOwners; }
    /** The prototype table, and the conversion between item stacks and lots. */
    public GoodsSection goods() { return goods; }
    public VaultSection vault() { return vault; }
    public SimulationSection simulation() { return simulation; }
    /** The order book: every open sell and buy order, and the matching engine. */
    public BookSection book() { return book; }
    /** Escrowed offers between accounts, and who has muted them. */
    public OffersSection offers() { return offers; }
    /** Auto-buy and auto-sell rules. */
    public RulesSection rules() { return rules; }

    /** What the sides of open direct trades have put in; see {@link #recoverTradeEscrow}. */
    public TradeEscrowSection tradeEscrow() { return tradeEscrow; }

    /** Who owns the Auto-Trader block at each position. */
    public AutoTraderSection autoTraders() { return autoTraders; }

    /** The exchange: the price state of every listed company, and the index fund's level. */
    public ExchangeSection exchange() { return exchange; }

    /** What the order book asks about an order an auto-trade rule placed: the rules section. */
    public RuleOrders ruleOrders() { return rules; }

    public int schema() { return schema; }

    /**
     * Applies a ledger plan to this store's ledger as one operation: every leg or none. This is
     * how a section moves credits. In the running game the plan goes through the currency
     * provider, so balance listeners hear of every change to a player's balance; a store that is
     * not the bound one — in a unit test — is applied to directly, under the same rules.
     */
    public LedgerResult execute(LedgerPlan plan) {
        return NeroEconomyCommon.CURRENCY_PROVIDER.executeOn(ledger, plan);
    }

    /**
     * Pays a batch out of the Reserve to players, scaled down pro rata when the Reserve or the
     * day's budget is short. Like {@link #execute}, it goes through the currency provider in the
     * running game and straight to this store's ledger otherwise.
     */
    public Reserve.ProRata payProRata(BudgetKind kind, Map<UUID, Long> amounts, TransactionType type) {
        return NeroEconomyCommon.CURRENCY_PROVIDER.payProRataOn(ledger, kind, amounts, type);
    }

    /**
     * Every section, in one list. The escrow check and the prototype reference scan walk this, so a
     * new section is part of both by being listed here.
     */
    private List<EconomySection> sections() {
        return List.of(ledger, market, obligations, shopOwners, goods, vault, simulation, book, offers, rules,
                tradeEscrow, autoTraders, exchange);
    }

    /** The names of the sections that hold anything, for the health report. */
    public List<String> sectionsPresent() {
        List<String> names = new ArrayList<>();
        if (!ledger.isEmpty()) names.add("ledger");
        if (!market.isEmpty()) names.add("market");
        if (!obligations.isEmpty()) names.add("obligations");
        if (!shopOwners.isEmpty()) names.add("shop_owners");
        if (!goods.isEmpty()) names.add("goods");
        if (!vault.isEmpty()) names.add("vault");
        if (!simulation.isEmpty()) names.add("simulation");
        if (!book.isEmpty()) names.add("book");
        if (!offers.isEmpty()) names.add("offers");
        if (!rules.isEmpty()) names.add("rules");
        if (!tradeEscrow.isEmpty()) names.add("trade_escrow");
        if (!autoTraders.isEmpty()) names.add("auto_traders");
        if (!exchange.isEmpty()) names.add("exchange");
        return names;
    }

    // --- goods -------------------------------------------------------------------------------

    /**
     * Hands every lot any section holds to {@code visitor}. This is the prototype table's reference
     * scan: a prototype is kept exactly as long as a lot seen here names it.
     */
    public void forEachLot(Consumer<Lot> visitor) {
        for (EconomySection section : sections())
            if (section instanceof LotHolder holder) holder.forEachLot(visitor);
    }

    /**
     * Moves the item claims a market section still carries into the owners' vaults. Claims were the
     * market section's own until the vault existed; they are delivered, never deposited, so a claim
     * list longer than the vault cap is booked in full.
     *
     * @param evenMirrored also take claims that only mirror a vault (see
     *                     {@link MarketSavedData#mirrorVaultInOldFile}): true when the vault they
     *                     mirrored is gone and this store is being rebuilt from the old files
     */
    private void adoptItemClaims(boolean evenMirrored) {
        Map<UUID, List<ItemStack>> claims = market.drainLegacyItemClaims(evenMirrored);
        if (claims.isEmpty()) return;
        claims.forEach((player, stacks) -> stacks.forEach(stack -> VaultStacks.deliver(this, player, stack)));
        setDirty();
    }

    /**
     * Moves the Market Board rows a market section still carries onto the order book: a listing
     * becomes a sell order and a buy offer a buy order, each with the id, price and expiry it had,
     * and a buy offer with the escrow it had. Nothing moves in the ledger, so both invariants hold
     * exactly as before, and an id the companion app already shows stays valid. Shop listings and
     * the gallery's demo rows stay where they are.
     *
     * @param evenMirrored also take rows that only mirror a book (see
     *                     {@link MarketSavedData#mirrorBookInOldFile}): true when the book they
     *                     mirrored is gone and this store is being rebuilt from the old files
     */
    private void adoptBoardRows(boolean evenMirrored) {
        MarketSavedData.LegacyBoardRows rows = market.drainLegacyBoardRows(evenMirrored);
        if (rows.isEmpty()) return;
        for (MarketListing listing : rows.listings()) {
            Lot lot = goods.lotOf(listing.stock());
            // An id that is somehow taken keeps its goods all the same: they go to the seller's vault.
            if (lot != null && !book.adoptSell(listing.listingId(), new PlayerAccountId(listing.seller()), lot,
                    listing.baseUnitPrice(), listing.expiresAt())) VaultStacks.deliver(this, listing.seller(), listing.stock());
        }
        for (MarketBuyOffer offer : rows.buyOffers()) {
            // Likewise the escrow of an offer that cannot be adopted: it becomes the buyer's claim.
            if (!book.adoptBuy(offer.offerId(), new PlayerAccountId(offer.buyer()), offer.itemId().toString(),
                    offer.remaining(), offer.unitPrice(), offer.escrow(), offer.expiresAt()))
                market.addCreditClaim(offer.buyer(), offer.escrow(), TransactionType.ESCROW_REFUND);
        }
        setDirty();
    }

    /**
     * The players' open orders as Market Board rows: what the old market file is given to hold
     * while it is still a fallback. An order larger than one stack is written as several rows.
     */
    private MarketSavedData.LegacyBoardRows bookAsBoardRows() {
        List<MarketListing> listings = new ArrayList<>();
        List<MarketBuyOffer> offers = new ArrayList<>();
        for (AccountId account : book.owners()) {
            if (!(account instanceof PlayerAccountId player)) continue;
            for (SellOrder order : book.sellsOf(account)) {
                ItemStack whole = goods.stackOf(order.lot());
                if (whole.isEmpty()) continue;
                int perStack = Math.max(1, whole.getMaxStackSize());
                UUID id = order.id();
                for (int left = order.lot().count(); left > 0; left -= perStack) {
                    listings.add(new MarketListing(id, player.playerId(), ListingSource.MARKET_BOARD,
                            whole.copyWithCount(Math.min(perStack, left)), order.unitPrice(),
                            Math.max(1L, order.expiresAt()), "", false, 0L));
                    id = UUID.randomUUID();
                }
            }
            for (BuyOrder order : book.buysOf(account)) {
                Identifier item = Identifier.tryParse(order.item());
                if (item != null) offers.add(new MarketBuyOffer(order.id(), player.playerId(), item, order.remaining(),
                        order.unitPrice(), order.escrow(), order.expiresAt()));
            }
        }
        return new MarketSavedData.LegacyBoardRows(listings, offers);
    }

    /** Every player's vault as item stacks: what the old market file is given to hold while it is still a fallback. */
    private Map<UUID, List<ItemStack>> vaultsAsClaims() {
        Map<UUID, List<ItemStack>> claims = new LinkedHashMap<>();
        for (AccountId account : vault.accounts()) {
            if (!(account instanceof PlayerAccountId player)) continue;
            List<ItemStack> stacks = VaultStacks.stacks(this, player.playerId());
            if (!stacks.isEmpty()) claims.put(player.playerId(), stacks);
        }
        return claims;
    }

    /**
     * Returns whatever is in trade escrow to its owners' vaults. No trade window survives a
     * restart, so anything found there on load was in a trade that a stop or a crash cut short.
     * It is run from {@link #open}, not from the decoder, because a delivery needs the game's
     * stack limits.
     *
     * @return how many stacks were returned
     */
    public int recoverTradeEscrow() {
        return tradeEscrow.isEmpty() ? 0 : tradeEscrow.recover(Trading.env(this));
    }

    // --- invariants --------------------------------------------------------------------------

    /** Every recorded escrow obligation: what Invariant B checks the escrow account against. */
    public long escrowObligations() {
        long total = 0L;
        for (EconomySection section : sections())
            if (section instanceof EscrowHolder holder) total = EscrowMath.total(total, holder.escrowObligations());
        return total;
    }

    /**
     * What the exchange account should hold: what Invariant C checks it against. The cost of every
     * open share and index position, the principal of every open bond, and the interest reserved
     * for those bonds.
     */
    public long exchangeObligations() { return exchange.held(); }

    /** Aggregates for {@code /eco admin supply}; names no account. */
    public SupplyReport supplyReport() { return ledger.supplyReport(escrowObligations(), exchangeObligations()); }

    /** The three invariants at once. Cheap: one pass over the balances and one over the open rows. */
    public boolean checkInvariants() {
        return ledger.supplyBalanced()
                && ledger.balance(SystemAccountId.ESCROW) == escrowObligations()
                && ledger.balance(SystemAccountId.EXCHANGE) == exchangeObligations();
    }

    // --- access ------------------------------------------------------------------------------

    /**
     * The world's economy store. The first call for a server resolves it: load, migrate or freeze.
     * Every later call goes through Core's guard, which keeps the store's recovery backup fresh.
     */
    public static EconomyStore get(MinecraftServer server) {
        Session current = session;
        if (current == null || current.server.get() != server) current = open(server);
        EconomyStore frozen = current.frozen;
        if (frozen != null) return frozen;
        return SavedDataRecovery.get(server.overworld(), TYPE, EconomyStore::new, ID.toString());
    }

    private static synchronized Session open(MinecraftServer server) {
        Session current = session;
        if (current != null && current.server.get() == server) return current;
        ServerLevel level = server.overworld();
        SavedDataStorage storage = level.getDataStorage();
        Session opened = new Session(server);
        // Before anything is read: stored stacks need the server's registries, and Core's backup
        // of the store does not bring them.
        StoredStacks.bind(server.registryAccess());
        EconomyMarker marker = SavedDataRecovery.get(level, EconomyMarker.TYPE, EconomyMarker::new,
                EconomyMarker.ID.toString());
        // A plain read: null when there is no file, and also when the file could not be read.
        EconomyStore onDisk = storage.get(TYPE);
        boolean missing = onDisk == null && marker.exists();
        // A store written by a later version holds sections this one would drop on its next save.
        boolean newer = onDisk != null && onDisk.schema > SCHEMA;
        if (missing || newer) {
            // The stand-in is not installed and never saved, so the file on disk and Core's last
            // backup of it stay exactly as they are.
            opened.frozen = new EconomyStore();
            EconomyHealth.freeze();
            session = opened;
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] This world has an economy, but its store {}. The economy "
                    + "is FROZEN: nothing can be spent, earned or traded until the store is restored and the "
                    + "server restarted, or an operator runs '/eco admin health accept' to start a fresh economy.",
                    newer ? "was written by a newer version of the mod" : "is missing or unreadable");
            return opened;
        }
        EconomyHealth.clear();
        EconomyStore store = SavedDataRecovery.get(level, TYPE, EconomyStore::new, ID.toString());
        // Published before the set-up below, which reaches back in through the section accessors.
        session = opened;
        if (store.schema < SCHEMA) store.setUp(storage, true);
        else if (store.loadedFromDisk) {
            // The combined store has been read back from disk, so it is the durable copy: only now
            // is the marker written and are the older files emptied.
            marker.markExists(store.schema);
            clearLegacy(level, storage);
        }
        // Goods left in trade escrow by a stop or a crash go to their owners' vaults, whoever is online.
        if (store.recoverTradeEscrow() > 0) store.setDirty();
        return opened;
    }

    /** Server stop: nothing resolved for one world carries into the next. */
    public static synchronized void close(MinecraftServer server) {
        Session current = session;
        if (current == null || current.server.get() != server) return;
        session = null;
        EconomyHealth.clear();
        StoredStacks.unbind();
    }

    /**
     * Pushes the store into Core's recovery backup now, because that backup is a second copy of the
     * same rows. Skipped while frozen: the store in use then is an empty stand-in, and writing it
     * would replace the last good backup.
     */
    public static void backupNow(MinecraftServer server) {
        if (server == null) return;
        EconomyStore store = get(server);
        Session current = session;
        if (current != null && current.frozen == store) return;
        // The backup is a second copy of the store: a prototype nothing refers to must not be in it.
        store.goods.sweepIfDue(System.currentTimeMillis());
        SavedDataRecovery.backupNow(server.overworld(), TYPE, store, ID.toString());
    }

    /**
     * Every eraser calls this once it has purged its section. The purge is pushed into Core's
     * backup of the store, and into the older stores for as long as they still hold a copy:
     *
     * <ul>
     *   <li>in the session that adopted them, the old files still hold the rows as they were before
     *       migration, so they are rewritten from the purged sections and their backups replaced;</li>
     *   <li>an older store that was not adopted and still holds rows is purged directly.</li>
     * </ul>
     */
    public static void afterErasure(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        ServerLevel level = server.overworld();
        SavedDataStorage storage = level.getDataStorage();
        eraseLegacy(level, storage, EconomySavedData.TYPE, old -> old.forgetPlayer(player));
        eraseLegacy(level, storage, MarketSavedData.TYPE, old -> old.forgetPlayer(player));
        eraseLegacy(level, storage, ObligationSavedData.TYPE, old -> old.forgetPlayer(player));
        eraseLegacy(level, storage, ShopOwnershipState.TYPE, old -> old.forgetPlayer(player));
        backupNow(server);
    }

    private static <T extends EconomySection> void eraseLegacy(ServerLevel level, SavedDataStorage storage,
            SavedDataType<T> type, Consumer<T> purge) {
        T old = storage.get(type);
        if (old == null) return;
        if (old.owner() != null) old.rewriteStandaloneFile();
        else if (old.isEmpty()) return;
        else {
            purge.accept(old);
            old.setDirty();
        }
        SavedDataRecovery.backupNow(level, type, old, type.id().toString());
    }

    /**
     * An operator accepts a fresh start for a frozen economy: the empty store is installed, set up
     * as a new economy and saved over the unreadable one at the next save. Anything left in the
     * older stores is discarded, not imported.
     *
     * @return false when the economy is not frozen
     */
    public static synchronized boolean acceptFreshStart(MinecraftServer server) {
        Session current = session;
        if (server == null || current == null || current.server.get() != server || current.frozen == null)
            return false;
        ServerLevel level = server.overworld();
        SavedDataStorage storage = level.getDataStorage();
        EconomyStore store = current.frozen;
        storage.set(TYPE, store);
        current.frozen = null;
        EconomyHealth.clear();
        clearLegacy(level, storage);
        store.setUp(storage, false);
        SavedDataRecovery.get(level, EconomyMarker.TYPE, EconomyMarker::new, EconomyMarker.ID.toString())
                .markExists(store.schema);
        NeroEconomyCommon.LOGGER.warn("[NeroEconomy] An operator accepted a fresh start; the economy is running again.");
        return true;
    }

    // --- migration ---------------------------------------------------------------------------

    /**
     * Sets a store up for the first time: adopts what the four older stores hold (when asked to),
     * brings the ledger into the supply accounting, restores escrow the old version had written off
     * the ledger, and fills the Reserve once. A brand-new world takes the same path with nothing
     * to adopt.
     */
    private void setUp(SavedDataStorage storage, boolean importLegacy) {
        boolean adopted = false;
        if (importLegacy) {
            EconomySavedData oldLedger = storage.get(EconomySavedData.TYPE);
            MarketSavedData oldMarket = storage.get(MarketSavedData.TYPE);
            ObligationSavedData oldObligations = storage.get(ObligationSavedData.TYPE);
            ShopOwnershipState oldShops = storage.get(ShopOwnershipState.TYPE);
            if (oldLedger != null && !oldLedger.isEmpty()) { ledger = adopt(oldLedger); adopted = true; }
            if (oldMarket != null && !oldMarket.isEmpty()) { market = adopt(oldMarket); adopted = true; }
            if (oldObligations != null && !oldObligations.isEmpty()) { obligations = adopt(oldObligations); adopted = true; }
            if (oldShops != null && !oldShops.isEmpty()) { shopOwners = adopt(oldShops); adopted = true; }
            if (market == oldMarket) {
                // The old market file is the fallback copy until this store has been read back from
                // disk. Its item claims are in the vault now, so from here on it is written with a
                // mirror of the vault in their place; should this store be lost before it is saved,
                // the next start rebuilds the vault from that file.
                adoptItemClaims(true);
                market.mirrorVaultInOldFile(this::vaultsAsClaims);
                // The same goes for its Market Board rows, which are orders on the book from here on.
                adoptBoardRows(true);
                market.mirrorBookInOldFile(this::bookAsBoardRows);
            }
        }
        ledger.adoptLegacySupply(escrowObligations());
        ledger.seedReserve(EconomyConfig.RESERVE_INITIAL.get());
        schema = SCHEMA;
        setDirty();
        if (adopted) NeroEconomyCommon.LOGGER.info("[NeroEconomy] Older economy stores were adopted into the "
                + "combined store; their files are emptied once the new store has been saved and read back.");
    }

    /**
     * Empties whatever the four older stores still hold, in their files and in Core's backups of
     * them. Only called once the combined store is the durable copy, or on an accepted fresh start.
     */
    private static void clearLegacy(ServerLevel level, SavedDataStorage storage) {
        clearLegacy(level, storage, EconomySavedData.TYPE, new EconomySavedData());
        clearLegacy(level, storage, MarketSavedData.TYPE, new MarketSavedData());
        clearLegacy(level, storage, ObligationSavedData.TYPE, new ObligationSavedData());
        clearLegacy(level, storage, ShopOwnershipState.TYPE, new ShopOwnershipState());
    }

    private static <T extends EconomySection> void clearLegacy(ServerLevel level, SavedDataStorage storage,
            SavedDataType<T> type, T empty) {
        T old = storage.get(type);
        if (old == null || old.isEmpty() || old.owner() != null) return;
        // set() marks the empty instance dirty, so the old file is rewritten empty at the next save.
        storage.set(type, empty);
        SavedDataRecovery.backupNow(level, type, empty, type.id().toString());
    }
}
