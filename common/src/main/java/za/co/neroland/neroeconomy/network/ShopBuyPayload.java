package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;

/**
 * Idempotent player-shop buy intent. The request id is generated on the client so a retried click
 * settles once, and the quoted price is only a stale-quote guard — the server re-prices at execution.
 */
public record ShopBuyPayload(int containerId, UUID requestId, long quotedUnitPrice)
        implements CustomPacketPayload {
    public static final Type<ShopBuyPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "shop_buy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShopBuyPayload> STREAM_CODEC =
            StreamCodec.of(ShopBuyPayload::write, ShopBuyPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, ShopBuyPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeVarLong(value.quotedUnitPrice);
    }
    private static ShopBuyPayload read(RegistryFriendlyByteBuf buf) {
        return new ShopBuyPayload(buf.readVarInt(), buf.readUUID(), Math.max(1L, buf.readVarLong()));
    }
    public static void handle(ShopBuyPayload value, ServerPlayer player) {
        if (player.containerMenu instanceof PlayerShopMenu menu && menu.containerId == value.containerId
                && menu.stillValid(player)) menu.buy(player, value);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
