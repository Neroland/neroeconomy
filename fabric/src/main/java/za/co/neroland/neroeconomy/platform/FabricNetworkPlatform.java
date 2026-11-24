package za.co.neroland.neroeconomy.platform;

//? if >=1.20.5 {
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
//?} else {
/*import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.nerolandcore.network.legacy.LegacyPayload;
import za.co.neroland.nerolandcore.network.legacy.LegacyStreamCodec;

public final class FabricNetworkPlatform implements NetworkPlatform {
    @Override
    public void register(Object ignored) {
        NeroEconomyNetwork.serverbound().forEach(FabricNetworkPlatform::registerServerbound);
    }

    @Override
    public void registerClient() {
        NeroEconomyNetwork.clientbound().forEach(FabricNetworkPlatform::registerClientbound);
    }

    private static <T extends LegacyPayload> void registerServerbound(NeroEconomyNetwork.Serverbound<T> packet) {
        ServerPlayNetworking.registerGlobalReceiver(packet.type().id(), (server, player, handler, buffer, sender) -> {
            T message = packet.codec().decode(buffer);
            server.execute(() -> packet.handler().accept(message, player));
        });
    }

    private static <T extends LegacyPayload> void registerClientbound(NeroEconomyNetwork.Clientbound<T> packet) {
        ClientPlayNetworking.registerGlobalReceiver(packet.type().id(), (client, handler, buffer, sender) -> {
            T message = packet.codec().decode(buffer);
            client.execute(() -> packet.handler().accept(message));
        });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, LegacyPayload payload) {
        var id = payload.type().id();
        if (!ServerPlayNetworking.canSend(player, id)) {
            return;
        }
        for (var packet : NeroEconomyNetwork.clientbound()) {
            if (packet.type().equals(payload.type())) {
                ServerPlayNetworking.send(player, id, encodeClientbound(packet, payload));
                return;
            }
        }
        throw new IllegalArgumentException("Unregistered clientbound payload " + id);
    }

    @Override
    public void sendToServer(LegacyPayload payload) {
        var id = payload.type().id();
        if (!ClientPlayNetworking.canSend(id)) {
            return;
        }
        for (var packet : NeroEconomyNetwork.serverbound()) {
            if (packet.type().equals(payload.type())) {
                ClientPlayNetworking.send(id, encodeServerbound(packet, payload));
                return;
            }
        }
        throw new IllegalArgumentException("Unregistered serverbound payload " + id);
    }

    private static <T extends LegacyPayload> FriendlyByteBuf encodeClientbound(
            NeroEconomyNetwork.Clientbound<T> packet, LegacyPayload payload) {
        return encode(packet.type(), packet.codec(), payload);
    }

    private static <T extends LegacyPayload> FriendlyByteBuf encodeServerbound(
            NeroEconomyNetwork.Serverbound<T> packet, LegacyPayload payload) {
        return encode(packet.type(), packet.codec(), payload);
    }

    private static <T extends LegacyPayload> FriendlyByteBuf encode(LegacyPayload.Type<T> type,
            LegacyStreamCodec<? super FriendlyByteBuf, T> codec, LegacyPayload payload) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        codec.encode(buffer, type.payloadClass().cast(payload));
        return buffer;
    }
}
*///?}
