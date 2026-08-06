package za.co.neroland.neroeconomy.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Loader implementation of NeroEconomy's private {@code neroeconomy:main} channel. */
public interface NetworkPlatform {
    void register(Object loaderBus);
    void registerClient();
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
    void sendToServer(CustomPacketPayload payload);
}
