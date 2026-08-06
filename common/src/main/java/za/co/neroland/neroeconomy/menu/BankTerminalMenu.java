package za.co.neroland.neroeconomy.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;
import za.co.neroland.neroeconomy.market.MarketSavedData;

public final class BankTerminalMenu extends AbstractContainerMenu {
    private static final int DATA_COUNT = 2;
    private final ContainerData data;
    private final BankTerminalBlockEntity terminal;
    private final java.util.Map<java.util.UUID, java.util.LinkedHashSet<java.util.UUID>> requests = new java.util.HashMap<>();

    public BankTerminalMenu(int id, Inventory inventory) {
        this(id, inventory, inventory.player, null);
    }

    @SuppressWarnings("this-escape")
    public BankTerminalMenu(int id, Inventory inventory, Player player, BankTerminalBlockEntity terminal) {
        super(ModMenuTypes.BANK_TERMINAL.get(), id);
        this.terminal = terminal;
        this.data = player instanceof ServerPlayer serverPlayer ? new BalanceData(serverPlayer)
                : new SimpleContainerData(DATA_COUNT);
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
    }

    public long balance() { return Integer.toUnsignedLong(data.get(0)) | ((long) data.get(1) << 32); }
    @Override public boolean stillValid(Player player) {
        return terminal == null || !terminal.isRemoved() && player.distanceToSqr(
                terminal.getBlockPos().getX() + 0.5D, terminal.getBlockPos().getY() + 0.5D,
                terminal.getBlockPos().getZ() + 0.5D) <= 64.0D;
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (id == 3 && player instanceof ServerPlayer collector) {
            MarketSavedData store = MarketSavedData.get(collector.level().getServer());
            store.collectCredits(collector.getUUID());
            store.collectItems(collector);
            collector.sendSystemMessage(Component.translatable("gui.neroeconomy.market.collect_complete"));
            return true;
        }
        return false;
    }

    public void transfer(ServerPlayer source, java.util.UUID requestId, String targetValue, long amount) {
        if (source == null || requestId == null || targetValue == null || amount < 1L) return;
        var seen = requests.computeIfAbsent(source.getUUID(), ignored -> new java.util.LinkedHashSet<>());
        if (!seen.add(requestId)) return;
        while (seen.size() > 128) seen.remove(seen.iterator().next());
        ServerPlayer online = source.level().getServer().getPlayerList().getPlayerByName(targetValue);
        java.util.UUID target;
        if (online != null) target = online.getUUID();
        else try { target = java.util.UUID.fromString(targetValue); }
        catch (IllegalArgumentException invalid) {
            source.sendSystemMessage(Component.translatable("gui.neroeconomy.bank.invalid_target")); return;
        }
        if (source.getUUID().equals(target)) {
            source.sendSystemMessage(Component.translatable("command.neroeconomy.self_payment")); return;
        }
        boolean paid = CurrencyApi.transfer(source.getUUID(), target, NeroEconomyCommon.CREDITS, amount);
        source.sendSystemMessage(Component.translatable(paid ? "gui.neroeconomy.bank.payment_sent"
                : "gui.neroeconomy.bank.payment_failed", amount));
        if (paid && online != null)
            online.sendSystemMessage(Component.translatable("command.neroeconomy.payment_received", amount));
    }

    private static final class BalanceData implements ContainerData {
        private final ServerPlayer player;
        BalanceData(ServerPlayer player) { this.player = player; }
        @Override public int get(int index) {
            long value = CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS);
            return index == 0 ? (int) value : (int) (value >>> 32);
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
