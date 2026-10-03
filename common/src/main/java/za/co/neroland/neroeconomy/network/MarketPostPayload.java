package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Board-only intent to escrow held goods into a sell offer. */
public record MarketPostPayload(int containerId, UUID requestId, int quantity, long unitPrice)
        implements CustomPacketPayload {
    public static final Type<MarketPostPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_post"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketPostPayload> STREAM_CODEC =
            StreamCodec.of(MarketPostPayload::write, MarketPostPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketPostPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeVarInt(value.quantity);
        buf.writeVarLong(value.unitPrice);
    }
    private static MarketPostPayload read(RegistryFriendlyByteBuf buf) {
        return new MarketPostPayload(buf.readVarInt(), buf.readUUID(), Math.max(1, Math.min(64, buf.readVarInt())),
                Math.max(1L, buf.readVarLong()));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
