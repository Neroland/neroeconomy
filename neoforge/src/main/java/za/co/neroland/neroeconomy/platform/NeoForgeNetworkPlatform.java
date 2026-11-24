package za.co.neroland.neroeconomy.platform;

//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
//? if >=1.21.6 {
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
//?}
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;

public final class NeoForgeNetworkPlatform implements NetworkPlatform {
    @Override public void register(Object bus) { ((IEventBus) bus).addListener(this::onRegister); }
    @Override public void registerClient() { }
    private void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        NeroEconomyNetwork.clientbound().forEach(value -> registerClientbound(registrar, value));
        NeroEconomyNetwork.serverbound().forEach(value -> registerServerbound(registrar, value));
    }
    private static <T extends CustomPacketPayload> void registerClientbound(PayloadRegistrar registrar,
            NeroEconomyNetwork.Clientbound<T> value) {
        registrar.playToClient(value.type(), value.codec(),
                (payload, context) -> context.enqueueWork(() -> value.handler().accept(payload)));
    }
    private static <T extends CustomPacketPayload> void registerServerbound(PayloadRegistrar registrar,
            NeroEconomyNetwork.Serverbound<T> value) {
        registrar.playToServer(value.type(), value.codec(), (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) value.handler().accept(payload, player);
        }));
    }
    @Override public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
    @Override public void sendToServer(CustomPacketPayload payload) {
        //? if >=1.21.6 {
        ClientPacketDistributor.sendToServer(payload);
        //?} else {
        /*PacketDistributor.sendToServer(payload);
        *///?}
    }
}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.nerolandcore.network.legacy.LegacyPayload;
import za.co.neroland.nerolandcore.network.legacy.LegacyStreamCodec;

public final class NeoForgeNetworkPlatform implements NetworkPlatform {
    @Override public void register(Object bus) {
        ((IEventBus) bus).addListener(NeoForgeNetworkPlatform::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlerEvent event) {
        IPayloadRegistrar registrar = event.registrar(NeroEconomyCommon.MOD_ID).versioned("1").optional();
        NeroEconomyNetwork.clientbound().forEach(packet -> registerClientbound(registrar, packet));
        NeroEconomyNetwork.serverbound().forEach(packet -> registerServerbound(registrar, packet));
    }

    private static <T extends LegacyPayload> void registerClientbound(IPayloadRegistrar registrar,
            NeroEconomyNetwork.Clientbound<T> packet) {
        registrar.play(packet.type().id(),
                buffer -> new WirePayload<>(packet.type(), packet.codec(), packet.codec().decode(buffer)),
                builder -> {
                    builder.client((payload, context) ->
                            context.workHandler().execute(() -> packet.handler().accept(payload.message())));
                });
    }

    private static <T extends LegacyPayload> void registerServerbound(IPayloadRegistrar registrar,
            NeroEconomyNetwork.Serverbound<T> packet) {
        registrar.play(packet.type().id(),
                buffer -> new WirePayload<>(packet.type(), packet.codec(), packet.codec().decode(buffer)),
                builder -> {
                    builder.server((payload, context) -> context.workHandler().execute(() ->
                            context.player().ifPresent(player -> {
                                if (player instanceof ServerPlayer serverPlayer) {
                                    packet.handler().accept(payload.message(), serverPlayer);
                                }
                            })));
                });
    }

    @Override public void registerClient() { }

    @Override
    public void sendToPlayer(ServerPlayer player, LegacyPayload payload) {
        if (!NetworkRegistry.getInstance().isConnected(player.connection, payload.type().id())) {
            return;
        }
        for (NeroEconomyNetwork.Clientbound<?> packet : NeroEconomyNetwork.clientbound()) {
            if (packet.type().equals(payload.type())) {
                PacketDistributor.PLAYER.with(player).send(wrapClientbound(packet, payload));
                return;
            }
        }
        throw new IllegalArgumentException("Unregistered clientbound payload " + payload.type().id());
    }

    @Override
    public void sendToServer(LegacyPayload payload) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !NetworkRegistry.getInstance().isConnected(connection, payload.type().id())) {
            return;
        }
        for (NeroEconomyNetwork.Serverbound<?> packet : NeroEconomyNetwork.serverbound()) {
            if (packet.type().equals(payload.type())) {
                PacketDistributor.SERVER.noArg().send(wrapServerbound(packet, payload));
                return;
            }
        }
        throw new IllegalArgumentException("Unregistered serverbound payload " + payload.type().id());
    }

    private static <T extends LegacyPayload> WirePayload<T> wrapClientbound(NeroEconomyNetwork.Clientbound<T> packet,
            LegacyPayload payload) {
        return new WirePayload<>(packet.type(), packet.codec(), packet.type().payloadClass().cast(payload));
    }

    private static <T extends LegacyPayload> WirePayload<T> wrapServerbound(NeroEconomyNetwork.Serverbound<T> packet,
            LegacyPayload payload) {
        return new WirePayload<>(packet.type(), packet.codec(), packet.type().payloadClass().cast(payload));
    }

    private record WirePayload<T extends LegacyPayload>(LegacyPayload.Type<T> type,
            LegacyStreamCodec<? super FriendlyByteBuf, T> codec, T message)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        @Override
        public ResourceLocation id() {
            return type.id();
        }

        @Override
        public void write(FriendlyByteBuf buffer) {
            codec.encode(buffer, message);
        }
    }
}
*///?}
