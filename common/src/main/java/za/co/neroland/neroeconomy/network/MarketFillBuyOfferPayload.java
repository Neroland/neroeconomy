package za.co.neroland.neroeconomy.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Idempotent intent to fill a credits-backed buy offer from the player's main hand. */
public record MarketFillBuyOfferPayload(int containerId, UUID requestId, UUID offerId, int quantity)
        implements CustomPacketPayload {
    public static final Type<MarketFillBuyOfferPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_fill_buy_offer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketFillBuyOfferPayload> STREAM_CODEC =
            StreamCodec.of(MarketFillBuyOfferPayload::write, MarketFillBuyOfferPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketFillBuyOfferPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeUUID(value.offerId);
        buf.writeVarInt(value.quantity);
    }
    private static MarketFillBuyOfferPayload read(RegistryFriendlyByteBuf buf) {
        return new MarketFillBuyOfferPayload(buf.readVarInt(), buf.readUUID(), buf.readUUID(),
                Math.max(1, Math.min(2304, buf.readVarInt())));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
