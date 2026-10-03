package za.co.neroland.neroeconomy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * Client intent to query a page. Query length and page are bounded server-side. A query moves
 * nothing, so it carries no request id: asking twice simply answers twice.
 */
public record MarketQueryPayload(int containerId, String query, int page) implements CustomPacketPayload {
    public static final Type<MarketQueryPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_query"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketQueryPayload> STREAM_CODEC =
            StreamCodec.of(MarketQueryPayload::write, MarketQueryPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketQueryPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUtf(NeroEconomyNetwork.clip(value.query, 64), 64);
        buf.writeVarInt(value.page);
    }
    private static MarketQueryPayload read(RegistryFriendlyByteBuf buf) {
        return new MarketQueryPayload(buf.readVarInt(), buf.readUtf(64),
                Math.max(0, Math.min(10000, buf.readVarInt())));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
