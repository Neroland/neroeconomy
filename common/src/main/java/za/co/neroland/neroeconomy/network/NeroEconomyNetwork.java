package za.co.neroland.neroeconomy.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.platform.Services;

/** Payload declarations for the dedicated {@code neroeconomy:main} channel. */
public final class NeroEconomyNetwork {
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
        serverbound(MarketQueryPayload.TYPE, MarketQueryPayload.STREAM_CODEC, MarketQueryPayload::handle);
        serverbound(MarketBuyPayload.TYPE, MarketBuyPayload.STREAM_CODEC, MarketBuyPayload::handle);
        serverbound(MarketPostPayload.TYPE, MarketPostPayload.STREAM_CODEC, MarketPostPayload::handle);
        serverbound(MarketClaimPayload.TYPE, MarketClaimPayload.STREAM_CODEC, MarketClaimPayload::handle);
        clientbound(OpportunityPagePayload.TYPE, OpportunityPagePayload.STREAM_CODEC, ClientOpportunityPage::accept);
        serverbound(OpportunityIntentPayload.TYPE, OpportunityIntentPayload.STREAM_CODEC, OpportunityIntentPayload::handle);
        serverbound(BankTransferPayload.TYPE, BankTransferPayload.STREAM_CODEC, BankTransferPayload::handle);
        serverbound(MarketBuyOfferPayload.TYPE, MarketBuyOfferPayload.STREAM_CODEC, MarketBuyOfferPayload::handle);
        serverbound(MarketFillBuyOfferPayload.TYPE, MarketFillBuyOfferPayload.STREAM_CODEC,
                MarketFillBuyOfferPayload::handle);
        serverbound(ShopBuyPayload.TYPE, ShopBuyPayload.STREAM_CODEC, ShopBuyPayload::handle);
    }

    public static <T extends CustomPacketPayload> void clientbound(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Consumer<T> handler) {
        CLIENTBOUND.add(new Clientbound<>(type, codec, handler));
    }

    public static <T extends CustomPacketPayload> void serverbound(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
        SERVERBOUND.add(new Serverbound<>(type, codec, handler));
    }

    public static List<Clientbound<?>> clientbound() { return List.copyOf(CLIENTBOUND); }
    public static List<Serverbound<?>> serverbound() { return List.copyOf(SERVERBOUND); }
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        Services.NETWORK.sendToPlayer(player, payload);
    }
    public static void sendToServer(CustomPacketPayload payload) { Services.NETWORK.sendToServer(payload); }
}
