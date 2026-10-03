package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * Idempotent buy intent. The client price is only a ceiling the buyer agreed to and the item id is
 * only what the buyer was shown; neither is authority — the server refuses the purchase if the
 * listing now costs more or sells something else.
 */
public record MarketBuyPayload(int containerId, UUID requestId, UUID listingId, int quantity, long quotedUnitPrice,
        Identifier itemId) implements CustomPacketPayload {
    public static final Type<MarketBuyPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_buy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketBuyPayload> STREAM_CODEC =
            StreamCodec.of(MarketBuyPayload::write, MarketBuyPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketBuyPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeUUID(value.listingId);
        buf.writeVarInt(value.quantity); buf.writeVarLong(value.quotedUnitPrice); buf.writeIdentifier(value.itemId);
    }
    private static MarketBuyPayload read(RegistryFriendlyByteBuf buf) {
        return new MarketBuyPayload(buf.readVarInt(), buf.readUUID(), buf.readUUID(),
                Math.max(1, Math.min(64, buf.readVarInt())), Math.max(1L, buf.readVarLong()), buf.readIdentifier());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
