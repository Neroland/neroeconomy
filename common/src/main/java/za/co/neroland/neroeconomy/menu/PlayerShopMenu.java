package za.co.neroland.neroeconomy.menu;

import java.util.function.BooleanSupplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.market.PurchaseOutcome;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.RateLimiter;
import za.co.neroland.neroeconomy.network.ShopBuyPayload;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

/**
 * One offer slot plus the player inventory. The shop container is deliberately a single slot: the
 * block entity stores exactly one offer, and vanilla destroys the carried stack for any slot a
 * container advertises but does not store.
 */
public final class PlayerShopMenu extends AbstractContainerMenu {
    // Data slots. Price and balance are 64-bit and take four 16-bit slots each (see Packed64).
    private static final int OWNER_SLOT = 0;
    private static final int PRICE_SLOT = OWNER_SLOT + 1;
    private static final int BALANCE_SLOT = PRICE_SLOT + Packed64.SLOTS;
    private static final int STOCK_SLOT = BALANCE_SLOT + Packed64.SLOTS;
    private static final int DATA_COUNT = STOCK_SLOT + 1;
    private final Container shop;
    private final ContainerData data;
    private final PlayerShopBlockEntity blockEntity;

    public PlayerShopMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(1), inventory.player);
    }

    @SuppressWarnings("this-escape")
    public PlayerShopMenu(int id, Inventory inventory, Container shop, Player player) {
        super(ModMenuTypes.PLAYER_SHOP.get(), id);
        this.shop = shop;
        this.blockEntity = shop instanceof PlayerShopBlockEntity be ? be : null;
        this.data = blockEntity != null && player instanceof ServerPlayer serverPlayer
                ? new ShopData(blockEntity, serverPlayer) : new SimpleContainerData(DATA_COUNT);
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
        // Slot coordinates mirror the sockets painted into textures/gui/player_shop.png: the offer slot
        // sits inside the buyer well, the inventory rows below the taller control stack.
        // The slot asks the synced owner flag on both sides: the client has no block entity to ask,
        // so a flag worked out locally there would always say "not the owner".
        // Stock never comes out of a creative-mode or spectator inventory; taking it back is allowed.
        addSlot(new ShopSlot(shop, PlayerShopBlockEntity.OFFER_SLOT, 12, 32, this::owner,
                () -> GoodsSource.allowed(player)));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 132 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 190));
    }

    public boolean owner() { return data.get(OWNER_SLOT) != 0; }
    /** The price a buyer is charged right now, already re-quoted through the pricing engine. */
    public long price() { return Packed64.read(data::get, PRICE_SLOT); }
    public long balance() { return Packed64.read(data::get, BALANCE_SLOT); }
    public int stock() { return data.get(STOCK_SLOT); }

    @Override public boolean stillValid(Player player) {
        // The client-side menu wraps a plain container and has no block to stay near.
        return blockEntity == null ? shop.stillValid(player) : IntentGuard.anchored(player, blockEntity);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    /**
     * The offer slot hands out live escrow stacks, so the mirror is refreshed from the authoritative
     * store immediately before vanilla reads, mutates and writes it back.
     */
    @Override public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (blockEntity != null) blockEntity.syncOffer();
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    @Override public void broadcastChanges() {
        if (blockEntity != null) blockEntity.syncOffer();
        super.broadcastChanges();
    }

    /** Server-validated purchase carrying the client's request id, so retries stay idempotent. */
    public void buy(ServerPlayer buyer, ShopBuyPayload intent) {
        if (blockEntity == null) return;
        PurchaseOutcome outcome = blockEntity.purchaseOutcome(buyer, intent.quotedUnitPrice(), intent.requestId(),
                intent.itemId());
        // The buyer is told what they paid, and what a price modifier added or took off.
        TradeMessages.purchase(buyer, outcome, 1, intent.itemId(),
                "gui.neroeconomy.shop.purchase_success", "gui.neroeconomy.shop.purchase_failed");
    }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (blockEntity == null || !blockEntity.canManage(player.getUUID())) return false;
        long delta = switch (id) { case 1 -> 1L; case 2 -> -1L; case 3 -> 10L; case 4 -> -10L; default -> 0L; };
        if (delta == 0L) return false;
        // A price nudge moves no value and is pressed in quick runs, so it spends the generous budget.
        if (!IntentGuard.button(player, this, RateLimiter.Budget.QUERY)) return false;
        long current = blockEntity.price();
        if (delta > 0L && Long.MAX_VALUE - current < delta) return true;
        blockEntity.setPrice(Math.max(1L, current + delta));
        return true;
    }

    private static final class ShopSlot extends Slot {
        private final BooleanSupplier owner;
        private final BooleanSupplier maySupply;
        ShopSlot(Container container, int slot, int x, int y, BooleanSupplier owner, BooleanSupplier maySupply) {
            super(container, slot, x, y);
            this.owner = owner;
            this.maySupply = maySupply;
        }
        /** The container has the last word: server side it refuses a stack the market would not escrow. */
        @Override public boolean mayPlace(ItemStack stack) {
            return owner.getAsBoolean() && maySupply.getAsBoolean()
                    && container.canPlaceItem(getContainerSlot(), stack);
        }
        @Override public boolean mayPickup(Player player) { return owner.getAsBoolean(); }
    }

    /** What one viewer is shown of the shop, worked out together once per tick. */
    private record Snapshot(boolean owner, long price, long balance, int stock) { }

    private static final class ShopData implements ContainerData {
        private final PlayerShopBlockEntity shop;
        private final ServerPlayer player;
        private final TickCache<Snapshot> snapshot = new TickCache<>();

        ShopData(PlayerShopBlockEntity shop, ServerPlayer player) {
            this.shop = shop;
            this.player = player;
        }

        @Override public int get(int index) {
            // The gauge shows the executed price, not the base price: any active shock or modifier
            // would otherwise make the displayed quote one the server will never charge. Ten slots
            // are read per sync, so the quote, the balance and the stock are each worked out once per
            // tick and every slot reads that snapshot.
            Snapshot now = snapshot.get(player.level().getServer().getTickCount(), () -> new Snapshot(
                    shop.canManage(player.getUUID()), shop.quotedUnitPrice(player.getUUID()),
                    CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS), shop.stockCount()));
            if (index == OWNER_SLOT) return now.owner() ? 1 : 0;
            if (index == STOCK_SLOT) return now.stock();
            if (index >= PRICE_SLOT && index < BALANCE_SLOT) return Packed64.get(now.price(), index - PRICE_SLOT);
            if (index >= BALANCE_SLOT && index < STOCK_SLOT) return Packed64.get(now.balance(), index - BALANCE_SLOT);
            return 0;
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
