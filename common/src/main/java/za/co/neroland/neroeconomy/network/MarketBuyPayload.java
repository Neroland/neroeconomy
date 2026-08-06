package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;

/** Idempotent buy intent. Client price is only a stale-quote guard, never authority. */
public record MarketBuyPayload(int containerId, UUID requestId, UUID listingId, int quantity, long quotedUnitPrice)
        implements CustomPacketPayload {
    public static final Type<MarketBuyPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_buy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketBuyPayload> STREAM_CODEC =
            StreamCodec.of(MarketBuyPayload::write, MarketBuyPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketBuyPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeUUID(value.listingId);
        buf.writeVarInt(value.quantity); buf.writeVarLong(value.quotedUnitPrice);
    }
    private static MarketBuyPayload read(RegistryFriendlyByteBuf buf) {
        return new MarketBuyPayload(buf.readVarInt(), buf.readUUID(), buf.readUUID(),
                Math.max(1, Math.min(64, buf.readVarInt())), Math.max(1L, buf.readVarLong()));
    }
    public static void handle(MarketBuyPayload value, ServerPlayer player) {
        if (player.containerMenu instanceof TradingTerminalMenu menu && menu.containerId == value.containerId
                && menu.stillValid(player)) menu.buy(player, value);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
