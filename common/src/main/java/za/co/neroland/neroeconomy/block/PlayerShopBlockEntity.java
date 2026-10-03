package za.co.neroland.neroeconomy.block;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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

import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PurchaseOutcome;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.vault.VaultStacks;

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
 *       interaction ends;</li>
 *   <li>a mirror is only ever written back while it is known to describe the escrow: one that has
 *       not been read since the block entity loaded, or that the escrow has moved under (a sale from
 *       a terminal, an expiry), is re-read instead of published, so it can neither delete a listing
 *       nor bring sold stock back.</li>
 * </ul>
 *
 * <p>A <b>demo</b> shop is one the admin gallery placed. Its listing is flagged, can never be
 * bought, and its stock is destroyed rather than claimed when the shop is emptied or removed.
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
    private boolean demo;
    /** The escrow as the mirror last agreed with it; null until the mirror has been read once. */
    private ItemStack agreed;
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
    public boolean hasOwner() { return owner() != null; }
    /** Whether this player may stock and price the shop. Nobody manages a demo shop, its builder included. */
    public boolean canManage(UUID player) { return !demo && isOwner(player); }
    public boolean isDemo() { return demo; }
    /** Marks a gallery shop; set once, straight after placement and before any stock is added. */
    public void markDemo() { demo = true; super.setChanged(); }
    public UUID listingId() { return listingId; }
    public long price() { return price; }
    /** Live escrow size, read from the authoritative store rather than the menu mirror. */
    public int stockCount() { return storedStock().getCount(); }

    /** The price a buyer would actually be charged right now; the store re-quotes the same way. */
    public long quotedUnitPrice(UUID buyer) {
        MarketListing listing = listing();
        if (listing == null || buyer == null) return price;
        try { return MarketSavedData.buyerUnitPrice(listing, buyer); }
        catch (IllegalArgumentException invalid) { return price; }
    }

    public void setPrice(long nextPrice) {
        if (owner() == null || nextPrice < 1L || level == null) return;
        syncOffer();
        price = nextPrice;
        setChanged();
    }

    /** @param expectedItem the item the buyer was shown; the purchase is refused if the shop now sells another */
    public PurchaseResult purchase(ServerPlayer buyer, long quotedPrice, UUID requestId, Identifier expectedItem) {
        return purchaseOutcome(buyer, quotedPrice, requestId, expectedItem).result();
    }

    /** {@link #purchase} with the amounts: what the buyer paid, the fee, and what the owner receives. */
    public PurchaseOutcome purchaseOutcome(ServerPlayer buyer, long quotedPrice, UUID requestId,
            Identifier expectedItem) {
        if (buyer == null || level == null || requestId == null) return refused(PurchaseResult.INTERNAL_ERROR);
        long tick = level.getGameTime();
        forgetStalePurchases(tick);
        Long previous = lastPurchaseTick.put(buyer.getUUID(), tick);
        if (previous != null && previous == tick) return refused(PurchaseResult.STALE_REQUEST);
        PurchaseOutcome outcome = MarketSavedData.get(level.getServer()).purchaseOutcome(buyer, listingId, 1,
                quotedPrice, requestId, expectedItem);
        syncOffer();
        return outcome;
    }

    private static PurchaseOutcome refused(PurchaseResult result) {
        return new PurchaseOutcome(result, 0L, 0L, 0L, 0L, false, false);
    }

    /**
     * Tears the listing down: the escrowed stock goes to the listed owner's vault, or is
     * destroyed for a demo shop. Works without an owner row, so an ownerless shop can still be
     * removed cleanly.
     */
    public void cancel() {
        if (level != null && !level.isClientSide()) {
            MarketSavedData.get(level.getServer()).releaseShop(listingId);
            agreed = ItemStack.EMPTY;
        }
        offer.setItem(OFFER_SLOT, ItemStack.EMPTY);
    }

    /** Re-reads the authoritative escrow into the menu mirror. Never call this mid-interaction. */
    public void syncOffer() {
        if (level == null || level.isClientSide()) return;
        ItemStack stored = storedStock();
        agreed = stored.copy();
        if (ItemStack.matches(stored, offer.getItem(OFFER_SLOT))) return;
        offer.setItem(OFFER_SLOT, stored);
    }

    /** An owner opening the shop is an interaction: it restarts the listing's idle-expiry clock. */
    private void touch(Player player) {
        if (level == null || level.isClientSide() || !isOwner(player.getUUID())) return;
        MarketSavedData.get(level.getServer()).touchShop(listingId);
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
        MarketSavedData store = MarketSavedData.get(level.getServer());
        // The mirror is only published while it still describes the escrow. If it has never been
        // read, or the escrow has moved since it was (a remote sale, an expiry), it is stale: adopt
        // the escrow instead of overwriting it with a view of the past.
        if (agreed == null || !ItemStack.matches(storedStock(), agreed)) { syncOffer(); return; }
        ItemStack stock = offer.getItem(OFFER_SLOT);
        if (stock.getCount() > stock.getMaxStackSize()) {
            // Never trim stock away: the excess is the owner's, so it is booked for them.
            ItemStack excess = stock.copyWithCount(stock.getCount() - stock.getMaxStackSize());
            stock.setCount(stock.getMaxStackSize());
            if (!demo) VaultStacks.deliver(EconomyStore.get(level.getServer()), owner, excess);
        }
        // replaceShopStock hands the displaced escrow back to us. The mirror was synced from that
        // same escrow at the start of this interaction and vanilla has already routed anything it
        // took out of the slot to the player, so the displaced stack is a spent duplicate here;
        // claiming it would mint items. Loss is instead prevented by never publishing a stale mirror.
        store.replaceShopStock(listingId, owner, stock, price, location(), demo);
        agreed = stock.copy();
    }

    private void forgetStalePurchases(long tick) {
        lastPurchaseTick.values().removeIf(value -> tick - value > PURCHASE_MEMORY_TICKS || value > tick);
        if (lastPurchaseTick.size() > MAX_REMEMBERED_BUYERS) lastPurchaseTick.clear();
    }

    private String location() {
        return level instanceof ServerLevel serverLevel ? ShopOwnershipState.key(serverLevel, worldPosition) : "";
    }

    @Override public Component getDisplayName() { return Component.translatable("container.neroeconomy.player_shop"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        syncOffer();
        touch(player);
        return new PlayerShopMenu(id, inventory, this, player);
    }
    @Override public int getContainerSize() { return CONTAINER_SIZE; }
    @Override public boolean isEmpty() { return offer.getItem(OFFER_SLOT).isEmpty(); }
    @Override public ItemStack getItem(int slot) {
        return slot == OFFER_SLOT ? offer.getItem(OFFER_SLOT) : ItemStack.EMPTY;
    }
    @Override public ItemStack removeItem(int slot, int amount) {
        // Demo stock backs nothing real, so it can never be taken out as items.
        if (slot != OFFER_SLOT || amount <= 0 || demo || owner() == null) return ItemStack.EMPTY;
        if (agreed == null) syncOffer();
        ItemStack removed = offer.removeItem(OFFER_SLOT, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, Integer.MAX_VALUE); }
    @Override public void setItem(int slot, ItemStack stack) {
        UUID owner = owner();
        if (slot != OFFER_SLOT || owner == null) return;
        if (level != null && !level.isClientSide()) {
            // A frozen economy escrows nothing. The slot already refuses a stack then; should one
            // arrive anyway it is handed back into the world, never into a store that is not saved.
            if (EconomyHealth.frozen()) {
                if (!stack.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, worldPosition, stack.copy());
                return;
            }
            if (agreed == null) syncOffer();
            // Vanilla has already taken the stack off the cursor by the time it calls this, so a
            // stack the shop may not hold is delivered to the owner's vault instead of being dropped.
            if (!demo && !stack.isEmpty() && !MarketSavedData.tradeable(stack, level.registryAccess())) {
                VaultStacks.deliver(EconomyStore.get(level.getServer()), owner, stack);
                // If that is the very stack the slot was already showing, the slot must let go of
                // it too, or it would exist both in the vault and in the shop.
                if (offer.getItem(OFFER_SLOT) == stack) { offer.setItem(OFFER_SLOT, ItemStack.EMPTY); setChanged(); }
                return;
            }
            // Defensive only: vanilla never inserts past Slot#getMaxStackSize, and any excess is
            // claimed rather than trimmed away, so an oversized insert can never delete items.
            if (stack.getCount() > stack.getMaxStackSize()) {
                ItemStack excess = stack.copyWithCount(stack.getCount() - stack.getMaxStackSize());
                stack.setCount(stack.getMaxStackSize());
                if (!demo) VaultStacks.deliver(EconomyStore.get(level.getServer()), owner, excess);
            }
        }
        offer.setItem(OFFER_SLOT, stack);
        setChanged();
    }
    @Override public void setChanged() {
        super.setChanged();
        publish();
    }
    /** Same rule as every NeroEconomy menu: still here, in the player's own level, and in reach. */
    @Override public boolean stillValid(Player player) { return IntentGuard.anchored(player, this); }
    /** Server side this also refuses a stack the market would not escrow; the client cannot tell and allows it. */
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot != OFFER_SLOT) return false;
        if (level == null || level.isClientSide()) return true;
        return !EconomyHealth.frozen() && MarketSavedData.tradeable(stack, level.registryAccess());
    }
    /**
     * Empties the shop on behalf of a caller that will not keep the contents (another mod clearing
     * a container before replacing the block). The escrow is the truth, not the mirror, so the whole
     * listing is released from the store: the stock goes to its owner's vault, or is destroyed
     * for a demo shop. Nothing is published afterwards, because there is nothing left to publish.
     */
    @Override public void clearContent() {
        cancel();
        super.setChanged();
    }
    @Override public int[] getSlotsForFace(Direction side) { return NO_AUTOMATION_SLOTS; }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) { return false; }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) { return false; }

    /**
     * The block is being replaced, by anything: a player, an explosion, a command that keeps side
     * effects. The escrow is released before the default handler runs, so that handler finds an
     * empty container and drops nothing, and the owner row goes with the block.
     */
    @Override public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        cancel();
        if (level instanceof ServerLevel serverLevel)
            ShopOwnershipState.get(serverLevel.getServer()).remove(serverLevel, pos);
        super.preRemoveSideEffects(pos, state);
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("ListingId", listingId.toString());
        output.putLong("Price", price);
        if (demo) output.putBoolean("Demo", true);
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        try { listingId = UUID.fromString(input.getStringOr("ListingId", listingId.toString())); }
        catch (IllegalArgumentException ignored) { listingId = UUID.randomUUID(); }
        price = Math.max(1L, input.getLongOr("Price", 1L));
        demo = input.getBooleanOr("Demo", false);
        offer.setItem(OFFER_SLOT, ItemStack.EMPTY);
        // The mirror was just emptied without reading the escrow, so it describes nothing yet.
        agreed = null;
    }
}
