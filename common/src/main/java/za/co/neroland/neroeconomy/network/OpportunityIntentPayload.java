package za.co.neroland.neroeconomy.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;

/** Terminal tab query or server-validated action. */
public record OpportunityIntentPayload(int containerId, UUID requestId, OpportunityPagePayload.Tab tab,
        Action action, String objectId, long amount) implements CustomPacketPayload {
    public enum Action { QUERY, CLAIM, FULFILL, BID }
    public static final Type<OpportunityIntentPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "opportunity_intent"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpportunityIntentPayload> STREAM_CODEC =
            StreamCodec.of(OpportunityIntentPayload::write, OpportunityIntentPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, OpportunityIntentPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeByte(value.tab.ordinal());
        buf.writeByte(value.action.ordinal()); buf.writeUtf(value.objectId, 36); buf.writeVarLong(value.amount);
    }
    private static OpportunityIntentPayload read(RegistryFriendlyByteBuf buf) {
        int container = buf.readVarInt(); UUID request = buf.readUUID();
        var tabs = OpportunityPagePayload.Tab.values(); var actions = Action.values();
        return new OpportunityIntentPayload(container, request, tabs[Math.min(buf.readUnsignedByte(), tabs.length - 1)],
                actions[Math.min(buf.readUnsignedByte(), actions.length - 1)], buf.readUtf(36),
                Math.max(0L, buf.readVarLong()));
    }
    public static void handle(OpportunityIntentPayload value, ServerPlayer player) {
        if (player.containerMenu instanceof TradingTerminalMenu menu && menu.containerId == value.containerId
                && menu.stillValid(player)) menu.handleOpportunity(player, value);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
