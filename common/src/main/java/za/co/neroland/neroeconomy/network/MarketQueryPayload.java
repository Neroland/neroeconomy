package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;

/** Client intent to query a page. Query length and page are bounded server-side. */
public record MarketQueryPayload(int containerId, UUID requestId, String query, int page)
        implements CustomPacketPayload {
    public static final Type<MarketQueryPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_query"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketQueryPayload> STREAM_CODEC =
            StreamCodec.of(MarketQueryPayload::write, MarketQueryPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketQueryPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeUtf(value.query, 64);
        buf.writeVarInt(value.page);
    }
    private static MarketQueryPayload read(RegistryFriendlyByteBuf buf) {
        return new MarketQueryPayload(buf.readVarInt(), buf.readUUID(), buf.readUtf(64),
                Math.max(0, Math.min(10000, buf.readVarInt())));
    }
    public static void handle(MarketQueryPayload value, ServerPlayer player) {
        if (player.containerMenu instanceof TradingTerminalMenu menu
                && menu.containerId == value.containerId && menu.stillValid(player)) menu.sendPage(value.query, value.page);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
