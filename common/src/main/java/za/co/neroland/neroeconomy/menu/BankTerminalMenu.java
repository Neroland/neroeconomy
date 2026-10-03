package za.co.neroland.neroeconomy.menu;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.RateLimiter;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

public final class BankTerminalMenu extends AbstractContainerMenu implements TerminalAnchored {
    /** The balance, as four 16-bit data slots (see {@link Packed64}). */
    private static final int DATA_COUNT = Packed64.SLOTS;
    /** Menu button: collect everything owed to the player. */
    public static final int COLLECT_BUTTON = 3;
    /** Menu button: open the player's vault. */
    public static final int VAULT_BUTTON = 4;
    private final ContainerData data;
    private final BankTerminalBlockEntity terminal;

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

    public long balance() { return Packed64.read(data::get, 0); }
    @Override public net.minecraft.world.level.block.entity.BlockEntity terminalAnchor() { return terminal; }
    @Override public boolean stillValid(Player player) { return IntentGuard.anchored(player, terminal); }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override public boolean clickMenuButton(Player player, int id) {
        if ((id != COLLECT_BUTTON && id != VAULT_BUTTON) || !(player instanceof ServerPlayer collector)
                || !IntentGuard.button(player, this, RateLimiter.Budget.VALUE)) return false;
        if (id == VAULT_BUTTON) return openVault(collector);
        MarketSavedData store = MarketSavedData.get(collector.level().getServer());
        long credits = store.collectCreditsTotal(collector.getUUID());
        int items = store.collectItems(collector);
        collector.sendSystemMessage(Component.translatable("gui.neroeconomy.market.collect_complete", credits, items));
        return true;
    }

    /**
     * Opens the player's own vault in place of this menu. The vault is only ever opened here: by
     * the server, for the player standing at the terminal, after the shared guard has passed (which
     * also refuses, with the one frozen message, while the economy is frozen).
     */
    private boolean openVault(ServerPlayer opener) {
        if (terminal == null) return false;
        opener.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) ->
                new VaultMenu(containerId, inventory, opener, terminal),
                Component.translatable("container.neroeconomy.vault")));
        return true;
    }

    /**
     * Pays {@code amount} credits to {@code targetValue}, resolved exactly as {@code /eco pay}
     * resolves its recipient. Every refusal happens before the ledger is called, because the ledger
     * opens (and funds) an account on its first write: an unknown recipient or a short balance must
     * leave no account and no audit row behind. The request id is recorded in the shared request
     * ledger only once the payment is otherwise good, so a replayed packet pays once and a refused
     * one does not use its id up.
     */
    public void transfer(ServerPlayer source, UUID requestId, String targetValue, long amount) {
        if (source == null || requestId == null || targetValue == null) return;
        MinecraftServer server = source.level().getServer();
        UUID target = KnownAccounts.resolve(server, targetValue);
        // A payment to another player cannot use a starting balance that has not vested yet.
        long spendable = NeroEconomyCommon.CURRENCY_PROVIDER.spendable(source.getUUID());
        BankTransferRules.Check check = BankTransferRules.check(amount, target != null,
                source.getUUID().equals(target), CurrencyApi.getBalance(source.getUUID(), NeroEconomyCommon.CREDITS),
                spendable);
        if (check != BankTransferRules.Check.OK) {
            source.sendSystemMessage(check == BankTransferRules.Check.FUNDS_LOCKED
                    ? Component.translatable(check.translationKey(), spendable,
                            EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get())
                    : Component.translatable(check.translationKey(), amount));
            return;
        }
        // A replay of a payment that already went through: answered by the first one, so say nothing.
        if (!MarketSavedData.get(server).rememberRequest(source.getUUID(), requestId)) return;
        boolean paid = CurrencyApi.transfer(source.getUUID(), target, NeroEconomyCommon.CREDITS, amount);
        source.sendSystemMessage(Component.translatable(paid ? "gui.neroeconomy.bank.payment_sent"
                : "gui.neroeconomy.bank.payment_failed", amount));
        ServerPlayer online = paid ? server.getPlayerList().getPlayer(target) : null;
        if (online != null)
            online.sendSystemMessage(Component.translatable("command.neroeconomy.payment_received", amount));
    }

    /** The viewer's balance, looked up once per tick however many of its four slots are read. */
    private static final class BalanceData implements ContainerData {
        private final ServerPlayer player;
        private final TickCache<Long> balance = new TickCache<>();
        BalanceData(ServerPlayer player) { this.player = player; }
        @Override public int get(int index) {
            long value = balance.get(player.level().getServer().getTickCount(),
                    () -> CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS));
            return Packed64.get(value, index);
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
