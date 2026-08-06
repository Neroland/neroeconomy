package za.co.neroland.neroeconomy.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
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
    @Override public void sendToServer(CustomPacketPayload payload) { ClientPacketDistributor.sendToServer(payload); }
}
