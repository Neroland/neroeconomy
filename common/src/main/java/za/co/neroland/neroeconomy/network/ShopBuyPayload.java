package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * Idempotent player-shop buy intent. The request id is generated on the client so a retried click
 * settles once. The quoted price is only the ceiling the buyer agreed to — the server re-prices at
 * execution — and the item id is the item the buyer was shown, so a shop restocked with something
 * else between look and click is refused instead of sold.
 */
public record ShopBuyPayload(int containerId, UUID requestId, long quotedUnitPrice, Identifier itemId)
        implements CustomPacketPayload {
    public static final Type<ShopBuyPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "shop_buy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShopBuyPayload> STREAM_CODEC =
            StreamCodec.of(ShopBuyPayload::write, ShopBuyPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, ShopBuyPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeVarLong(value.quotedUnitPrice);
        buf.writeIdentifier(value.itemId);
    }
    private static ShopBuyPayload read(RegistryFriendlyByteBuf buf) {
        return new ShopBuyPayload(buf.readVarInt(), buf.readUUID(), Math.max(1L, buf.readVarLong()), buf.readIdentifier());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
