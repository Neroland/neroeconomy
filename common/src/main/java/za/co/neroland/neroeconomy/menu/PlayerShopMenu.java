package za.co.neroland.neroeconomy.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

public final class PlayerShopMenu extends AbstractContainerMenu {
    private static final int DATA_COUNT = 6;
    private final Container shop;
    private final ContainerData data;
    private final PlayerShopBlockEntity blockEntity;
    private final boolean owner;

    public PlayerShopMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(9), inventory.player);
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
        for (int slot = 0; slot < 9; slot++) {
            addSlot(new ShopSlot(shop, slot, 8 + slot * 18, 34, owner));
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 142));
    }

    public boolean owner() { return data.get(0) != 0; }
    public long price() { return Integer.toUnsignedLong(data.get(1)) | ((long) data.get(2) << 32); }
    public long balance() { return Integer.toUnsignedLong(data.get(3)) | ((long) data.get(4) << 32); }
    public int stock() { return data.get(5); }

    @Override public boolean stillValid(Player player) { return shop.stillValid(player); }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (blockEntity == null) return false;
        if (id == 0 && player instanceof ServerPlayer buyer) {
            var result = blockEntity.purchase(buyer, blockEntity.price(), java.util.UUID.randomUUID());
            boolean success = result == za.co.neroland.neroeconomy.market.PurchaseResult.SUCCESS;
            buyer.sendSystemMessage(Component.translatable(success ? "gui.neroeconomy.shop.purchase_success"
                    : "gui.neroeconomy.shop.purchase_failed", result.name()));
            return true;
        }
        if (!blockEntity.isOwner(player.getUUID())) return false;
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
            long price = shop.price();
            long balance = CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS);
            return switch (index) {
                case 0 -> owner ? 1 : 0;
                case 1 -> (int) price;
                case 2 -> (int) (price >>> 32);
                case 3 -> (int) balance;
                case 4 -> (int) (balance >>> 32);
                case 5 -> shop.stockCount();
                default -> 0;
            };
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
