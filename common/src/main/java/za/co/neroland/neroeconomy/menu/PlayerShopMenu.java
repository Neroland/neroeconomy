package za.co.neroland.neroeconomy.menu;

import net.minecraft.network.chat.Component;
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
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.network.ShopBuyPayload;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

/**
 * One offer slot plus the player inventory. The shop container is deliberately a single slot: the
 * block entity stores exactly one offer, and vanilla destroys the carried stack for any slot a
 * container advertises but does not store.
 */
public final class PlayerShopMenu extends AbstractContainerMenu {
    private static final int DATA_COUNT = 6;
    private final Container shop;
    private final ContainerData data;
    private final PlayerShopBlockEntity blockEntity;
    private final boolean owner;

    public PlayerShopMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(1), inventory.player);
    }

    @SuppressWarnings("this-escape")
    public PlayerShopMenu(int id, Inventory inventory, Container shop, Player player) {
        super(ModMenuTypes.PLAYER_SHOP.get(), id);
        this.shop = shop;
        this.blockEntity = shop instanceof PlayerShopBlockEntity be ? be : null;
        this.owner = blockEntity != null && blockEntity.isOwner(player.getUUID());
        this.data = blockEntity != null && player instanceof ServerPlayer serverPlayer
                ? new ShopData(blockEntity, serverPlayer, owner) : new SimpleContainerData(DATA_COUNT);
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
        // Slot coordinates mirror the sockets painted into textures/gui/player_shop.png: the offer slot
        // sits inside the buyer well, the inventory rows below the taller control stack.
        addSlot(new ShopSlot(shop, PlayerShopBlockEntity.OFFER_SLOT, 12, 32, owner));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 132 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 190));
    }

    public boolean owner() { return data.get(0) != 0; }
    /** The price a buyer is charged right now, already re-quoted through the pricing engine. */
    public long price() { return Integer.toUnsignedLong(data.get(1)) | ((long) data.get(2) << 32); }
    public long balance() { return Integer.toUnsignedLong(data.get(3)) | ((long) data.get(4) << 32); }
    public int stock() { return data.get(5); }

    @Override public boolean stillValid(Player player) { return shop.stillValid(player); }
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
        PurchaseResult result = blockEntity.purchase(buyer, intent.quotedUnitPrice(), intent.requestId());
        buyer.sendSystemMessage(Component.translatable(result == PurchaseResult.SUCCESS
                ? "gui.neroeconomy.shop.purchase_success" : "gui.neroeconomy.shop.purchase_failed", result.name()));
    }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (blockEntity == null || !blockEntity.isOwner(player.getUUID())) return false;
        long delta = switch (id) { case 1 -> 1L; case 2 -> -1L; case 3 -> 10L; case 4 -> -10L; default -> 0L; };
        if (delta == 0L) return false;
        long current = blockEntity.price();
        if (delta > 0L && Long.MAX_VALUE - current < delta) return true;
        blockEntity.setPrice(Math.max(1L, current + delta));
        return true;
    }

    private static final class ShopSlot extends Slot {
        private final boolean owner;
        ShopSlot(Container container, int slot, int x, int y, boolean owner) {
            super(container, slot, x, y);
            this.owner = owner;
        }
        @Override public boolean mayPlace(ItemStack stack) { return owner; }
        @Override public boolean mayPickup(Player player) { return owner; }
    }

    private record ShopData(PlayerShopBlockEntity shop, ServerPlayer player, boolean owner) implements ContainerData {
        @Override public int get(int index) {
            // The gauge shows the executed price, not the base price: any active shock or modifier
            // would otherwise make the displayed quote one the server will never charge. Each value
            // is computed only for the index that needs it, so one sync is two quotes, not six.
            return switch (index) {
                case 0 -> owner ? 1 : 0;
                case 1 -> (int) price();
                case 2 -> (int) (price() >>> 32);
                case 3 -> (int) balance();
                case 4 -> (int) (balance() >>> 32);
                case 5 -> shop.stockCount();
                default -> 0;
            };
        }
        private long price() { return shop.quotedUnitPrice(player.getUUID()); }
        private long balance() { return CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS); }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
