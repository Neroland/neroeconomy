package za.co.neroland.neroeconomy.platform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;

public final class FabricNetworkPlatform implements NetworkPlatform {
    @Override public void register(Object ignored) {
        NeroEconomyNetwork.clientbound().forEach(FabricNetworkPlatform::registerClientboundType);
        NeroEconomyNetwork.serverbound().forEach(FabricNetworkPlatform::registerServerbound);
    }
    @Override public void registerClient() {
        NeroEconomyNetwork.clientbound().forEach(FabricNetworkPlatform::registerClientReceiver);
    }
    private static <T extends CustomPacketPayload> void registerClientboundType(NeroEconomyNetwork.Clientbound<T> value) {
        PayloadTypeRegistry.clientboundPlay().register(value.type(), value.codec());
    }
    private static <T extends CustomPacketPayload> void registerServerbound(NeroEconomyNetwork.Serverbound<T> value) {
        PayloadTypeRegistry.serverboundPlay().register(value.type(), value.codec());
        ServerPlayNetworking.registerGlobalReceiver(value.type(), (payload, context) -> {
            ServerPlayer player = context.player();
            player.level().getServer().execute(() -> value.handler().accept(payload, player));
        });
    }
    private static <T extends CustomPacketPayload> void registerClientReceiver(NeroEconomyNetwork.Clientbound<T> value) {
        ClientPlayNetworking.registerGlobalReceiver(value.type(), (payload, context) ->
                context.client().execute(() -> value.handler().accept(payload)));
    }
    @Override public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
    @Override public void sendToServer(CustomPacketPayload payload) { ClientPlayNetworking.send(payload); }
}
