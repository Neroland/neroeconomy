package za.co.neroland.neroeconomy.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.menu.AutoTraderMenu;
import za.co.neroland.neroeconomy.menu.BankTerminalMenu;
import za.co.neroland.neroeconomy.menu.DirectTradeMenu;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;
import za.co.neroland.neroeconomy.network.RateLimiter.Budget;
import za.co.neroland.neroeconomy.platform.Services;

/**
 * Payload declarations for the dedicated {@code neroeconomy:main} channel.
 *
 * <p>A serverbound payload can only be declared through {@link #intent}, which puts the shared
 * {@link IntentGuard} in front of its handler: the packet budget first, then the open-menu and
 * player checks. A handler therefore never runs for a flooding, dead or spectating player, for a
 * stale container id, or for a menu whose block is gone, out of reach or in another level. A
 * value-moving intent is also refused, with one plain message, while the economy is frozen.
 */
public final class NeroEconomyNetwork {
    /** What a guarded intent does once the guard has handed it the player's open menu. */
    @FunctionalInterface
    public interface IntentHandler<M extends AbstractContainerMenu, T extends CustomPacketPayload> {
        void handle(M menu, ServerPlayer player, T payload);
    }

    public record Clientbound<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Consumer<T> handler) { }
    public record Serverbound<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) { }

    private static final List<Clientbound<?>> CLIENTBOUND = new ArrayList<>();
    private static final List<Serverbound<?>> SERVERBOUND = new ArrayList<>();
    private static boolean initialized;

    private NeroEconomyNetwork() { }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        clientbound(MarketPagePayload.TYPE, MarketPagePayload.STREAM_CODEC, ClientMarketPage::accept);
        intent(MarketQueryPayload.TYPE, MarketQueryPayload.STREAM_CODEC, value -> Budget.QUERY,
                TradingTerminalMenu.class, MarketQueryPayload::containerId,
                (menu, player, value) -> menu.sendPage(value.query(), value.page()));
        intent(MarketBuyPayload.TYPE, MarketBuyPayload.STREAM_CODEC, value -> Budget.VALUE,
                TradingTerminalMenu.class, MarketBuyPayload::containerId, TradingTerminalMenu::buy);
        intent(MarketPostPayload.TYPE, MarketPostPayload.STREAM_CODEC, value -> Budget.VALUE,
                TradingTerminalMenu.class, MarketPostPayload::containerId, TradingTerminalMenu::post);
        intent(MarketClaimPayload.TYPE, MarketClaimPayload.STREAM_CODEC, value -> Budget.VALUE,
                TradingTerminalMenu.class, MarketClaimPayload::containerId, (menu, player, value) -> {
                    MarketSavedData store = MarketSavedData.get(player.level().getServer());
                    long credits = store.collectCreditsTotal(player.getUUID());
                    int items = store.collectItems(player);
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "gui.neroeconomy.market.collect_complete", credits, items));
                });
        clientbound(OpportunityPagePayload.TYPE, OpportunityPagePayload.STREAM_CODEC, ClientOpportunityPage::accept);
        intent(OpportunityIntentPayload.TYPE, OpportunityIntentPayload.STREAM_CODEC,
                value -> value.action().moves() ? Budget.VALUE : Budget.QUERY,
                TradingTerminalMenu.class, OpportunityIntentPayload::containerId,
                TradingTerminalMenu::handleOpportunity);
        intent(BankTransferPayload.TYPE, BankTransferPayload.STREAM_CODEC, value -> Budget.VALUE,
                BankTerminalMenu.class, BankTransferPayload::containerId, (menu, player, value) ->
                        menu.transfer(player, value.requestId(), value.target(), value.amount()));
        intent(MarketBuyOfferPayload.TYPE, MarketBuyOfferPayload.STREAM_CODEC, value -> Budget.VALUE,
                TradingTerminalMenu.class, MarketBuyOfferPayload::containerId, TradingTerminalMenu::postBuyOffer);
        intent(MarketFillBuyOfferPayload.TYPE, MarketFillBuyOfferPayload.STREAM_CODEC, value -> Budget.VALUE,
                TradingTerminalMenu.class, MarketFillBuyOfferPayload::containerId, TradingTerminalMenu::fillBuyOffer);
        intent(ShopBuyPayload.TYPE, ShopBuyPayload.STREAM_CODEC, value -> Budget.VALUE,
                PlayerShopMenu.class, ShopBuyPayload::containerId, PlayerShopMenu::buy);
        intent(DirectTradePayload.TYPE, DirectTradePayload.STREAM_CODEC, value -> Budget.VALUE,
                DirectTradeMenu.class, DirectTradePayload::containerId, DirectTradeMenu::handle);
        clientbound(AutoTraderStatePayload.TYPE, AutoTraderStatePayload.STREAM_CODEC, ClientAutoTrader::accept);
        intent(AutoTraderPayload.TYPE, AutoTraderPayload.STREAM_CODEC, value -> Budget.VALUE,
                AutoTraderMenu.class, AutoTraderPayload::containerId, AutoTraderMenu::handle);
    }

    public static <T extends CustomPacketPayload> void clientbound(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Consumer<T> handler) {
        CLIENTBOUND.add(new Clientbound<>(type, codec, handler));
    }

    /**
     * Declares a serverbound payload behind the shared guard. There is deliberately no way to
     * declare one without it.
     *
     * @param budget      which packet budget this payload spends, decided per packet
     * @param menuType    the menu the intent acts through; any other open menu drops the packet
     * @param containerId reads the container id the client addressed
     * @param action      runs only for a guarded player, with their open menu
     */
    public static <T extends CustomPacketPayload, M extends AbstractContainerMenu> void intent(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Function<T, Budget> budget, Class<M> menuType, ToIntFunction<T> containerId,
            IntentHandler<M, T> action) {
        SERVERBOUND.add(new Serverbound<>(type, codec, (payload, player) -> {
            // Over budget, or not allowed to act: dropped without a reply and without a log line.
            Budget cost = budget.apply(payload);
            if (player == null || !IntentGuard.withinBudget(player, cost)) return;
            M menu = IntentGuard.openMenu(player, menuType, containerId.applyAsInt(payload));
            if (menu == null) return;
            // A frozen economy moves no value: the player is told so, and the handler never runs.
            if (cost == Budget.VALUE && IntentGuard.refusedWhileFrozen(player)) return;
            action.handle(menu, player, payload);
        }));
    }

    /**
     * Cuts a string to the length its payload declares. {@code writeUtf} throws on anything longer,
     * and a throw during encode disconnects the receiver, so every free string is clipped on write.
     */
    public static String clip(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength));
    }

    public static List<Clientbound<?>> clientbound() { return List.copyOf(CLIENTBOUND); }
    public static List<Serverbound<?>> serverbound() { return List.copyOf(SERVERBOUND); }
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        Services.NETWORK.sendToPlayer(player, payload);
    }
    public static void sendToServer(CustomPacketPayload payload) { Services.NETWORK.sendToServer(payload); }
}
