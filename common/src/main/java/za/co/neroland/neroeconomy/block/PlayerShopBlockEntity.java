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

import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;

/** One-offer shop whose stock lives in the global escrow store, so unloaded shops keep selling. */
public final class PlayerShopBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    public static final int OFFER_SLOT = 0;
    private static final int[] NO_AUTOMATION_SLOTS = new int[0];
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
    public int stockCount() { return getItem(OFFER_SLOT).getCount(); }

    public void setPrice(long nextPrice) {
        UUID owner = owner();
        if (owner == null || nextPrice < 1L || level == null) return;
        ItemStack stock = getItem(OFFER_SLOT);
        price = nextPrice;
        MarketSavedData.get(level.getServer()).replaceShopStock(listingId, owner, stock, price, location());
        setChanged();
    }

    public PurchaseResult purchase(ServerPlayer buyer, long quotedPrice, UUID requestId) {
        if (buyer == null || level == null) return PurchaseResult.INTERNAL_ERROR;
        long tick = level.getGameTime();
        Long previous = lastPurchaseTick.put(buyer.getUUID(), tick);
        if (previous != null && previous == tick) return PurchaseResult.STALE_REQUEST;
        return MarketSavedData.get(level.getServer()).purchase(buyer, listingId, 1, quotedPrice, requestId);
    }

    public void cancel() {
        UUID owner = owner();
        if (owner != null && level != null) MarketSavedData.get(level.getServer()).cancel(listingId, owner);
    }

    private String location() {
        return level == null ? "" : level.dimension().identifier() + "|" + worldPosition.asLong();
    }

    @Override public Component getDisplayName() { return Component.translatable("container.neroeconomy.player_shop"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new PlayerShopMenu(id, inventory, this, player);
    }
    @Override public int getContainerSize() { return 9; }
    @Override public boolean isEmpty() { return getItem(OFFER_SLOT).isEmpty(); }
    @Override public ItemStack getItem(int slot) {
        if (slot != OFFER_SLOT || level == null) return ItemStack.EMPTY;
        MarketListing listing = MarketSavedData.get(level.getServer()).listing(listingId);
        return listing == null ? ItemStack.EMPTY : listing.stock().copy();
    }
    @Override public ItemStack removeItem(int slot, int amount) {
        if (slot != OFFER_SLOT || amount <= 0 || level == null) return ItemStack.EMPTY;
        UUID owner = owner();
        ItemStack current = getItem(slot);
        if (owner == null || current.isEmpty()) return ItemStack.EMPTY;
        int removed = Math.min(amount, current.getCount());
        ItemStack result = current.copyWithCount(removed);
        ItemStack remainder = current.getCount() == removed ? ItemStack.EMPTY
                : current.copyWithCount(current.getCount() - removed);
        MarketSavedData.get(level.getServer()).replaceShopStock(listingId, owner, remainder, price, location());
        setChanged();
        return result;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, Integer.MAX_VALUE); }
    @Override public void setItem(int slot, ItemStack stack) {
        if (slot != OFFER_SLOT || level == null) return;
        UUID owner = owner();
        if (owner == null) return;
        ItemStack safe = stack.copy();
        if (safe.getCount() > safe.getMaxStackSize()) safe.setCount(safe.getMaxStackSize());
        MarketSavedData.get(level.getServer()).replaceShopStock(listingId, owner, safe, price, location());
        setChanged();
    }
    @Override public boolean stillValid(Player player) {
        return !isRemoved() && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) <= 64.0D;
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot == OFFER_SLOT; }
    @Override public void clearContent() { setItem(OFFER_SLOT, ItemStack.EMPTY); }
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
    }
}
