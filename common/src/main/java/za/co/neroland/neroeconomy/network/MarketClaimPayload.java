package za.co.neroland.neroeconomy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * Collect durable item/proceeds claims at a valid open terminal. Carries no request id: collecting
 * is idempotent by nature, because a claim that has been paid out is no longer there to collect.
 */
public record MarketClaimPayload(int containerId) implements CustomPacketPayload {
    public static final Type<MarketClaimPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_claim"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketClaimPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> buf.writeVarInt(value.containerId),
                    buf -> new MarketClaimPayload(buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
