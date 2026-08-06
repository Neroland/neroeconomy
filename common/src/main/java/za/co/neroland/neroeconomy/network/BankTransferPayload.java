package za.co.neroland.neroeconomy.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.BankTerminalMenu;

/** Idempotent bank transfer intent; target and amount are revalidated by the open server menu. */
public record BankTransferPayload(int containerId, UUID requestId, String target, long amount)
        implements CustomPacketPayload {
    public static final Type<BankTransferPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "bank_transfer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BankTransferPayload> STREAM_CODEC =
            StreamCodec.of(BankTransferPayload::write, BankTransferPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, BankTransferPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeUtf(value.target, 64);
        buf.writeVarLong(value.amount);
    }
    private static BankTransferPayload read(RegistryFriendlyByteBuf buf) {
        return new BankTransferPayload(buf.readVarInt(), buf.readUUID(), buf.readUtf(64), Math.max(0L, buf.readVarLong()));
    }
    public static void handle(BankTransferPayload value, ServerPlayer player) {
        if (player.containerMenu instanceof BankTerminalMenu menu && menu.containerId == value.containerId
                && menu.stillValid(player)) menu.transfer(player, value.requestId(), value.target(), value.amount());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
