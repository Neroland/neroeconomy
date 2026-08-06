package za.co.neroland.neroeconomy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;
import za.co.neroland.neroeconomy.market.MarketSavedData;

/** Collect durable item/proceeds claims at a valid open terminal. */
public record MarketClaimPayload(int containerId) implements CustomPacketPayload {
    public static final Type<MarketClaimPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_claim"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketClaimPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> buf.writeVarInt(value.containerId),
                    buf -> new MarketClaimPayload(buf.readVarInt()));
    public static void handle(MarketClaimPayload value, ServerPlayer player) {
        if (player.containerMenu instanceof TradingTerminalMenu menu && menu.containerId == value.containerId
                && menu.stillValid(player)) {
            MarketSavedData store = MarketSavedData.get(player.level().getServer());
            store.collectCredits(player.getUUID()); store.collectItems(player);
        }
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
