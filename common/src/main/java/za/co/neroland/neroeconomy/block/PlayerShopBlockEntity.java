package za.co.neroland.neroeconomy.block;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.neroeconomy.api.PricingEngine;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;

/**
 * One-offer shop whose stock lives in the global escrow store, so unloaded shops keep selling.
 *
 * <p><b>Offer-slot invariant.</b> Vanilla menu code ({@code AbstractContainerMenu#doClick},
 * {@code Slot#safeInsert}, {@code Slot#tryRemove}) mutates the {@link ItemStack} returned by
 * {@code Slot#getItem()} <em>in place</em> and only afterwards calls {@code Slot#setChanged()}. The
 * offer slot must therefore expose a live stack, never a detached copy, or every such mutation would
 * land on a throwaway while the escrow stayed untouched. {@link #offer} is that live one-slot mirror:
 * <ul>
 *   <li>the market store stays authoritative — the mirror is re-read from it before every container
 *       interaction and on every menu sync (see {@code PlayerShopMenu#clicked} and
 *       {@code PlayerShopMenu#broadcastChanges}), so on the single-threaded server side no sale can
 *       settle between a read and its write-back;</li>
 *   <li>every mutating path writes the mirror straight back through
 *       {@link MarketSavedData#replaceShopStock}, so slot and escrow can never disagree once an
 *       interaction ends.</li>
 * </ul>
 *
 * <p>The container is exactly <b>one</b> slot wide. Vanilla clears the carried stack before calling
 * {@code setItem}, so any extra slot advertised by {@link #getContainerSize()} but ignored by
 * {@link #setItem} would silently destroy whatever was placed into it.
 */
public final class PlayerShopBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    public static final int OFFER_SLOT = 0;
    private static final int CONTAINER_SIZE = 1;
    private static final int[] NO_AUTOMATION_SLOTS = new int[0];
    /** A buyer is remembered only long enough to reject a double click; entries then expire. */
    private static final long PURCHASE_MEMORY_TICKS = 100L;
    private static final int MAX_REMEMBERED_BUYERS = 64;

    private final SimpleContainer offer = new SimpleContainer(CONTAINER_SIZE);
    private UUID listingId = UUID.randomUUID();
    private long price = 1L;
    private final Map<UUID, Long> lastPurchaseTick = new HashMap<>();

    public PlayerShopBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.PLAYER_SHOP.get(), pos, state); }

    public void claim(UUID player) {
        if (level instanceof ServerLevel serverLevel)
            ShopOwnershipState.get(serverLevel.getServer()).claim(serverLevel, worldPosition, player);
    }
    private UUID owner() {
        return level instanceof ServerLevel serverLevel
                ? ShopOwnershipState.get(serverLevel.getServer()).owner(serverLevel, worldPosition) : null;
    }
    public boolean isOwner(UUID player) { UUID current = owner(); return current != null && current.equals(player); }
    public UUID listingId() { return listingId; }
    public long price() { return price; }
    /** Live escrow size, read from the authoritative store rather than the menu mirror. */
    public int stockCount() { return storedStock().getCount(); }

    /** The price a buyer would actually be charged right now; the store re-quotes the same way. */
    public long quotedUnitPrice(UUID buyer) {
        MarketListing listing = listing();
        if (listing == null || buyer == null) return price;
        try { return PricingEngine.quote(listing, buyer).unitPrice(); }
        catch (IllegalArgumentException invalid) { return price; }
    }

    public void setPrice(long nextPrice) {
        if (owner() == null || nextPrice < 1L || level == null) return;
        syncOffer();
        price = nextPrice;
        setChanged();
    }

    public PurchaseResult purchase(ServerPlayer buyer, long quotedPrice, UUID requestId) {
        if (buyer == null || level == null || requestId == null) return PurchaseResult.INTERNAL_ERROR;
        long tick = level.getGameTime();
        forgetStalePurchases(tick);
        Long previous = lastPurchaseTick.put(buyer.getUUID(), tick);
        if (previous != null && previous == tick) return PurchaseResult.STALE_REQUEST;
        PurchaseResult result = MarketSavedData.get(level.getServer()).purchase(buyer, listingId, 1, quotedPrice, requestId);
        syncOffer();
        return result;
    }

    public void cancel() {
        UUID owner = owner();
        if (owner != null && level != null) MarketSavedData.get(level.getServer()).cancel(listingId, owner);
        offer.setItem(OFFER_SLOT, ItemStack.EMPTY);
    }

    /** Re-reads the authoritative escrow into the menu mirror. Never call this mid-interaction. */
    public void syncOffer() {
        if (level == null || level.isClientSide()) return;
        ItemStack stored = storedStock();
        if (ItemStack.matches(stored, offer.getItem(OFFER_SLOT))) return;
        offer.setItem(OFFER_SLOT, stored);
    }

    private MarketListing listing() {
        return level == null || level.isClientSide() ? null : MarketSavedData.get(level.getServer()).listing(listingId);
    }
    private ItemStack storedStock() {
        MarketListing listing = listing();
        return listing == null ? ItemStack.EMPTY : listing.stock().copy();
    }

    /** Publishes the mirror back into the escrow store; idempotent, so callers may over-publish. */
    private void publish() {
        UUID owner = owner();
        if (owner == null || level == null || level.isClientSide() || isRemoved()) return;
        ItemStack stock = offer.getItem(OFFER_SLOT);
        MarketSavedData store = MarketSavedData.get(level.getServer());
        if (stock.isEmpty() && store.listing(listingId) == null) return;
        if (stock.getCount() > stock.getMaxStackSize()) stock.setCount(stock.getMaxStackSize());
        // replaceShopStock hands the displaced escrow back to us. The mirror was synced from that
        // same escrow at the start of this interaction and vanilla has already routed anything it
        // took out of the slot to the player, so the displaced stack is a spent duplicate here;
        // claiming it would mint items. Loss is instead prevented by never publishing a stale mirror.
        store.replaceShopStock(listingId, owner, stock, price, location());
    }

    private void forgetStalePurchases(long tick) {
        lastPurchaseTick.values().removeIf(value -> tick - value > PURCHASE_MEMORY_TICKS || value > tick);
        if (lastPurchaseTick.size() > MAX_REMEMBERED_BUYERS) lastPurchaseTick.clear();
    }

    private String location() {
        return level == null ? "" : level.dimension().identifier() + "|" + worldPosition.asLong();
    }

    @Override public Component getDisplayName() { return Component.translatable("container.neroeconomy.player_shop"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        syncOffer();
        return new PlayerShopMenu(id, inventory, this, player);
    }
    @Override public int getContainerSize() { return CONTAINER_SIZE; }
    @Override public boolean isEmpty() { return offer.getItem(OFFER_SLOT).isEmpty(); }
    @Override public ItemStack getItem(int slot) {
        return slot == OFFER_SLOT ? offer.getItem(OFFER_SLOT) : ItemStack.EMPTY;
    }
    @Override public ItemStack removeItem(int slot, int amount) {
        if (slot != OFFER_SLOT || amount <= 0 || owner() == null) return ItemStack.EMPTY;
        ItemStack removed = offer.removeItem(OFFER_SLOT, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, Integer.MAX_VALUE); }
    @Override public void setItem(int slot, ItemStack stack) {
        UUID owner = owner();
        if (slot != OFFER_SLOT || owner == null) return;
        // Defensive only: vanilla never inserts past Slot#getMaxStackSize, and any excess is claimed
        // rather than trimmed away, so an oversized insert can never delete items.
        if (stack.getCount() > stack.getMaxStackSize() && level != null && !level.isClientSide()) {
            ItemStack excess = stack.copyWithCount(stack.getCount() - stack.getMaxStackSize());
            stack.setCount(stack.getMaxStackSize());
            MarketSavedData.get(level.getServer()).addItemClaim(owner, excess);
        }
        offer.setItem(OFFER_SLOT, stack);
        setChanged();
    }
    @Override public void setChanged() {
        super.setChanged();
        publish();
    }
    @Override public boolean stillValid(Player player) {
        return !isRemoved() && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) <= 64.0D;
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot == OFFER_SLOT; }
    @Override public void clearContent() {
        UUID owner = owner();
        ItemStack cleared = offer.removeItemNoUpdate(OFFER_SLOT);
        // Nothing here hands the cleared stock to a player, so it becomes an owner claim.
        if (owner != null && level != null && !level.isClientSide() && !cleared.isEmpty())
            MarketSavedData.get(level.getServer()).addItemClaim(owner, cleared);
        setChanged();
    }
    @Override public int[] getSlotsForFace(Direction side) { return NO_AUTOMATION_SLOTS; }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) { return false; }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) { return false; }

    @Override public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        cancel();
        super.preRemoveSideEffects(pos, state);
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("ListingId", listingId.toString());
        output.putLong("Price", price);
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        try { listingId = UUID.fromString(input.getStringOr("ListingId", listingId.toString())); }
        catch (IllegalArgumentException ignored) { listingId = UUID.randomUUID(); }
        price = Math.max(1L, input.getLongOr("Price", 1L));
        offer.setItem(OFFER_SLOT, ItemStack.EMPTY);
    }
}
