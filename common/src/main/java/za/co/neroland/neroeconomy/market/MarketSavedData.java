package za.co.neroland.neroeconomy.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolandcore.data.SavedDataRecovery;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PriceQuote;
import za.co.neroland.neroeconomy.api.PricingEngine;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.nerolandcore.link.LinkAlert;

/** Authoritative listing, goods-claim, proceeds, and request-idempotency store. */
public final class MarketSavedData extends SavedData {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market");
    public static final SavedDataType<MarketSavedData> TYPE =
            new SavedDataType<>(ID, MarketSavedData::new, codec(), null);
    private static final int MAX_REQUEST_IDS_PER_PLAYER = 128;

    private final Map<UUID, MarketListing> listings = new LinkedHashMap<>();
    private final Map<UUID, MarketBuyOffer> buyOffers = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> itemClaims = new LinkedHashMap<>();
    private final Map<UUID, Long> creditClaims = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> processedRequests = new HashMap<>();

    public MarketSavedData() { }
    public static MarketSavedData get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, MarketSavedData::new, ID.toString());
    }

    public synchronized MarketListing postFromHand(ServerPlayer seller, int quantity, long price,
            long expiresAt, ListingSource source, String location, UUID requestId) {
        if (seller == null || quantity < 1 || price < 1L || expiresAt <= System.currentTimeMillis()
                || source == null || location == null || requestId == null
                || !rememberRequest(seller.getUUID(), requestId)) return null;
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty() || held.getCount() < quantity) return null;
        ItemStack escrow = held.copyWithCount(quantity);
        UUID id = freshId();
        MarketListing listing = new MarketListing(id, seller.getUUID(), source, escrow, price, expiresAt, location);
        listings.put(id, listing);
        held.shrink(quantity);
        setDirty();
        return listing;
    }

    public synchronized MarketBuyOffer postBuyOffer(ServerPlayer buyer, Identifier itemId, int quantity,
            long unitPrice, long expiresAt, UUID requestId) {
        if (buyer == null || itemId == null || quantity < 1 || unitPrice < 1 || expiresAt <= System.currentTimeMillis()
                || requestId == null || !rememberRequest(buyer.getUUID(), requestId)) return null;
        long escrow;
        try { escrow = EscrowLifecycle.create(quantity, unitPrice); }
        catch (ArithmeticException overflow) { return null; }
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(buyer.getUUID(), NeroEconomyCommon.CREDITS,
                escrow, TransactionType.MARKET_ESCROW)) return null;
        UUID id = freshId();
        MarketBuyOffer offer = new MarketBuyOffer(id, buyer.getUUID(), itemId, quantity, unitPrice, escrow, expiresAt);
        buyOffers.put(id, offer); setDirty(); return offer;
    }

    public synchronized boolean fillBuyOffer(ServerPlayer seller, UUID offerId, int quantity, UUID requestId) {
        if (seller == null || offerId == null || quantity < 1 || requestId == null
                || !rememberRequest(seller.getUUID(), requestId)) return false;
        MarketBuyOffer offer = buyOffers.get(offerId);
        if (offer == null || offer.buyer().equals(seller.getUUID()) || offer.expiresAt() <= System.currentTimeMillis()
                || quantity > offer.remaining()) return false;
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty() || held.getCount() < quantity
                || !BuiltInRegistries.ITEM.getKey(held.getItem()).equals(offer.itemId())) return false;
        long proceeds;
        try { proceeds = Math.multiplyExact(offer.unitPrice(), quantity); }
        catch (ArithmeticException overflow) { return false; }
        if (proceeds > offer.escrow()) return false;
        ItemStack goods = held.copyWithCount(quantity);
        long current = creditClaims.getOrDefault(seller.getUUID(), 0L);
        if (Long.MAX_VALUE - current < proceeds) return false;
        held.shrink(quantity);
        itemClaims.computeIfAbsent(offer.buyer(), ignored -> new ArrayList<>()).add(goods);
        creditClaims.put(seller.getUUID(), current + proceeds);
        int remaining = offer.remaining() - quantity;
        long escrow = EscrowLifecycle.fill(offer.escrow(), quantity, offer.unitPrice());
        if (remaining == 0) buyOffers.remove(offerId);
        else buyOffers.put(offerId, new MarketBuyOffer(offer.offerId(), offer.buyer(), offer.itemId(), remaining,
                offer.unitPrice(), escrow, offer.expiresAt()));
        setDirty(); return true;
    }

    public synchronized List<MarketBuyOffer> searchBuyOffers(String query, int page, int pageSize, long now) {
        expire(now); String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        int size = Math.max(1, Math.min(50, pageSize));
        return buyOffers.values().stream().filter(value -> needle.isEmpty()
                || value.itemId().toString().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(value -> value.itemId().toString()))
                .skip((long) Math.max(0, page) * size).limit(size).toList();
    }

    public synchronized int searchBuyOfferCount(String query, long now) {
        expire(now); String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        return (int) buyOffers.values().stream().filter(value -> needle.isEmpty()
                || value.itemId().toString().toLowerCase(Locale.ROOT).contains(needle)).count();
    }

    /** Registers or atomically replaces a shop's central escrow. Caller owns the returned old stock. */
    public synchronized ItemStack replaceShopStock(UUID listingId, UUID owner, ItemStack next, long price,
            String location) {
        if (listingId == null || owner == null || next == null || price < 1L || location == null)
            return ItemStack.EMPTY;
        MarketListing old = listings.remove(listingId);
        if (!next.isEmpty()) listings.put(listingId, new MarketListing(listingId, owner, ListingSource.PLAYER_SHOP,
                next.copy(), price, Long.MAX_VALUE, location));
        setDirty();
        return old == null ? ItemStack.EMPTY : old.stock().copy();
    }

    public synchronized MarketListing listing(UUID id) {
        MarketListing value = listings.get(id);
        return value == null ? null : copy(value);
    }

    public synchronized List<MarketListing> search(String query, int page, int pageSize, long now) {
        expire(now);
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        int safeSize = Math.max(1, Math.min(50, pageSize));
        int skip = Math.max(0, page) * safeSize;
        return listings.values().stream().filter(row -> row.availableQuantity() > 0)
                .filter(row -> needle.isEmpty() || row.itemId().toString().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(row -> row.itemId().toString()))
                .skip(skip).limit(safeSize).map(MarketSavedData::copy).toList();
    }

    public synchronized int searchCount(String query, long now) {
        expire(now);
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        return (int) listings.values().stream().filter(row -> row.availableQuantity() > 0)
                .filter(row -> needle.isEmpty() || row.itemId().toString().toLowerCase(Locale.ROOT).contains(needle))
                .count();
    }

    /**
     * Re-prices and settles exactly once. Goods first move to a durable claim; immediate inventory
     * collection is an optimization, so a full/disconnecting buyer can never lose purchased goods.
     */
    public synchronized PurchaseResult purchase(ServerPlayer buyer, UUID listingId, int quantity,
            long quotedUnitPrice, UUID requestId) {
        if (buyer == null || listingId == null || requestId == null || quantity < 1)
            return PurchaseResult.INVALID_QUANTITY;
        if (!rememberRequest(buyer.getUUID(), requestId)) return PurchaseResult.STALE_REQUEST;
        MarketListing listing = listings.get(listingId);
        if (listing == null || listing.expiresAt() <= System.currentTimeMillis()) return PurchaseResult.NOT_FOUND;
        if (listing.seller().equals(buyer.getUUID())) return PurchaseResult.OWN_LISTING;
        if (listing.availableQuantity() < quantity) return PurchaseResult.OUT_OF_STOCK;
        PriceQuote execution = PricingEngine.quote(listing, buyer.getUUID());
        if (!PricingEngine.executionPriceAccepted(quotedUnitPrice, execution.unitPrice())) return PurchaseResult.PRICE_MOVED;
        long total;
        try { total = execution.total(quantity); }
        catch (ArithmeticException overflow) { return PurchaseResult.INVALID_QUANTITY; }
        ItemStack goods = listing.stock().copyWithCount(quantity);
        if (!canFit(buyer, goods)) return PurchaseResult.INVENTORY_FULL;
        if (listing.source() == ListingSource.MARKET_BOARD) {
            long claim = creditClaims.getOrDefault(listing.seller(), 0L);
            if (Long.MAX_VALUE - claim < total) return PurchaseResult.INTERNAL_ERROR;
            if (!NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(buyer.getUUID(), NeroEconomyCommon.CREDITS,
                    total, TransactionType.MARKET_PURCHASE)) return PurchaseResult.INSUFFICIENT_FUNDS;
            creditClaims.put(listing.seller(), claim + total);
        } else if (!NeroEconomyCommon.CURRENCY_PROVIDER.transferTyped(buyer.getUUID(), listing.seller(),
                NeroEconomyCommon.CREDITS, total, TransactionType.MARKET_PURCHASE)) {
            return PurchaseResult.INSUFFICIENT_FUNDS;
        }
        // Nothing below can reject: the durable claim is the delivery commit point.
        itemClaims.computeIfAbsent(buyer.getUUID(), ignored -> new ArrayList<>()).add(goods.copy());
        int remaining = listing.availableQuantity() - quantity;
        if (remaining == 0) listings.remove(listingId);
        else listings.put(listingId, listing.withStock(listing.stock().copyWithCount(remaining)));
        setDirty();
        if (remaining == 0 && listing.source() == ListingSource.PLAYER_SHOP)
            EconomyAlerts.raise(buyer.level().getServer(), listing.seller(), "shop_sold_out",
                    "A player shop sold out.", LinkAlert.Severity.INFO);
        collectItems(buyer);
        return PurchaseResult.SUCCESS;
    }

    public synchronized int collectItems(ServerPlayer player) {
        List<ItemStack> claims = itemClaims.get(player.getUUID());
        if (claims == null) return 0;
        int delivered = 0;
        for (var iterator = claims.iterator(); iterator.hasNext();) {
            ItemStack claim = iterator.next();
            ItemStack moving = claim.copy();
            if (player.getInventory().add(moving) && moving.isEmpty()) {
                delivered += claim.getCount();
                iterator.remove();
            }
        }
        if (claims.isEmpty()) itemClaims.remove(player.getUUID());
        if (delivered > 0) setDirty();
        return delivered;
    }

    public synchronized boolean collectCredits(UUID player) {
        Long value = creditClaims.get(player);
        if (value == null || value <= 0L) return false;
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(player, NeroEconomyCommon.CREDITS,
                value, TransactionType.ESCROW_REFUND)) return false;
        creditClaims.remove(player);
        setDirty();
        return true;
    }

    public synchronized void addCreditClaim(UUID player, long amount) {
        if (player == null || amount <= 0L) return;
        long current = creditClaims.getOrDefault(player, 0L);
        creditClaims.put(player, Long.MAX_VALUE - current < amount ? Long.MAX_VALUE : current + amount);
        setDirty();
    }

    public synchronized long creditClaim(UUID player) { return creditClaims.getOrDefault(player, 0L); }
    public synchronized List<ItemStack> itemClaims(UUID player) {
        List<ItemStack> values = itemClaims.get(player);
        return values == null ? List.of() : values.stream().map(ItemStack::copy).toList();
    }

    public synchronized void addItemClaim(UUID player, ItemStack item) {
        if (player == null || item == null || item.isEmpty()) return;
        itemClaims.computeIfAbsent(player, ignored -> new ArrayList<>()).add(item.copy());
        setDirty();
    }

    public synchronized void cancel(UUID listingId, UUID actor) {
        MarketListing listing = listings.get(listingId);
        if (listing == null || actor == null || !listing.seller().equals(actor)) return;
        listings.remove(listingId);
        itemClaims.computeIfAbsent(actor, ignored -> new ArrayList<>()).add(listing.stock().copy());
        setDirty();
    }

    public synchronized void expire(long now) {
        boolean changed = false;
        for (var iterator = listings.entrySet().iterator(); iterator.hasNext();) {
            MarketListing listing = iterator.next().getValue();
            if (listing.expiresAt() <= now) {
                itemClaims.computeIfAbsent(listing.seller(), ignored -> new ArrayList<>()).add(listing.stock().copy());
                iterator.remove();
                changed = true;
            }
        }
        for (var iterator = buyOffers.entrySet().iterator(); iterator.hasNext();) {
            MarketBuyOffer offer = iterator.next().getValue();
            if (offer.expiresAt() <= now) {
                addCreditClaim(offer.buyer(), EscrowLifecycle.expire(offer.escrow()));
                iterator.remove(); changed = true;
            }
        }
        if (changed) setDirty();
    }

    public synchronized void forgetPlayer(UUID player) {
        if (player == null) return;
        boolean changed = listings.entrySet().removeIf(entry -> entry.getValue().seller().equals(player));
        changed |= buyOffers.entrySet().removeIf(entry -> entry.getValue().buyer().equals(player));
        changed |= itemClaims.remove(player) != null;
        changed |= creditClaims.remove(player) != null;
        processedRequests.remove(player);
        if (changed) setDirty();
    }

    private boolean rememberRequest(UUID player, UUID request) {
        LinkedHashSet<UUID> values = processedRequests.computeIfAbsent(player, ignored -> new LinkedHashSet<>());
        if (!values.add(request)) return false;
        while (values.size() > MAX_REQUEST_IDS_PER_PLAYER) values.remove(values.iterator().next());
        return true;
    }

    private UUID freshId() { UUID id; do id = UUID.randomUUID(); while (listings.containsKey(id) || buyOffers.containsKey(id)); return id; }
    private static MarketListing copy(MarketListing value) {
        return new MarketListing(value.listingId(), value.seller(), value.source(), value.stock(),
                value.baseUnitPrice(), value.expiresAt(), value.location());
    }

    private static boolean canFit(ServerPlayer player, ItemStack goods) {
        int remaining = goods.getCount();
        for (ItemStack slot : player.getInventory().getNonEquipmentItems()) {
            if (slot.isEmpty()) remaining -= goods.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(slot, goods)) remaining -= slot.getMaxStackSize() - slot.getCount();
            if (remaining <= 0) return true;
        }
        return false;
    }

    private record ListingRow(String id, String seller, String source, ItemStack stock, long price,
            long expires, String location) {
        static final Codec<ListingRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(ListingRow::id),
                Codec.STRING.fieldOf("seller").forGetter(ListingRow::seller),
                Codec.STRING.fieldOf("source").forGetter(ListingRow::source),
                ItemStack.CODEC.fieldOf("stock").forGetter(ListingRow::stock),
                Codec.LONG.fieldOf("price").forGetter(ListingRow::price),
                Codec.LONG.fieldOf("expires").forGetter(ListingRow::expires),
                Codec.STRING.optionalFieldOf("location", "").forGetter(ListingRow::location))
                .apply(instance, ListingRow::new));
    }
    private record ClaimRow(String player, List<ItemStack> items) {
        static final Codec<ClaimRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(ClaimRow::player),
                ItemStack.CODEC.listOf().fieldOf("items").forGetter(ClaimRow::items)).apply(instance, ClaimRow::new));
    }
    private record CreditRow(String player, long amount) {
        static final Codec<CreditRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(CreditRow::player),
                Codec.LONG.fieldOf("amount").forGetter(CreditRow::amount)).apply(instance, CreditRow::new));
    }
    private record BuyRow(String id, String buyer, String item, int quantity, long price, long escrow, long expires) {
        static final Codec<BuyRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(BuyRow::id), Codec.STRING.fieldOf("buyer").forGetter(BuyRow::buyer),
                Codec.STRING.fieldOf("item").forGetter(BuyRow::item), Codec.INT.fieldOf("quantity").forGetter(BuyRow::quantity),
                Codec.LONG.fieldOf("price").forGetter(BuyRow::price), Codec.LONG.fieldOf("escrow").forGetter(BuyRow::escrow),
                Codec.LONG.fieldOf("expires").forGetter(BuyRow::expires)).apply(instance, BuyRow::new));
    }
    private static Codec<MarketSavedData> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                ListingRow.CODEC.listOf().optionalFieldOf("listings", List.of()).forGetter(MarketSavedData::listingRows),
                ClaimRow.CODEC.listOf().optionalFieldOf("item_claims", List.of()).forGetter(MarketSavedData::claimRows),
                CreditRow.CODEC.listOf().optionalFieldOf("credit_claims", List.of()).forGetter(MarketSavedData::creditRows),
                BuyRow.CODEC.listOf().optionalFieldOf("buy_offers", List.of()).forGetter(MarketSavedData::buyRows))
                .apply(instance, MarketSavedData::fromRows));
    }
    private List<ListingRow> listingRows() { return listings.values().stream().map(value -> new ListingRow(
            value.listingId().toString(), value.seller().toString(), value.source().name(), value.stock().copy(),
            value.baseUnitPrice(), value.expiresAt(), value.location())).toList(); }
    private List<ClaimRow> claimRows() { return itemClaims.entrySet().stream().map(entry -> new ClaimRow(
            entry.getKey().toString(), entry.getValue().stream().map(ItemStack::copy).toList())).toList(); }
    private List<CreditRow> creditRows() { return creditClaims.entrySet().stream().map(entry ->
            new CreditRow(entry.getKey().toString(), entry.getValue())).toList(); }
    private List<BuyRow> buyRows() { return buyOffers.values().stream().map(value -> new BuyRow(value.offerId().toString(),
            value.buyer().toString(), value.itemId().toString(), value.remaining(), value.unitPrice(), value.escrow(),
            value.expiresAt())).toList(); }
    private static MarketSavedData fromRows(List<ListingRow> listings, List<ClaimRow> claims, List<CreditRow> credits,
            List<BuyRow> buys) {
        MarketSavedData data = new MarketSavedData();
        for (ListingRow row : listings) try {
            MarketListing value = new MarketListing(UUID.fromString(row.id()), UUID.fromString(row.seller()),
                    ListingSource.valueOf(row.source()), row.stock(), row.price(), row.expires(), row.location());
            data.listings.put(value.listingId(), value);
        } catch (IllegalArgumentException ignored) { }
        for (ClaimRow row : claims) try {
            data.itemClaims.put(UUID.fromString(row.player()), row.items().stream().filter(item -> !item.isEmpty())
                    .map(ItemStack::copy).toList());
        } catch (IllegalArgumentException ignored) { }
        for (CreditRow row : credits) try {
            if (row.amount() > 0L) data.creditClaims.put(UUID.fromString(row.player()), row.amount());
        } catch (IllegalArgumentException ignored) { }
        for (BuyRow row : buys) try {
            MarketBuyOffer offer = new MarketBuyOffer(UUID.fromString(row.id()), UUID.fromString(row.buyer()),
                    Identifier.parse(row.item()), row.quantity(), row.price(), row.escrow(), row.expires());
            if (offer.remaining() > 0 && offer.unitPrice() > 0 && offer.escrow() > 0) data.buyOffers.put(offer.offerId(), offer);
        } catch (IllegalArgumentException ignored) { }
        return data;
    }
}
