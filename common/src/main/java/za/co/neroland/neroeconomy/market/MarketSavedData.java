package za.co.neroland.neroeconomy.market;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.PriceQuote;
import za.co.neroland.neroeconomy.api.PricingEngine;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BookResult;
import za.co.neroland.neroeconomy.book.BookStacks;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.Fill;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.Traded;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.EscrowHolder;
import za.co.neroland.neroeconomy.economy.EscrowMath;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.gallery.GallerySandbox;
import za.co.neroland.neroeconomy.goods.GoodsSection;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.vault.VaultStacks;
import za.co.neroland.nerolandcore.link.LinkAlert;

/**
 * Authoritative shop-listing, proceeds, and request-idempotency section of the economy store.
 *
 * <p><b>The book.</b> Market Board listings and buy offers are orders on the order book
 * ({@code BookSection}) now. This section still holds the Player Shop listings, the gallery's demo
 * rows, every credit claim and the request ledger, and it is still the one front door the terminal,
 * the commands and the companion app use: its searches return shops and the book's sell orders
 * side by side (and the book's buy orders where they did before), and its post, buy, fill and
 * cancel calls for board rows are carried out by the book. A save from before the book existed
 * still carries board rows here; the store moves them onto the book when it loads.
 *
 * <p><b>Goods.</b> Goods this section owes a player — a purchase made while away, an expired or
 * cancelled listing, a filled buy offer — are delivered to the player's vault (see
 * {@link VaultStacks}). This section used to keep them itself as "item claims"; a save from then
 * still carries them, and the store moves them into the vaults when it loads.
 *
 * <p><b>Credits.</b> Nothing here holds credits of its own. A buy offer's escrow and every credit
 * claim are numbers that say whose the credits in the ledger's escrow account are: escrowing is a
 * transfer from the player to that account, collecting a claim is a transfer back out of it, and a
 * fee is a transfer from it to the Reserve. The numbers and the account balance always move together.
 */
public final class MarketSavedData extends EconomySection implements EscrowHolder {
    /** The id this section was stored under when it was a store of its own; read once, on migration. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market");
    /** Resolved on first use: the rows hold item stacks, whose codec needs the game's registries. */
    public static final Codec<MarketSavedData> CODEC = Codec.lazyInitialized(MarketSavedData::codec);
    /** The pre-combined-store type. Only {@link EconomyStore} uses it, to migrate and then empty the old file. */
    public static final SavedDataType<MarketSavedData> TYPE =
            new SavedDataType<>(ID, MarketSavedData::new, CODEC, null);
    /** Items in this tag (empty by default) can never be listed, auctioned, escrowed or requested. */
    public static final TagKey<Item> UNTRADEABLE = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "untradeable"));
    /** Nominal owner of the gallery's demo board rows, so a showcase row names no player. */
    private static final UUID DEMO_ACCOUNT = new UUID(0L, 0L);
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final Map<UUID, MarketListing> listings = new LinkedHashMap<>();
    private final Map<UUID, MarketBuyOffer> buyOffers = new LinkedHashMap<>();
    /**
     * Item claims read from a save written before the vault existed. The store empties this into
     * the vaults as soon as the section is part of it; nothing is added here afterwards except by
     * a section that stands alone and so has no vault to deliver to.
     */
    private final Map<UUID, List<ItemStack>> legacyItemClaims = new LinkedHashMap<>();
    /** True when the claims just read only mirror a vault that was saved in the same store. */
    private boolean legacyClaimsMirrorVault;
    /** Set in the one session that adopted this section from its own old file; see {@link #mirrorVaultInOldFile}. */
    private Supplier<Map<UUID, List<ItemStack>>> vaultMirror;
    /** True when the board rows just read only mirror a book that was saved in the same store. */
    private boolean legacyBoardMirrorsBook;
    /** Set in the one session that adopted this section from its own old file; see {@link #mirrorBookInOldFile}. */
    private Supplier<LegacyBoardRows> bookMirror;
    private final CreditClaims creditClaims = new CreditClaims();
    private final RequestLedger requests = new RequestLedger();

    public MarketSavedData() { }

    /** The market section of the world's economy store. */
    public static MarketSavedData get(MinecraftServer server) {
        return EconomyStore.get(server).market();
    }

    /**
     * Erasure entry point; tolerates an unknown player. Must run after the obligation store's, which
     * books counterparty refunds into this store. The purge is pushed into Core's recovery backup
     * in the same request, because that backup is a second copy of the same rows.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        get(server).forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }

    private static long now() { return NeroEconomyCommon.CURRENCY_PROVIDER.now(); }

    /**
     * Whether a stack may enter escrow at all: not in {@link #UNTRADEABLE}, and no larger encoded
     * than {@code maxStackDataBytes}. One stack is one row in a file that is rewritten whole, so a
     * stack stuffed with data (a full container, a written book) is refused at the door.
     */
    public static boolean tradeable(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) return false;
        return MarketLimits.stackAllowed(encodedBytes(stack, registries),
                EconomyConfig.MAX_STACK_DATA_BYTES.get(), stack.is(UNTRADEABLE));
    }

    /** Whether an item id is barred from trade by tag; an unknown id is not this method's concern. */
    public static boolean untradeable(Identifier itemId) {
        return BuiltInRegistries.ITEM.get(itemId).map(holder -> holder.is(UNTRADEABLE)).orElse(false);
    }

    /**
     * A stack exactly as the item defines it: no component patch (no rename, enchantment, stored
     * contents) and no damage. Buy offers and jobs name an item id, so only this form satisfies them.
     */
    public static boolean pristine(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getComponentsPatch().isEmpty() && !stack.isDamaged();
    }

    /** Whether this viewer is shown the gallery's demo rows: a creative-mode level-3 operator only. */
    public static boolean seesDemo(ServerPlayer viewer) {
        return viewer != null && viewer.isCreative() && Commands.LEVEL_ADMINS.check(viewer.permissions());
    }

    /** Size of the stack's NBT encoding in bytes; unencodable stacks report the largest size. */
    private static int encodedBytes(ItemStack stack, HolderLookup.Provider registries) {
        try {
            Tag tag = ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
                    .result().orElse(null);
            if (tag == null) return Integer.MAX_VALUE;
            ByteCounter counter = new ByteCounter();
            NbtIo.writeAnyTag(tag, new DataOutputStream(counter));
            return counter.count;
        } catch (IOException | RuntimeException unencodable) {
            return Integer.MAX_VALUE;
        }
    }

    private static final class ByteCounter extends OutputStream {
        private int count;
        @Override public void write(int value) { if (count < Integer.MAX_VALUE) count++; }
        @Override public void write(byte[] bytes, int offset, int length) {
            count = (int) Math.min(Integer.MAX_VALUE, (long) count + Math.max(0, length));
        }
    }

    public synchronized MarketListing postFromHand(ServerPlayer seller, int quantity, long price,
            long expiresAt, ListingSource source, String location, UUID requestId) {
        return postFromHandChecked(seller, quantity, price, expiresAt, source, location, requestId).row();
    }

    /**
     * {@link #postFromHand} with the reason. A Market Board listing is a sell order on the book:
     * whatever crosses an open buy order sells at once and the rest rests there. The row returned
     * restates what was offered, under the order's id.
     */
    public synchronized Posted<MarketListing> postFromHandChecked(ServerPlayer seller, int quantity, long price,
            long expiresAt, ListingSource source, String location, UUID requestId) {
        if (EconomyHealth.frozen()) return Posted.refused(PostResult.FROZEN);
        if (seller == null || quantity < 1 || price < 1L || expiresAt <= now()
                || source == null || location == null || requestId == null) return Posted.refused(PostResult.INVALID);
        if (source == ListingSource.MARKET_BOARD) {
            if (owner() == null) return Posted.refused(PostResult.INVALID);
            BookStacks.HandSale sale = BookStacks.sellFromHand(seller, quantity, price, requestId);
            Placed placed = sale.placed();
            if (!placed.ok()) return Posted.refused(postResult(placed.result()));
            return Posted.success(new MarketListing(placed.orderId(), seller.getUUID(), source, sale.offered(), price,
                    expiresAt, location, false, now()), placed.listingFee());
        }
        // Goods never enter the economy from a creative-mode or spectator inventory.
        if (!GoodsSource.allowed(seller)) return Posted.refused(PostResult.CREATIVE);
        if (!rememberRequest(seller.getUUID(), requestId)) return Posted.refused(PostResult.STALE_REQUEST);
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty() || held.getCount() < quantity) return Posted.refused(PostResult.NOT_HOLDING);
        ItemStack escrow = held.copyWithCount(quantity);
        if (!tradeable(escrow, seller.registryAccess()))
            return Posted.refused(escrow.is(UNTRADEABLE) ? PostResult.UNTRADEABLE : PostResult.OVERSIZED);
        UUID id = freshId();
        MarketListing listing = new MarketListing(id, seller.getUUID(), source, escrow, price, expiresAt, location,
                false, now());
        listings.put(id, listing);
        held.shrink(quantity);
        setDirty();
        return Posted.success(listing, 0L);
    }

    public synchronized MarketBuyOffer postBuyOffer(ServerPlayer buyer, Identifier itemId, int quantity,
            long unitPrice, long expiresAt, UUID requestId) {
        return postBuyOfferChecked(buyer, itemId, quantity, unitPrice, expiresAt, requestId).row();
    }

    /**
     * {@link #postBuyOffer} with the reason. A buy offer is a buy order on the book: whatever
     * crosses an open sell order is bought at once, and the rest rests there with its credits in
     * escrow. The row returned says how many are still wanted and what is held for them.
     */
    public synchronized Posted<MarketBuyOffer> postBuyOfferChecked(ServerPlayer buyer, Identifier itemId,
            int quantity, long unitPrice, long expiresAt, UUID requestId) {
        if (EconomyHealth.frozen()) return Posted.refused(PostResult.FROZEN);
        if (buyer == null || itemId == null || quantity < 1 || unitPrice < 1 || expiresAt <= now()
                || requestId == null || owner() == null) return Posted.refused(PostResult.INVALID);
        Placed placed = BookStacks.buyOrder(buyer.level().getServer(), buyer.getUUID(), buyer, itemId.toString(),
                quantity, unitPrice, requestId);
        if (!placed.ok()) return Posted.refused(postResult(placed.result()));
        return Posted.success(new MarketBuyOffer(placed.orderId(), buyer.getUUID(), itemId, placed.rested(), unitPrice,
                unitPrice * placed.rested(), expiresAt), placed.listingFee());
    }

    /**
     * Fills a buy order from the seller's main hand. Only an unmodified stack of the item the
     * order names is accepted: a renamed, enchanted, filled or worn stack is not what the buyer
     * escrowed credits for. The result says why a fill was refused.
     */
    public synchronized FillResult fillBuyOffer(ServerPlayer seller, UUID offerId, int quantity, UUID requestId) {
        return fillBuyOfferOutcome(seller, offerId, quantity, requestId).result();
    }

    /**
     * {@link #fillBuyOffer} with the amounts: what the sale was worth, the market fee taken from
     * it, and what was booked for the seller to collect. The proceeds were escrowed by the buyer
     * when the order was placed; the fee moves from that escrow to the Reserve.
     */
    public synchronized FillOutcome fillBuyOfferOutcome(ServerPlayer seller, UUID offerId, int quantity,
            UUID requestId) {
        if (EconomyHealth.frozen()) return FillOutcome.refused(FillResult.FROZEN);
        if (seller == null || offerId == null || quantity < 1 || requestId == null || owner() == null)
            return FillOutcome.refused(FillResult.INVALID);
        Traded traded = BookStacks.fillFromHand(seller, offerId, quantity, requestId);
        if (!traded.ok()) return FillOutcome.refused(fillResult(traded.result()));
        Fill fill = traded.fill();
        return new FillOutcome(FillResult.SUCCESS, fill.base(), fill.fee(), fill.sellerReceives());
    }

    /** The post result a refused order is reported as. */
    private static PostResult postResult(BookResult result) {
        return switch (result) {
            case OK -> PostResult.SUCCESS;
            case STALE_REQUEST -> PostResult.STALE_REQUEST;
            case LIMIT_REACHED -> PostResult.LIMIT_REACHED;
            case NOT_HOLDING -> PostResult.NOT_HOLDING;
            case UNKNOWN_ITEM -> PostResult.UNKNOWN_ITEM;
            case UNTRADEABLE -> PostResult.UNTRADEABLE;
            case OVERSIZED -> PostResult.OVERSIZED;
            case INSUFFICIENT_FUNDS -> PostResult.INSUFFICIENT_FUNDS;
            case FUNDS_LOCKED -> PostResult.FUNDS_LOCKED;
            case FEE_UNPAID -> PostResult.FEE_UNPAID;
            case STORAGE_FULL -> PostResult.STORAGE_FULL;
            case PAYOUT_BLOCKED -> PostResult.PAYOUT_BLOCKED;
            case CREATIVE -> PostResult.CREATIVE;
            case FROZEN -> PostResult.FROZEN;
            case MARKET_CLOSED -> PostResult.MARKET_CLOSED;
            default -> PostResult.INVALID;
        };
    }

    /** The fill result a refused fill is reported as. */
    private static FillResult fillResult(BookResult result) {
        return switch (result) {
            case OK -> FillResult.SUCCESS;
            case STALE_REQUEST -> FillResult.STALE_REQUEST;
            case NOT_FOUND -> FillResult.NOT_FOUND;
            case OWN_ORDER -> FillResult.OWN_OFFER;
            case WRONG_ITEM -> FillResult.WRONG_ITEM;
            case NOT_ENOUGH, NOT_HOLDING -> FillResult.NOT_ENOUGH;
            case MODIFIED_ITEM -> FillResult.MODIFIED_ITEM;
            case UNTRADEABLE -> FillResult.UNTRADEABLE;
            case PAYOUT_BLOCKED -> FillResult.PAYOUT_BLOCKED;
            case CREATIVE -> FillResult.CREATIVE;
            case FROZEN -> FillResult.FROZEN;
            case MARKET_CLOSED -> FillResult.MARKET_CLOSED;
            default -> FillResult.INVALID;
        };
    }

    /** The purchase result a refused purchase from the book is reported as. */
    private static PurchaseResult purchaseResult(BookResult result, boolean live) {
        return switch (result) {
            case OK -> PurchaseResult.SUCCESS;
            case STALE_REQUEST -> PurchaseResult.STALE_REQUEST;
            case NOT_FOUND -> PurchaseResult.NOT_FOUND;
            case OWN_ORDER -> PurchaseResult.OWN_LISTING;
            case OUT_OF_STOCK -> PurchaseResult.OUT_OF_STOCK;
            case PRICE_MOVED -> PurchaseResult.PRICE_MOVED;
            case INSUFFICIENT_FUNDS -> PurchaseResult.INSUFFICIENT_FUNDS;
            case FUNDS_LOCKED -> PurchaseResult.FUNDS_LOCKED;
            case STORAGE_FULL -> live ? PurchaseResult.INVENTORY_FULL : PurchaseResult.CLAIMS_FULL;
            case INVALID -> PurchaseResult.INVALID_QUANTITY;
            case FROZEN -> PurchaseResult.FROZEN;
            case MARKET_CLOSED -> PurchaseResult.MARKET_CLOSED;
            default -> PurchaseResult.INTERNAL_ERROR;
        };
    }

    /** Live buy offers, without the gallery's demo rows. */
    public synchronized List<MarketBuyOffer> searchBuyOffers(String query, int page, int pageSize, long now) {
        return searchBuyOffers(query, page, pageSize, now, false);
    }

    /** @param includeDemo true only for a viewer {@link #seesDemo} approves */
    public synchronized List<MarketBuyOffer> searchBuyOffers(String query, int page, int pageSize, long now,
            boolean includeDemo) {
        expire(now);
        int size = MarketLimits.pageSize(pageSize);
        return wanted(needle(query), now, includeDemo).stream().skip(MarketLimits.pageOffset(page, size)).limit(size)
                .toList();
    }

    public synchronized int searchBuyOfferCount(String query, long now) {
        return searchBuyOfferCount(query, now, false);
    }

    public synchronized int searchBuyOfferCount(String query, long now, boolean includeDemo) {
        expire(now);
        return wanted(needle(query), now, includeDemo).size();
    }

    /** Every open buy order on the book, and the gallery's demo rows when asked for, by item id and price. */
    private List<MarketBuyOffer> wanted(String needle, long now, boolean includeDemo) {
        List<MarketBuyOffer> rows = new ArrayList<>();
        EconomyStore store = owner();
        if (includeDemo) {
            // Rows a gallery from before the sandbox left here, then the sandbox's own.
            for (MarketBuyOffer value : buyOffers.values())
                if (value.demo() && matches(value.itemId(), needle)) rows.add(value);
            if (store != null) for (GallerySandbox.Row row : store.gallery().rows(false, needle)) {
                MarketBuyOffer view = sampleOfferView(row);
                if (view != null) rows.add(view);
            }
        }
        if (store != null) for (BuyOrder order : store.book().buysMatching(item -> matches(item, needle), now)) {
            MarketBuyOffer view = offerView(order);
            if (view != null) rows.add(view);
        }
        // A stable sort: within one item the book's own order, highest price first, is kept.
        rows.sort(Comparator.comparing(value -> value.itemId().toString()));
        return rows;
    }

    /** A buy order as the buy offer the terminal, the commands and the companion app know. */
    private static MarketBuyOffer offerView(BuyOrder order) {
        Identifier item = Identifier.tryParse(order.item());
        return item == null ? null : new MarketBuyOffer(order.id(), ownerId(order.owner()), item, order.remaining(),
                order.unitPrice(), order.escrow(), order.expiresAt());
    }

    /**
     * A sell order as the listing the terminal, the commands and the companion app know; null for
     * goods the game cannot name right now.
     */
    private static MarketListing listingView(EconomyStore store, SellOrder order) {
        ItemStack stock = store.goods().stackOf(order.lot());
        return stock.isEmpty() ? null : new MarketListing(order.id(), ownerId(order.owner()), ListingSource.MARKET_BOARD,
                stock, order.unitPrice(), Math.max(1L, order.expiresAt()), "", false, 0L);
    }

    /** The player who owns an order; a system account's order names no player. */
    private static UUID ownerId(AccountId account) {
        return account instanceof PlayerAccountId player ? player.playerId() : DEMO_ACCOUNT;
    }

    private static boolean matches(String itemId, String needle) {
        return needle.isEmpty() || itemId.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String needle(String query) { return query == null ? "" : query.toLowerCase(Locale.ROOT).trim(); }

    private static boolean matches(Identifier itemId, String needle) {
        return needle.isEmpty() || itemId.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Registers or atomically replaces a shop's central escrow. Caller owns the returned old stock.
     * Publishing the stock and price the store already holds changes nothing at all, so an
     * over-eager caller neither rewrites the file nor counts as owner activity.
     */
    public synchronized ItemStack replaceShopStock(UUID listingId, UUID owner, ItemStack next, long price,
            String location, boolean demo) {
        if (listingId == null || owner == null || next == null || price < 1L || location == null
                || EconomyHealth.frozen()) return ItemStack.EMPTY;
        MarketListing old = listings.get(listingId);
        if (old == null && next.isEmpty()) return ItemStack.EMPTY;
        if (old != null && old.baseUnitPrice() == price && old.demo() == demo && old.seller().equals(owner)
                && ItemStack.matches(old.stock(), next)) return ItemStack.EMPTY;
        listings.remove(listingId);
        if (!next.isEmpty()) listings.put(listingId, new MarketListing(listingId, owner, ListingSource.PLAYER_SHOP,
                next.copy(), price, Long.MAX_VALUE, location, demo, now()));
        setDirty();
        return old == null ? ItemStack.EMPTY : old.stock().copy();
    }

    /** Records an owner interaction with a shop, which restarts its idle-expiry clock. */
    public synchronized void touchShop(UUID listingId) {
        MarketListing listing = listings.get(listingId);
        if (listing == null || listing.source() != ListingSource.PLAYER_SHOP) return;
        listings.put(listingId, listing.withActivity(now()));
        setDirty();
    }

    /** The shop listing or sell order with this id, or null. */
    public synchronized MarketListing listing(UUID id) {
        MarketListing value = listings.get(id);
        if (value != null) return copy(value);
        EconomyStore store = owner();
        SellOrder order = store == null ? null : store.book().sell(id);
        return order == null ? null : listingView(store, order);
    }

    /** The buy order with this id, or null; the record is immutable, so it is handed out as it is. */
    public synchronized MarketBuyOffer buyOffer(UUID id) {
        if (id == null) return null;
        MarketBuyOffer value = buyOffers.get(id);
        if (value != null) return value;
        EconomyStore store = owner();
        BuyOrder order = store == null ? null : store.book().buy(id);
        return order == null ? null : offerView(order);
    }

    /** Live sell listings, without the gallery's demo rows. This is the search Link and plain players get. */
    public synchronized List<MarketListing> search(String query, int page, int pageSize, long now) {
        return search(query, page, pageSize, now, false);
    }

    /** @param includeDemo true only for a viewer {@link #seesDemo} approves */
    public synchronized List<MarketListing> search(String query, int page, int pageSize, long now,
            boolean includeDemo) {
        expire(now);
        int size = MarketLimits.pageSize(pageSize);
        return page(offered(needle(query), now, includeDemo), MarketLimits.pageOffset(page, size), size);
    }

    public synchronized int searchCount(String query, long now) {
        return searchCount(query, now, false);
    }

    public synchronized int searchCount(String query, long now, boolean includeDemo) {
        expire(now);
        return offered(needle(query), now, includeDemo).size();
    }

    /**
     * One terminal page: the matching sell listings — shops and the book's sell orders — and, when
     * asked for, the book's buy orders, with the total of each. A single call does one expiry pass
     * and filters each collection once.
     *
     * @param listingTotal  every matching sell listing, not just this page's
     * @param buyOfferTotal every matching buy order; 0 when buy orders were not asked for
     */
    public record SearchPage(List<MarketListing> listings, List<MarketBuyOffer> buyOffers, int listingTotal,
            int buyOfferTotal) { }

    /** @param includeDemo true only for a viewer {@link #seesDemo} approves */
    public synchronized SearchPage searchPage(String query, int page, int pageSize, long now, boolean includeDemo,
            boolean includeBuyOffers) {
        expire(now);
        String needle = needle(query);
        int size = MarketLimits.pageSize(pageSize);
        long offset = MarketLimits.pageOffset(page, size);
        List<Offered> sells = offered(needle, now, includeDemo);
        List<MarketBuyOffer> buys = !includeBuyOffers ? List.of() : wanted(needle, now, includeDemo);
        return new SearchPage(page(sells, offset, size), buys.stream().skip(offset).limit(size).toList(),
                sells.size(), buys.size());
    }

    /**
     * One row of {@link #searchOffered}: the row as a listing, whether it is a player shop, and the
     * account that sells it. The account stays on the server: the Economy Hub turns it into a seller
     * kind ("a player", "a shop", the Merchant House's name) and never sends it.
     */
    public record OfferedRow(MarketListing listing, boolean shop, AccountId seller) { }

    /** A page of {@link #searchOffered}, with how many rows matched in all. */
    public record OfferedPage(List<OfferedRow> rows, int total) { }

    /**
     * Everything on offer that matches the search — shops and the book's sell orders, Merchant
     * House orders among them — in the same order as {@link #searchPage}, optionally only from
     * sellers {@code sellers} accepts (a Merchant Stall shows one House).
     *
     * @param sellers which selling accounts to keep; null keeps every one
     */
    public synchronized OfferedPage searchOffered(String query, int page, int pageSize, long now, boolean includeDemo,
            Predicate<AccountId> sellers) {
        expire(now);
        int size = MarketLimits.pageSize(pageSize);
        long offset = MarketLimits.pageOffset(page, size);
        List<Offered> rows = new ArrayList<>();
        for (Offered row : offered(needle(query), now, includeDemo))
            if (sellers == null || sellers.test(sellerOf(row))) rows.add(row);
        EconomyStore store = owner();
        List<OfferedRow> out = new ArrayList<>(size);
        for (long index = offset; index < rows.size() && out.size() < size; index++) {
            Offered row = rows.get((int) index);
            MarketListing view = row.listing() != null ? copy(row.listing()) : listingView(store, row.order());
            if (view != null) out.add(new OfferedRow(view, row.listing() != null
                    && row.listing().source() == ListingSource.PLAYER_SHOP, sellerOf(row)));
        }
        return new OfferedPage(out, rows.size());
    }

    private static AccountId sellerOf(Offered row) {
        return row.listing() != null ? new PlayerAccountId(row.listing().seller()) : row.order().owner();
    }

    /** One thing on offer: a shop listing held here, or a sell order on the book. */
    private record Offered(String item, long price, long sequence, MarketListing listing, SellOrder order) { }

    /**
     * Everything on offer that matches: shop listings and the book's sell orders, by item id, then
     * cheapest first, then oldest first. Orders the game cannot name right now are left out.
     */
    private List<Offered> offered(String needle, long now, boolean includeDemo) {
        List<Offered> rows = new ArrayList<>();
        for (MarketListing row : listings.values())
            if (row.availableQuantity() > 0 && (includeDemo || !row.demo()) && matches(row.itemId(), needle))
                rows.add(new Offered(row.itemId().toString(), row.baseUnitPrice(), 0L, row, null));
        EconomyStore store = owner();
        // The gallery's sample rows are read from its sandbox, for a viewer allowed to see them only.
        if (includeDemo && store != null) for (GallerySandbox.Row row : store.gallery().rows(true, needle)) {
            MarketListing view = sampleListingView(row);
            if (view != null) rows.add(new Offered(view.itemId().toString(), view.baseUnitPrice(), 0L, view, null));
        }
        if (store != null) {
            GoodsSection goods = store.goods();
            for (SellOrder order : store.book().sellsMatching(item -> matches(item, needle), now))
                if (goods.showable(order.lot()))
                    rows.add(new Offered(order.item(), order.unitPrice(), order.sequence(), null, order));
        }
        rows.sort(Comparator.comparing(Offered::item).thenComparingLong(Offered::price)
                .thenComparingLong(Offered::sequence));
        return rows;
    }

    /** One page of {@link #offered}, as listings. */
    private List<MarketListing> page(List<Offered> rows, long offset, int size) {
        EconomyStore store = owner();
        List<MarketListing> out = new ArrayList<>(size);
        for (long index = offset; index < rows.size() && out.size() < size; index++) {
            Offered row = rows.get((int) index);
            MarketListing view = row.listing() != null ? copy(row.listing()) : listingView(store, row.order());
            if (view != null) out.add(view);
        }
        return out;
    }

    /**
     * The unit price to show {@code buyer} for a listing right now: the price after shocks and
     * price modifiers, with a discount cut back to the market fee the sale pays the Reserve — and
     * when even that is a discount the Reserve cannot pay, the seller's own price, which is what
     * the purchase would then settle at. It is exactly what a purchase is charged per unit.
     */
    public static long buyerUnitPrice(MarketListing listing, UUID buyer) {
        return Settlement.buyerUnitPrice(listing.baseUnitPrice(), PricingEngine.quote(listing, buyer).unitPrice(),
                NeroEconomyCommon.CURRENCY_PROVIDER.systemBalance(SystemAccountId.RESERVE),
                EconomyConfig.MARKET_FEE_PERCENT.get());
    }

    /** Settles without an item check, for callers that have no "item the buyer saw" to offer. */
    public synchronized PurchaseResult purchase(ServerPlayer buyer, UUID listingId, int quantity,
            long quotedUnitPrice, UUID requestId) {
        return purchase(buyer, listingId, quantity, quotedUnitPrice, requestId, null);
    }

    /**
     * Re-prices and settles exactly once. The goods go into the buyer's inventory when all of them
     * fit there, and into the buyer's vault otherwise; when neither can take them the purchase is
     * refused before any credit moves, so a full or disconnecting buyer can never lose purchased
     * goods.
     *
     * <p>{@code quotedUnitPrice} is a ceiling: execution above it is refused, execution below it is
     * charged at the lower price. {@code expectedItem}, when given, is the item the buyer was shown;
     * a shop's listing id survives a restock, so without it the owner could swap the goods between
     * the buyer's look and click.
     */
    public synchronized PurchaseResult purchase(ServerPlayer buyer, UUID listingId, int quantity,
            long quotedUnitPrice, UUID requestId, Identifier expectedItem) {
        return purchaseOutcome(buyer, listingId, quantity, quotedUnitPrice, requestId, expectedItem).result();
    }

    /** {@link #purchase} with the amounts: what the buyer paid, the fee, and what the seller receives. */
    public synchronized PurchaseOutcome purchaseOutcome(ServerPlayer buyer, UUID listingId, int quantity,
            long quotedUnitPrice, UUID requestId, Identifier expectedItem) {
        if (buyer == null) return PurchaseOutcome.refused(PurchaseResult.INVALID_QUANTITY);
        return settlePurchase(buyer.getUUID(), buyer, null, listingId, quantity, quotedUnitPrice, requestId,
                expectedItem);
    }

    /**
     * A purchase for a buyer who is not in game, such as a companion-app trade. It runs the same
     * checks as {@link #purchase} in the same order, with one difference: there is no inventory to
     * fit the goods into, so they are delivered to the buyer's vault and wait at a Bank Terminal.
     * The vault's room is still checked before any credit moves.
     *
     * @param server only used to raise the seller's sold-out alert
     */
    public synchronized PurchaseResult purchaseToClaim(MinecraftServer server, UUID buyer, UUID listingId,
            int quantity, long quotedUnitPrice, UUID requestId) {
        return purchaseToClaimOutcome(server, buyer, listingId, quantity, quotedUnitPrice, requestId).result();
    }

    /** {@link #purchaseToClaim} with the amounts. */
    public synchronized PurchaseOutcome purchaseToClaimOutcome(MinecraftServer server, UUID buyer, UUID listingId,
            int quantity, long quotedUnitPrice, UUID requestId) {
        if (buyer == null) return PurchaseOutcome.refused(PurchaseResult.INVALID_QUANTITY);
        return settlePurchase(buyer, null, server, listingId, quantity, quotedUnitPrice, requestId, null);
    }

    /**
     * The one settlement path behind both purchase entry points.
     *
     * <p><b>Who pays what.</b> A price modifier changes the buyer's price only. With {@code base}
     * the seller's own price for the quantity and {@code fee} the market fee on it:
     * <ul>
     *   <li>the seller receives {@code base − fee} — into their credit claim for a board listing,
     *       straight into their balance for a shop;</li>
     *   <li>the fee goes to the Reserve;</li>
     *   <li>a surcharge (the buyer's price above {@code base}) goes to the Reserve;</li>
     *   <li>a discount (the buyer's price below {@code base}) is paid by the Reserve, and is never
     *       more than the fee the same sale pays it (see {@link Settlement#cappedUnitPrice}). When
     *       the Reserve cannot pay it, the purchase goes through at {@code base} instead — but only
     *       if that is still within the price the buyer confirmed.</li>
     * </ul>
     * Every credit movement of one purchase is a single ledger operation: all of it or none.
     *
     * @param live   the buyer's player when they are in game; null delivers the goods to their vault
     * @param server the server to raise alerts on when there is no live buyer to take it from
     */
    private PurchaseOutcome settlePurchase(UUID buyerId, ServerPlayer live, MinecraftServer server, UUID listingId,
            int quantity, long quotedUnitPrice, UUID requestId, Identifier expectedItem) {
        if (EconomyHealth.frozen()) return PurchaseOutcome.refused(PurchaseResult.FROZEN);
        if (marketClosed()) return PurchaseOutcome.refused(PurchaseResult.MARKET_CLOSED);
        if (listingId == null || requestId == null || quantity < 1)
            return PurchaseOutcome.refused(PurchaseResult.INVALID_QUANTITY);
        if (!rememberRequest(buyerId, requestId)) return PurchaseOutcome.refused(PurchaseResult.STALE_REQUEST);
        MarketListing listing = listings.get(listingId);
        if (listing == null) {
            // Not a shop: a sell order on the book, settled by the book's own engine.
            PurchaseOutcome fromBook = purchaseFromBook(buyerId, live, listingId, quantity, quotedUnitPrice, expectedItem);
            if (fromBook != null) return fromBook;
        }
        // A demo row backs no real stock and is never purchasable, whoever can see it.
        if (listing == null || listing.demo() || listing.expiresAt() <= now())
            return PurchaseOutcome.refused(PurchaseResult.NOT_FOUND);
        if (expectedItem != null && !listing.itemId().equals(expectedItem))
            return PurchaseOutcome.refused(PurchaseResult.ITEM_CHANGED);
        if (listing.seller().equals(buyerId)) return PurchaseOutcome.refused(PurchaseResult.OWN_LISTING);
        if (listing.availableQuantity() < quantity) return PurchaseOutcome.refused(PurchaseResult.OUT_OF_STOCK);
        PriceQuote execution = PricingEngine.quote(listing, buyerId);
        // A discount is cut back to the fee this sale pays the Reserve, so the Reserve never loses on it.
        long unit = Settlement.cappedUnitPrice(listing.baseUnitPrice(), execution.unitPrice(),
                EconomyConfig.MARKET_FEE_PERCENT.get());
        if (!PricingEngine.executionPriceWithinQuote(quotedUnitPrice, unit))
            return PurchaseOutcome.refused(PurchaseResult.PRICE_MOVED);
        long total;
        long base;
        try {
            total = Math.multiplyExact(unit, (long) quantity);
            base = Math.multiplyExact(listing.baseUnitPrice(), (long) quantity);
        } catch (ArithmeticException overflow) { return PurchaseOutcome.refused(PurchaseResult.INVALID_QUANTITY); }
        ItemStack goods = listing.stock().copyWithCount(quantity);
        // Where the goods will go is settled before any credit moves: the inventory of a buyer who is
        // in game when all of them fit there, and the buyer's vault otherwise. Neither: no sale.
        boolean toInventory = live != null && VaultStacks.fits(live, goods);
        if (!toInventory && !vaultCanAccept(buyerId, goods))
            return PurchaseOutcome.refused(live != null ? PurchaseResult.INVENTORY_FULL : PurchaseResult.CLAIMS_FULL);
        boolean board = listing.source() == ListingSource.MARKET_BOARD;
        // The fee is worked out on the seller's own price for the whole order.
        Settlement.Sale sale = Settlement.sale(base, EconomyConfig.MARKET_FEE_PERCENT.get());
        long fee = sale.fee();
        long proceeds = sale.proceeds();
        if (board && proceeds > 0L && !creditClaims.canAccept(listing.seller(), proceeds))
            return PurchaseOutcome.refused(PurchaseResult.INTERNAL_ERROR);
        LedgerResult settled = NeroEconomyCommon.CURRENCY_PROVIDER.execute(
                Settlement.purchase(buyerId, listing.seller(), board, base, total, fee));
        boolean discountSkipped = false;
        if (settled == LedgerResult.RESERVE_SHORT && total < base) {
            // The Reserve cannot pay the discount. The seller's own price still stands, as long as
            // it is not more than the buyer agreed to.
            if (!PricingEngine.executionPriceWithinQuote(quotedUnitPrice, listing.baseUnitPrice()))
                return PurchaseOutcome.refused(PurchaseResult.PRICE_MOVED);
            total = base;
            discountSkipped = true;
            settled = NeroEconomyCommon.CURRENCY_PROVIDER.execute(
                    Settlement.purchase(buyerId, listing.seller(), board, base, total, fee));
        }
        if (!settled.ok()) return PurchaseOutcome.refused(switch (settled) {
            case FROZEN -> PurchaseResult.FROZEN;
            case FUNDS_LOCKED -> PurchaseResult.FUNDS_LOCKED;
            case INSUFFICIENT_FUNDS -> PurchaseResult.INSUFFICIENT_FUNDS;
            default -> PurchaseResult.INTERNAL_ERROR;
        });
        // The seller's proceeds are in the escrow account now; this records whose they are.
        if (board) bookEscrowed(listing.seller(), TransactionType.MARKET_PAYOUT, proceeds);
        // Nothing below can reject. Whatever the inventory does not take after all goes to the vault,
        // which always books, and a move into a live inventory asks for the prompt save.
        if (toInventory && owner() != null) VaultStacks.giveOrDeliver(owner(), live, goods.copy());
        else deliverToVault(buyerId, goods);
        int remaining = listing.availableQuantity() - quantity;
        if (remaining == 0) listings.remove(listingId);
        // A sale is activity: it restarts a shop listing's idle-expiry clock.
        else listings.put(listingId, listing.withStock(listing.stock().copyWithCount(remaining)).withActivity(now()));
        setDirty();
        if (remaining == 0 && listing.source() == ListingSource.PLAYER_SHOP)
            EconomyAlerts.raise(live != null ? live.level().getServer() : server, listing.seller(),
                    EconomyAlerts.Kind.SHOP_SOLD_OUT, LinkAlert.Severity.INFO);
        return new PurchaseOutcome(PurchaseResult.SUCCESS, total, base, fee, proceeds, discountSkipped, !toInventory);
    }

    /**
     * A purchase from a sell order on the book, at up to the unit price the buyer confirmed. The
     * book settles it as one ledger operation and puts the goods in the buyer's inventory when
     * they are in game and it fits, in their vault otherwise.
     *
     * @return null when the id names no sell order
     */
    private PurchaseOutcome purchaseFromBook(UUID buyerId, ServerPlayer live, UUID orderId, int quantity,
            long quotedUnitPrice, Identifier expectedItem) {
        EconomyStore store = owner();
        SellOrder order = store == null ? null : store.book().sell(orderId);
        if (order == null) return null;
        if (expectedItem != null && !order.item().equals(expectedItem.toString()))
            return PurchaseOutcome.refused(PurchaseResult.ITEM_CHANGED);
        Traded traded = BookStacks.buyFromOrder(store, buyerId, live, orderId, quantity, quotedUnitPrice);
        if (!traded.ok()) return PurchaseOutcome.refused(purchaseResult(traded.result(), live != null));
        Fill fill = traded.fill();
        return new PurchaseOutcome(PurchaseResult.SUCCESS, fill.buyerPaid(), fill.base(), fill.fee(),
                fill.sellerReceives(), fill.discountSkipped(), traded.toVault());
    }

    /**
     * Records that credits already sitting in the escrow account are owed to {@code player}. Every
     * caller checks {@link CreditClaims#canAccept} before it moves anything, and a claim total can
     * never pass the supply it is part of, so a refusal here is not expected; if it happens anyway
     * the credits go to the Reserve rather than stay in escrow with no owner on record.
     */
    private void bookEscrowed(UUID player, TransactionType reason, long amount) {
        if (amount <= 0L || creditClaims.add(player, reason, amount)) return;
        NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A credit claim could not be recorded; the amount was "
                + "returned to the Reserve.");
        NeroEconomyCommon.CURRENCY_PROVIDER.execute(Settlement.escrowToReserve(amount, TransactionType.ESCROW_REFUND));
    }

    /**
     * Takes everything out of the player's vault that fits their inventory; see
     * {@link VaultStacks#collect}. This is what "Collect" does for goods at every terminal.
     *
     * @return how many items were moved
     */
    public synchronized int collectItems(ServerPlayer player) {
        return player == null ? 0 : VaultStacks.collect(owner(), player);
    }

    /**
     * Delivers goods this section owes a player to their vault. This is the path purchases, fills,
     * refunds, expiry and shop teardown use, and it ignores every cap on purpose: a cap may refuse
     * a new trade, but it must never be the reason goods are lost. A section that stands alone has
     * no vault and keeps the stack as a claim, which the store delivers when it adopts the section.
     */
    private void deliverToVault(UUID player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return;
        EconomyStore store = owner();
        if (store != null) VaultStacks.deliver(store, player, stack);
        else legacyItemClaims.computeIfAbsent(player, ignored -> new ArrayList<>()).add(stack.copy());
    }

    /**
     * Pre-flight for a voluntary trade — a purchase, a fill, a cancel: whether the player's vault
     * can take {@code stack}. Checked before any credit or item moves.
     */
    private boolean vaultCanAccept(UUID player, ItemStack stack) {
        EconomyStore store = owner();
        return player != null && (store == null || VaultStacks.canAccept(store, player, List.of(stack)));
    }

    /**
     * Pays out everything owed to the player: one transfer from the escrow account per reason, so
     * that a refund is recorded as a refund. A reason whose transfer is refused stays claimable.
     * The claim and the credits are in the same store, so no save is needed to keep them in step.
     *
     * @return true when anything was paid out
     */
    public synchronized boolean collectCredits(UUID player) {
        return collectCreditsTotal(player) > 0L;
    }

    /** {@link #collectCredits} with the amount: how many credits reached the player's balance. */
    public synchronized long collectCreditsTotal(UUID player) {
        if (player == null || EconomyHealth.frozen()) return 0L;
        long collected = 0L;
        EconomyStore store = owner();
        for (Map.Entry<TransactionType, Long> bucket : creditClaims.buckets(player).entrySet()) {
            if (bucket.getValue() <= 0L) continue;
            LedgerPlan plan = Settlement.collect(player, bucket.getValue(), bucket.getKey());
            // Through the store, so the same code pays out in the game and in a unit test.
            if (!(store != null ? store.execute(plan) : NeroEconomyCommon.CURRENCY_PROVIDER.execute(plan)).ok()) continue;
            creditClaims.settle(player, bucket.getKey());
            collected += bucket.getValue();
        }
        if (collected > 0L) setDirty();
        return collected;
    }

    /** What is waiting for the player to collect, in credits. */
    public synchronized long creditClaimTotal(UUID player) {
        return player == null ? 0L : creditClaims.total(player);
    }

    /**
     * Records that credits <b>already in the escrow account</b> are now owed to {@code player},
     * under the audit reason they will be collected as: a refund of the player's own escrow, or a
     * payout of somebody else's. Nothing moves in the ledger here, so a caller may only book what
     * an obligation it is closing in the same step was holding in escrow. An overflowing claim is
     * rejected outright and the caller must keep the escrow it was about to release.
     */
    public synchronized boolean addCreditClaim(UUID player, long amount, TransactionType reason) {
        if (!creditClaims.add(player, reason, amount)) return false;
        setDirty();
        return true;
    }

    /**
     * Every credit this section records as owed and held in escrow: the escrow behind each open
     * buy offer and every uncollected credit claim. Demo rows hold none.
     */
    public synchronized long escrowObligations() {
        long offers = 0L;
        for (MarketBuyOffer offer : buyOffers.values())
            if (!offer.demo()) offers = EscrowMath.total(offers, offer.escrow());
        return EscrowMath.total(offers, creditClaims.grandTotal());
    }

    @Override public synchronized boolean isEmpty() {
        return listings.isEmpty() && buyOffers.isEmpty() && legacyItemClaims.isEmpty()
                && creditClaims.snapshot().isEmpty() && requests.snapshot().isEmpty();
    }

    /** Pre-flight for callers that must not debit or delete anything before a claim can be booked. */
    public synchronized boolean canAcceptCreditClaim(UUID player, long amount) {
        return creditClaims.canAccept(player, amount);
    }

    /**
     * Hands over the item claims read from a save written before the vault existed, and forgets
     * them. Called by the store, which delivers them to the owners' vaults. Claims that only
     * mirror a vault saved in the same store are dropped instead, unless {@code evenMirrored}.
     */
    public synchronized Map<UUID, List<ItemStack>> drainLegacyItemClaims(boolean evenMirrored) {
        Map<UUID, List<ItemStack>> claims = new LinkedHashMap<>();
        if (evenMirrored || !legacyClaimsMirrorVault)
            legacyItemClaims.forEach((player, stacks) -> claims.put(player, List.copyOf(stacks)));
        boolean held = !legacyItemClaims.isEmpty();
        legacyItemClaims.clear();
        legacyClaimsMirrorVault = false;
        if (held) setDirty();
        return claims;
    }

    /**
     * From now on this section is saved with {@code vaults} in place of its item claims, marked as
     * a mirror. Only the store calls this, in the one session that adopted the section from its
     * own old file: that file stays the fallback copy until the combined store has been read back
     * from disk, and without the mirror an erasure in that session would rewrite it with every
     * player's migrated goods missing. The combined store ignores a mirror when it loads; a
     * rebuild from the old file takes it.
     */
    public synchronized void mirrorVaultInOldFile(Supplier<Map<UUID, List<ItemStack>>> vaults) {
        vaultMirror = vaults;
    }

    /**
     * Market Board rows as a save from before the order book kept them: sell listings and buy
     * offers. Only used to carry such rows onto the book, and to mirror the book into the old file.
     */
    public record LegacyBoardRows(List<MarketListing> listings, List<MarketBuyOffer> buyOffers) {
        public boolean isEmpty() { return listings.isEmpty() && buyOffers.isEmpty(); }
    }

    /**
     * Hands over the Market Board listings and buy offers read from a save written before the
     * order book existed, and forgets them. Called by the store, which places them on the book.
     * Shop listings and demo rows stay. Rows that only mirror a book saved in the same store are
     * dropped instead, unless {@code evenMirrored}.
     */
    public synchronized LegacyBoardRows drainLegacyBoardRows(boolean evenMirrored) {
        List<MarketListing> sells = new ArrayList<>();
        List<MarketBuyOffer> buys = new ArrayList<>();
        for (var iterator = listings.values().iterator(); iterator.hasNext();) {
            MarketListing listing = iterator.next();
            if (listing.demo() || listing.source() != ListingSource.MARKET_BOARD) continue;
            sells.add(listing);
            iterator.remove();
        }
        for (var iterator = buyOffers.values().iterator(); iterator.hasNext();) {
            MarketBuyOffer offer = iterator.next();
            if (offer.demo()) continue;
            buys.add(offer);
            iterator.remove();
        }
        boolean mirrored = legacyBoardMirrorsBook;
        legacyBoardMirrorsBook = false;
        if (!sells.isEmpty() || !buys.isEmpty()) setDirty();
        return mirrored && !evenMirrored ? new LegacyBoardRows(List.of(), List.of()) : new LegacyBoardRows(sells, buys);
    }

    /**
     * From now on this section is saved with {@code book} in place of its board rows, marked as a
     * mirror; the counterpart of {@link #mirrorVaultInOldFile} for the order book. The old market
     * file stays the fallback copy until the combined store has been read back from disk, and
     * without the mirror an erasure in that session would rewrite it with every player's migrated
     * listings and buy offers missing. The combined store ignores a mirror when it loads; a
     * rebuild from the old file takes it.
     */
    public synchronized void mirrorBookInOldFile(Supplier<LegacyBoardRows> book) {
        bookMirror = book;
    }

    /**
     * Shop teardown: the shop is gone or being emptied, so its escrow goes back to whoever it was
     * listed for. Not subject to the vault cap. A demo listing backs no real stock and is simply
     * destroyed.
     */
    public synchronized void releaseShop(UUID listingId) {
        MarketListing listing = listingId == null ? null : listings.get(listingId);
        if (listing == null || listing.source() != ListingSource.PLAYER_SHOP) return;
        listings.remove(listingId);
        if (!listing.demo()) deliverToVault(listing.seller(), listing.stock());
        setDirty();
    }

    /**
     * Releases every shop listing recorded at one {@code dimension|position} location. Used when the
     * shop block there has gone without its block entity being able to say so.
     *
     * @return how many listings were released
     */
    public synchronized int releaseShopsAt(String location) {
        if (location == null || location.isEmpty()) return 0;
        List<UUID> stranded = listings.values().stream()
                .filter(row -> row.source() == ListingSource.PLAYER_SHOP && row.location().equals(location))
                .map(MarketListing::listingId).toList();
        stranded.forEach(this::releaseShop);
        return stranded.size();
    }

    /** Every location that currently has a shop listing, for the stranded-shop sweep. */
    public synchronized List<String> shopLocations() {
        return listings.values().stream().filter(row -> row.source() == ListingSource.PLAYER_SHOP)
                .map(MarketListing::location).distinct().toList();
    }

    /**
     * Cancels the caller's own sell order; its goods go to their vault. Idempotent: a second call
     * finds nothing. A shop listing is not cancelled here — its stock is taken back at the shop.
     */
    public synchronized CancelResult cancelBoardListing(UUID listingId, UUID actor) {
        if (EconomyHealth.frozen()) return CancelResult.FROZEN;
        if (listingId == null || actor == null) return CancelResult.NOT_FOUND;
        MarketListing listing = listings.get(listingId);
        if (listing != null) return listing.seller().equals(actor) ? CancelResult.NOT_CANCELLABLE : CancelResult.NOT_FOUND;
        EconomyStore store = owner();
        if (store == null || store.book().sell(listingId) == null) return CancelResult.NOT_FOUND;
        return store.book().cancel(Trading.env(store), listingId, new PlayerAccountId(actor));
    }

    /**
     * Cancels the caller's own buy order; whatever escrow has not been spent on fills becomes their
     * credit claim, recorded as an escrow refund. The credits stay in the escrow account until the
     * claim is collected. Idempotent: a second call finds nothing.
     */
    public synchronized CancelResult cancelBuyOffer(UUID offerId, UUID actor) {
        if (EconomyHealth.frozen()) return CancelResult.FROZEN;
        if (offerId == null || actor == null) return CancelResult.NOT_FOUND;
        EconomyStore store = owner();
        if (store == null || store.book().buy(offerId) == null) return CancelResult.NOT_FOUND;
        return store.book().cancel(Trading.env(store), offerId, new PlayerAccountId(actor));
    }

    /** The caller's own live sell listings, shops and sell orders alike; never anyone else's, never a demo row. */
    public synchronized List<MarketListing> listingsOf(UUID player) {
        List<MarketListing> out = new ArrayList<>();
        for (MarketListing row : listings.values()) if (!row.demo() && row.seller().equals(player)) out.add(copy(row));
        EconomyStore store = owner();
        if (store != null && player != null) for (SellOrder order : store.book().sellsOf(new PlayerAccountId(player))) {
            MarketListing view = listingView(store, order);
            if (view != null) out.add(view);
        }
        return out;
    }

    /** The caller's own live buy orders; never anyone else's, never a demo row. */
    public synchronized List<MarketBuyOffer> buyOffersOf(UUID player) {
        List<MarketBuyOffer> out = new ArrayList<>();
        EconomyStore store = owner();
        if (store != null && player != null) for (BuyOrder order : store.book().buysOf(new PlayerAccountId(player))) {
            MarketBuyOffer view = offerView(order);
            if (view != null) out.add(view);
        }
        return out;
    }

    /**
     * A gallery sample sell row as the listing the terminal shows. It is built fresh for each
     * search and never stored here: nothing can look it up by id, so nothing can buy it. Null for
     * an item the game cannot name.
     */
    private static MarketListing sampleListingView(GallerySandbox.Row row) {
        Identifier id = Identifier.tryParse(row.itemId());
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        ItemStack stock = item == null ? ItemStack.EMPTY : new ItemStack(item, row.quantity());
        if (stock.isEmpty()) return null;
        return new MarketListing(row.id(), DEMO_ACCOUNT, ListingSource.MARKET_BOARD, stock, row.unitPrice(),
                Long.MAX_VALUE, "", true, 0L);
    }

    /** A gallery sample buy row as the buy offer the terminal shows: no escrow, never fillable. */
    private static MarketBuyOffer sampleOfferView(GallerySandbox.Row row) {
        Identifier id = Identifier.tryParse(row.itemId());
        if (id == null) return null;
        return new MarketBuyOffer(row.id(), DEMO_ACCOUNT, id, row.quantity(), row.unitPrice(), 0L, Long.MAX_VALUE, true);
    }

    /**
     * Removes every demo row held here — demo shop listings, and board rows a gallery built before
     * the sandbox left behind — without creating a claim. @return rows removed
     */
    public synchronized int clearDemo() {
        int before = listings.size() + buyOffers.size();
        listings.values().removeIf(MarketListing::demo);
        buyOffers.values().removeIf(MarketBuyOffer::demo);
        int removed = before - listings.size() - buyOffers.size();
        if (removed > 0) setDirty();
        return removed;
    }

    public synchronized void expire(long now) {
        // Expiry refunds escrow; nothing is refunded, and nothing lapses, while the economy is frozen.
        if (EconomyHealth.frozen()) return;
        boolean changed = false;
        int idleDays = EconomyConfig.SHOP_LISTING_MAX_IDLE_DAYS.get();
        for (var iterator = listings.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            MarketListing listing = entry.getValue();
            if (listing.source() == ListingSource.PLAYER_SHOP && listing.lastActivityAt() <= 0L) {
                // A row from before activity was tracked: its idle clock starts now.
                listing = listing.withActivity(now);
                entry.setValue(listing);
                changed = true;
            }
            boolean idle = listing.source() == ListingSource.PLAYER_SHOP && idleDays > 0
                    && now - listing.lastActivityAt() >= idleDays * MILLIS_PER_DAY;
            if (listing.expiresAt() > now && !idle) continue;
            // A demo row backs nothing, so it leaves without a delivery.
            if (!listing.demo()) deliverToVault(listing.seller(), listing.stock());
            iterator.remove();
            changed = true;
        }
        for (var iterator = buyOffers.entrySet().iterator(); iterator.hasNext();) {
            MarketBuyOffer offer = iterator.next().getValue();
            if (offer.expiresAt() > now) continue;
            // Only drop the offer once its escrow has actually been booked back to the buyer.
            long refund = offer.demo() ? 0L : EscrowLifecycle.expire(offer.escrow());
            if (refund > 0L && !addCreditClaim(offer.buyer(), refund, TransactionType.ESCROW_REFUND)) continue;
            iterator.remove(); changed = true;
        }
        changed |= requests.expire(now);
        if (changed) setDirty();
    }

    /**
     * Every row removed here is single-party — the erased player's own listing, offer, or claim — so
     * removal cannot strand a counterparty. Cross-party settlement happens in the obligation store,
     * which must therefore run its erasure hook before this one.
     *
     * <p>The credits behind the erased player's buy offers and uncollected claims are in the escrow
     * account; they move to the Reserve as an erasure sweep, in a row that names no player. The
     * erased player's goods are destroyed: the stock of their listings here, and their vault by the
     * vault section's own eraser.
     */
    public synchronized void forgetPlayer(UUID player) {
        if (player == null) return;
        long escrowed = creditClaims.total(player);
        for (MarketBuyOffer offer : buyOffers.values())
            if (!offer.demo() && offer.buyer().equals(player)) escrowed = EscrowMath.total(escrowed, offer.escrow());
        boolean changed = listings.entrySet().removeIf(entry -> entry.getValue().seller().equals(player));
        changed |= buyOffers.entrySet().removeIf(entry -> entry.getValue().buyer().equals(player));
        changed |= legacyItemClaims.remove(player) != null;
        changed |= creditClaims.forget(player);
        changed |= requests.forget(player);
        EconomySavedData ledger = ledger();
        if (ledger != null) ledger.sweepErasedEscrow(escrowed);
        if (changed) setDirty();
    }

    /**
     * The one idempotency gate for value-moving intents — market, bank and Link alike. Records the id
     * against the player and reports whether it was new; a replay within
     * {@link RequestLedger#TTL_MILLIS} returns false. Ids are persisted, so a replay across a restart
     * is refused too, and they are dropped by the expiry sweep and by erasure.
     */
    public synchronized boolean rememberRequest(UUID player, UUID requestId) {
        if (!requests.remember(player, requestId, now())) return false;
        setDirty();
        return true;
    }

    /**
     * Data-subject export: everything this store holds for one player. Items are reported as id and
     * count only, and no row names anyone else — a listing or offer here is the subject's own. Goods
     * waiting for the player are in their vault, which the vault section exports.
     */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        JsonArray own = new JsonArray();
        for (MarketListing listing : listings.values()) {
            if (!listing.seller().equals(player)) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", listing.listingId().toString());
            row.addProperty("item", listing.itemId().toString());
            row.addProperty("count", listing.availableQuantity());
            row.addProperty("unit_price", listing.baseUnitPrice());
            // A shop listing has no expiry; its sentinel is left out rather than exported as a date.
            if (listing.expiresAt() != Long.MAX_VALUE) row.addProperty("expires_at", listing.expiresAt());
            row.addProperty("source", listing.source().name());
            row.addProperty("location", listing.location());
            if (listing.lastActivityAt() > 0L) row.addProperty("last_activity_at", listing.lastActivityAt());
            if (listing.demo()) row.addProperty("demo", true);
            own.add(row);
        }
        out.add("listings", own);
        JsonArray offers = new JsonArray();
        for (MarketBuyOffer offer : buyOffers.values()) {
            if (!offer.buyer().equals(player)) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", offer.offerId().toString());
            row.addProperty("item", offer.itemId().toString());
            row.addProperty("remaining", offer.remaining());
            row.addProperty("unit_price", offer.unitPrice());
            row.addProperty("escrow", offer.escrow());
            row.addProperty("expires_at", offer.expiresAt());
            offers.add(row);
        }
        out.add("buy_offers", offers);
        out.addProperty("credit_claim", creditClaims.total(player));
        JsonObject reasons = new JsonObject();
        creditClaims.buckets(player).forEach((reason, amount) -> reasons.addProperty(reason.name(), amount));
        out.add("credit_claim_by_reason", reasons);
        JsonArray recent = new JsonArray();
        requests.of(player).forEach((id, at) -> {
            JsonObject row = new JsonObject();
            row.addProperty("id", id.toString());
            row.addProperty("at", at);
            recent.add(row);
        });
        out.add("recent_request_ids", recent);
        return out;
    }

    private UUID freshId() { UUID id; do id = UUID.randomUUID(); while (listings.containsKey(id) || buyOffers.containsKey(id)); return id; }
    private static MarketListing copy(MarketListing value) {
        return new MarketListing(value.listingId(), value.seller(), value.source(), value.stock(),
                value.baseUnitPrice(), value.expiresAt(), value.location(), value.demo(), value.lastActivityAt());
    }

    private record ListingRow(String id, String seller, String source, ItemStack stock, long price,
            long expires, String location, boolean demo, long lastActivity) {
        static final Codec<ListingRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(ListingRow::id),
                Codec.STRING.fieldOf("seller").forGetter(ListingRow::seller),
                Codec.STRING.fieldOf("source").forGetter(ListingRow::source),
                StoredStacks.CODEC.fieldOf("stock").forGetter(ListingRow::stock),
                Codec.LONG.fieldOf("price").forGetter(ListingRow::price),
                Codec.LONG.fieldOf("expires").forGetter(ListingRow::expires),
                Codec.STRING.optionalFieldOf("location", "").forGetter(ListingRow::location),
                // Added later, so optional: an old row is a real listing whose idle clock starts at load.
                Codec.BOOL.optionalFieldOf("demo", false).forGetter(ListingRow::demo),
                Codec.LONG.optionalFieldOf("last_activity", 0L).forGetter(ListingRow::lastActivity))
                .apply(instance, ListingRow::new));
    }
    private record ClaimRow(String player, List<ItemStack> items) {
        static final Codec<ClaimRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(ClaimRow::player),
                StoredStacks.CODEC.listOf().fieldOf("items").forGetter(ClaimRow::items)).apply(instance, ClaimRow::new));
    }
    private record CreditRow(String player, long amount) {
        static final Codec<CreditRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(CreditRow::player),
                Codec.LONG.fieldOf("amount").forGetter(CreditRow::amount)).apply(instance, CreditRow::new));
    }
    /** How a player's {@code credit_claims} total splits by reason; whatever is not listed is a payout. */
    private record ReasonRow(String player, String reason, long amount) {
        static final Codec<ReasonRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(ReasonRow::player),
                Codec.STRING.fieldOf("reason").forGetter(ReasonRow::reason),
                Codec.LONG.fieldOf("amount").forGetter(ReasonRow::amount)).apply(instance, ReasonRow::new));
    }
    private record RequestRow(String player, String id, long at) {
        static final Codec<RequestRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(RequestRow::player),
                Codec.STRING.fieldOf("id").forGetter(RequestRow::id),
                Codec.LONG.fieldOf("at").forGetter(RequestRow::at)).apply(instance, RequestRow::new));
    }
    private record BuyRow(String id, String buyer, String item, int quantity, long price, long escrow, long expires,
            boolean demo) {
        static final Codec<BuyRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(BuyRow::id), Codec.STRING.fieldOf("buyer").forGetter(BuyRow::buyer),
                Codec.STRING.fieldOf("item").forGetter(BuyRow::item), Codec.INT.fieldOf("quantity").forGetter(BuyRow::quantity),
                Codec.LONG.fieldOf("price").forGetter(BuyRow::price), Codec.LONG.fieldOf("escrow").forGetter(BuyRow::escrow),
                Codec.LONG.fieldOf("expires").forGetter(BuyRow::expires),
                Codec.BOOL.optionalFieldOf("demo", false).forGetter(BuyRow::demo)).apply(instance, BuyRow::new));
    }
    private static Codec<MarketSavedData> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                ListingRow.CODEC.listOf().optionalFieldOf("listings", List.of()).forGetter(MarketSavedData::listingRows),
                // Only ever read from a save written before the vault existed, or written as a mirror.
                ClaimRow.CODEC.listOf().optionalFieldOf("item_claims", List.of()).forGetter(MarketSavedData::claimRows),
                CreditRow.CODEC.listOf().optionalFieldOf("credit_claims", List.of()).forGetter(MarketSavedData::creditRows),
                BuyRow.CODEC.listOf().optionalFieldOf("buy_offers", List.of()).forGetter(MarketSavedData::buyRows),
                // Added later, so optional: an old save has neither and loads every claim as a payout.
                ReasonRow.CODEC.listOf().optionalFieldOf("credit_claim_reasons", List.of())
                        .forGetter(MarketSavedData::reasonRows),
                RequestRow.CODEC.listOf().optionalFieldOf("processed_requests", List.of())
                        .forGetter(MarketSavedData::requestRows),
                Codec.BOOL.optionalFieldOf("item_claims_mirror_vault", false)
                        .forGetter(data -> data.vaultMirror != null),
                // Added with the order book, so optional: only set in the one session that mirrors
                // the book into this section's old file.
                Codec.BOOL.optionalFieldOf("board_rows_mirror_book", false)
                        .forGetter(data -> data.bookMirror != null))
                .apply(instance, MarketSavedData::fromRows));
    }
    private List<ListingRow> listingRows() {
        List<MarketListing> rows = new ArrayList<>(listings.values());
        Supplier<LegacyBoardRows> mirror = bookMirror;
        if (mirror != null) rows.addAll(mirror.get().listings());
        return rows.stream().map(value -> new ListingRow(
                value.listingId().toString(), value.seller().toString(), value.source().name(), value.stock().copy(),
                value.baseUnitPrice(), value.expiresAt(), value.location(), value.demo(), value.lastActivityAt()))
                .toList();
    }
    private List<ClaimRow> claimRows() {
        Supplier<Map<UUID, List<ItemStack>>> mirror = vaultMirror;
        Map<UUID, List<ItemStack>> claims = mirror == null ? legacyItemClaims : mirror.get();
        return claims.entrySet().stream().map(entry -> new ClaimRow(
                entry.getKey().toString(), entry.getValue().stream().map(ItemStack::copy).toList())).toList();
    }
    /** One total per player, the shape older builds read; the reason split is stored beside it. */
    private List<CreditRow> creditRows() { return creditClaims.snapshot().keySet().stream().map(player ->
            new CreditRow(player.toString(), creditClaims.total(player))).toList(); }
    private List<ReasonRow> reasonRows() {
        List<ReasonRow> rows = new ArrayList<>();
        creditClaims.snapshot().forEach((player, buckets) -> buckets.forEach((reason, amount) -> {
            if (reason != TransactionType.MARKET_PAYOUT) rows.add(new ReasonRow(player.toString(), reason.name(), amount));
        }));
        return rows;
    }
    private List<RequestRow> requestRows() {
        List<RequestRow> rows = new ArrayList<>();
        requests.snapshot().forEach((player, ids) -> ids.forEach((id, at) ->
                rows.add(new RequestRow(player.toString(), id.toString(), at))));
        return rows;
    }
    private List<BuyRow> buyRows() {
        List<MarketBuyOffer> rows = new ArrayList<>(buyOffers.values());
        Supplier<LegacyBoardRows> mirror = bookMirror;
        if (mirror != null) rows.addAll(mirror.get().buyOffers());
        return rows.stream().map(value -> new BuyRow(value.offerId().toString(),
                value.buyer().toString(), value.itemId().toString(), value.remaining(), value.unitPrice(),
                value.escrow(), value.expiresAt(), value.demo())).toList();
    }
    private static MarketSavedData fromRows(List<ListingRow> listings, List<ClaimRow> claims, List<CreditRow> credits,
            List<BuyRow> buys, List<ReasonRow> reasons, List<RequestRow> requests, boolean claimsMirrorVault,
            boolean boardMirrorsBook) {
        MarketSavedData data = new MarketSavedData();
        data.legacyBoardMirrorsBook = boardMirrorsBook;
        // A row saved before activity was tracked starts its idle clock at load. Without a bound
        // clock it stays unstamped and the first expiry sweep stamps it instead.
        long loadedAt = NeroEconomyCommon.CURRENCY_PROVIDER.clockBound() ? now() : 0L;
        for (ListingRow row : listings) try {
            MarketListing value = new MarketListing(UUID.fromString(row.id()), UUID.fromString(row.seller()),
                    ListingSource.valueOf(row.source()), row.stock(), row.price(), row.expires(), row.location(),
                    row.demo(), row.lastActivity() > 0L ? row.lastActivity() : loadedAt);
            data.listings.put(value.listingId(), value);
        } catch (IllegalArgumentException ignored) { }
        // Held only until the store delivers them to the vaults (or drops them, for a mirror).
        for (ClaimRow row : claims) try {
            data.legacyItemClaims.put(UUID.fromString(row.player()), new ArrayList<>(row.items().stream()
                    .filter(item -> !item.isEmpty()).map(ItemStack::copy).toList()));
        } catch (IllegalArgumentException ignored) { }
        data.legacyClaimsMirrorVault = claimsMirrorVault;
        // The per-player total is authoritative. Reason rows only label parts of it, so a missing,
        // unknown or oversized reason can never create or lose credits: the rest loads as a payout.
        Map<UUID, Long> unlabelled = new LinkedHashMap<>();
        for (CreditRow row : credits) try {
            if (row.amount() > 0L) unlabelled.put(UUID.fromString(row.player()), row.amount());
        } catch (IllegalArgumentException ignored) { }
        for (ReasonRow row : reasons) try {
            UUID player = UUID.fromString(row.player());
            long labelled = Math.min(unlabelled.getOrDefault(player, 0L), row.amount());
            if (labelled <= 0L) continue;
            TransactionType reason = TransactionType.valueOf(row.reason());
            // A claim is collected as a transfer, which a minting or burning reason can never be.
            if (reason.mints() || reason.burns()) continue;
            data.creditClaims.add(player, reason, labelled);
            unlabelled.merge(player, -labelled, Long::sum);
        } catch (IllegalArgumentException ignored) { }
        unlabelled.forEach((player, amount) -> data.creditClaims.add(player, TransactionType.MARKET_PAYOUT, amount));
        for (RequestRow row : requests) try {
            data.requests.restore(UUID.fromString(row.player()), UUID.fromString(row.id()), row.at());
        } catch (IllegalArgumentException ignored) { }
        // Ids are stamped in economy time, so they are only aged here when that clock is available.
        // Otherwise the sweep that runs as the server binds drops the stale ones a moment later.
        if (NeroEconomyCommon.CURRENCY_PROVIDER.clockBound()) data.requests.expire(now());
        for (BuyRow row : buys) try {
            MarketBuyOffer offer = new MarketBuyOffer(UUID.fromString(row.id()), UUID.fromString(row.buyer()),
                    Identifier.parse(row.item()), row.quantity(), row.price(), row.escrow(), row.expires(), row.demo());
            // A demo offer holds no escrow by design; a real one without escrow is unpayable and dropped.
            if (offer.remaining() > 0 && offer.unitPrice() > 0 && (offer.demo() ? offer.escrow() == 0L : offer.escrow() > 0L))
                data.buyOffers.put(offer.offerId(), offer);
        } catch (IllegalArgumentException ignored) { }
        return data;
    }
}
