package za.co.neroland.neroeconomy.platform;

//? if >=1.20.5 {
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
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
//? if >=1.20.2 {
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;
//?} else {
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
//?}

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.nerolandcore.network.legacy.LegacyPayload;

public final class ForgeNetworkPlatform implements NetworkPlatform {
    private static final int PROTOCOL_VERSION = 1;
    private static SimpleChannel channel;

    @Override public void register(Object ignored) {
        //? if >=1.20.2 {
        channel = ChannelBuilder.named(new ResourceLocation(NeroEconomyCommon.MOD_ID, "main"))
                .networkProtocolVersion(PROTOCOL_VERSION).optional().simpleChannel();
        //?} else {
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(NeroEconomyCommon.MOD_ID, "main"),
                () -> Integer.toString(PROTOCOL_VERSION), ForgeNetworkPlatform::acceptVersion, ForgeNetworkPlatform::acceptVersion);
        //?}
        int id = 0;
        for (NeroEconomyNetwork.Clientbound<?> packet : NeroEconomyNetwork.clientbound()) {
            registerClientbound(packet, id++);
        }
        for (NeroEconomyNetwork.Serverbound<?> packet : NeroEconomyNetwork.serverbound()) {
            registerServerbound(packet, id++);
        }
    }

    //? if <1.20.2 {
    private static boolean acceptVersion(String version) {
        return Integer.toString(PROTOCOL_VERSION).equals(version) || NetworkRegistry.ABSENT.version().equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }
    //?}

    private static <T extends LegacyPayload> void registerClientbound(NeroEconomyNetwork.Clientbound<T> packet, int id) {
        channel.messageBuilder(packet.type().payloadClass(), id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((payload, buffer) -> packet.codec().encode(buffer, payload))
                .decoder(packet.codec()::decode)
                .consumerMainThread((payload, context) -> packet.handler().accept(payload))
                .add();
    }

    private static <T extends LegacyPayload> void registerServerbound(NeroEconomyNetwork.Serverbound<T> packet, int id) {
        channel.messageBuilder(packet.type().payloadClass(), id, NetworkDirection.PLAY_TO_SERVER)
                .encoder((payload, buffer) -> packet.codec().encode(buffer, payload))
                .decoder(packet.codec()::decode)
                .consumerMainThread((payload, context) -> {
                    //? if >=1.20.2 {
                    ServerPlayer player = context.getSender();
                    //?} else {
                    ServerPlayer player = context.get().getSender();
                    //?}
                    if (player != null) {
                        packet.handler().accept(payload, player);
                    }
                })
                .add();
    }

    @Override public void registerClient() { }

    @Override
    public void sendToPlayer(ServerPlayer player, LegacyPayload payload) {
        //? if >=1.20.2 {
        var connection = player.connection.getConnection();
        //?} else {
        var connection = player.connection.connection;
        //?}
        if (channel != null && channel.isRemotePresent(connection)) {
            //? if >=1.20.2 {
            channel.send(payload, connection);
            //?} else {
            channel.send(PacketDistributor.PLAYER.with(() -> player), payload);
            //?}
        }
    }

    @Override
    public void sendToServer(LegacyPayload payload) {
        var connection = Minecraft.getInstance().getConnection();
        if (channel != null && connection != null && channel.isRemotePresent(connection.getConnection())) {
            //? if >=1.20.2 {
            channel.send(payload, connection.getConnection());
            //?} else {
            channel.sendToServer(payload);
            //?}
        }
    }
}
*///?}
