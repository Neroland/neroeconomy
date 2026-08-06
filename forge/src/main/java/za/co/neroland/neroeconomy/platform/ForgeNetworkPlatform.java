package za.co.neroland.neroeconomy.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.payload.PayloadFlow;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;

public final class ForgeNetworkPlatform implements NetworkPlatform {
    private static Channel<CustomPacketPayload> channel;
    @Override public void register(Object ignored) {
        PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> flow = ChannelBuilder
                .named(Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "main"))
                .optional().payloadChannel().play().bidirectional();
        NeroEconomyNetwork.clientbound().forEach(value -> registerClientbound(flow, value));
        NeroEconomyNetwork.serverbound().forEach(value -> registerServerbound(flow, value));
        channel = flow.build();
    }
    @Override public void registerClient() { }
    private static <T extends CustomPacketPayload> void registerClientbound(
            PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> flow,
            NeroEconomyNetwork.Clientbound<T> value) {
        flow.addMain(value.type(), registryCodec(value.codec()), (payload, context) -> {
            if (context.isClientSide()) value.handler().accept(payload);
        });
    }
    private static <T extends CustomPacketPayload> void registerServerbound(
            PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> flow,
            NeroEconomyNetwork.Serverbound<T> value) {
        flow.addMain(value.type(), registryCodec(value.codec()), (payload, context) -> {
            if (context.getSender() instanceof ServerPlayer player) value.handler().accept(payload, player);
        });
    }
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> registryCodec(
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return (StreamCodec<RegistryFriendlyByteBuf, T>) codec;
    }
    @Override public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (channel != null) channel.send(payload, PacketDistributor.PLAYER.with(player));
    }
    @Override public void sendToServer(CustomPacketPayload payload) {
        if (channel != null) channel.send(payload, PacketDistributor.SERVER.noArg());
    }
}
