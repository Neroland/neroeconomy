package za.co.neroland.neroeconomy.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubRouter;
import za.co.neroland.neroeconomy.hub.HubSession;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.hub.PageCoalescer;
import za.co.neroland.neroeconomy.network.HubIntentPayload;
import za.co.neroland.neroeconomy.network.HubPagePayload;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

/**
 * The Economy Hub's menu: no slots, anchored to the economy block it was opened at, and valid only
 * while its player stays there. The server pushes the current tab's page (see {@link HubSession});
 * the client sends one kind of intent ({@link HubIntentPayload}), which is checked by
 * {@link HubRouter} against the page this menu last sent. Pages are pushed at most once a tick and
 * only when asked for or when the data behind them changed ({@link PageCoalescer}).
 */
public final class EconomyHubMenu extends AbstractContainerMenu implements TerminalAnchored {
    private final BlockEntity terminal;
    private final ServerPlayer serverPlayer;
    private final HubSession session;
    private final PageCoalescer coalescer = new PageCoalescer();
    private int revision;

    /** Client side: the page arrives by payload. */
    public EconomyHubMenu(int id, Inventory inventory) {
        this(id, inventory, null, HubTab.WALLET);
    }

    /** Server side: opened at {@code terminal}, on {@code tab}. */
    public EconomyHubMenu(int id, Inventory inventory, BlockEntity terminal, HubTab tab) {
        super(ModMenuTypes.ECONOMY_HUB.get(), id);
        this.terminal = terminal;
        this.serverPlayer = inventory.player instanceof ServerPlayer value ? value : null;
        this.session = serverPlayer == null ? null : new HubSession(serverPlayer, terminal, tab);
    }

    @Override public BlockEntity terminalAnchor() { return terminal; }
    @Override public boolean stillValid(Player player) { return IntentGuard.anchored(player, terminal); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (serverPlayer == null || session == null) return;
        long tick = serverPlayer.level().getServer().getTickCount();
        if (coalescer.due(tick, session.signatureInterval(), session::signature)) push();
    }

    private void push() {
        HubPage page = session.build().at(containerId, ++revision);
        NeroEconomyNetwork.sendToPlayer(serverPlayer, new HubPagePayload(page));
    }

    /** One intent from the client: checked, carried out, and the page sent again on the next tick. */
    public void handle(ServerPlayer player, HubIntentPayload payload) {
        if (session == null || player != serverPlayer) return;
        HubRouter.Context context = new HubRouter.Context(session.tab(), session.rows(), EconomyHealth.frozen(),
                GoodsSource.allowed(player), session.admin(), EconomyConfig.ADMIN_MUTATIONS_ENABLED.get());
        HubRouter.Result result = HubRouter.route(payload.intent(), context, session);
        if (result == HubRouter.Result.GOODS_SOURCE) player.sendSystemMessage(Component.translatable(GoodsSource.REFUSED_KEY));
        else if (result == HubRouter.Result.MUTATIONS_OFF)
            player.sendSystemMessage(Component.translatable("gui.neroeconomy.hub.admin.mutations_off"));
        else if (result == HubRouter.Result.FROZEN) player.sendSystemMessage(Component.translatable(EconomyHealth.FROZEN_KEY));
        // Whatever happened, the player sees the page as it is now; a refused intent changes nothing on it.
        coalescer.force();
    }
}
