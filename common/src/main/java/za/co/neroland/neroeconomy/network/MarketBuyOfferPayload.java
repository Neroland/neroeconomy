package za.co.neroland.neroeconomy.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;

/** Board-only intent to escrow credits for a standing buy offer. */
public record MarketBuyOfferPayload(int containerId, UUID requestId, Identifier itemId, int quantity, long unitPrice)
        implements CustomPacketPayload {
    public static final Type<MarketBuyOfferPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_buy_offer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketBuyOfferPayload> STREAM_CODEC =
            StreamCodec.of(MarketBuyOfferPayload::write, MarketBuyOfferPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketBuyOfferPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeIdentifier(value.itemId);
        buf.writeVarInt(value.quantity); buf.writeVarLong(value.unitPrice);
    }
    private static MarketBuyOfferPayload read(RegistryFriendlyByteBuf buf) {
        return new MarketBuyOfferPayload(buf.readVarInt(), buf.readUUID(), buf.readIdentifier(),
                Math.max(1, Math.min(2304, buf.readVarInt())), Math.max(1L, buf.readVarLong()));
    }
    public static void handle(MarketBuyOfferPayload value, ServerPlayer player) {
        if (player.containerMenu instanceof TradingTerminalMenu menu && menu.containerId == value.containerId
                && menu.stillValid(player)) menu.postBuyOffer(player, value);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
