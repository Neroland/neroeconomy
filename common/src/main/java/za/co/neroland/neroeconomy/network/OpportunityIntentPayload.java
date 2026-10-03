package za.co.neroland.neroeconomy.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * Terminal tab query or server-validated action. Every action other than {@link Action#QUERY} is
 * recorded against {@code requestId} in the shared request ledger before it runs, so a replayed
 * packet acts once; a query ignores the id.
 */
public record OpportunityIntentPayload(int containerId, UUID requestId, OpportunityPagePayload.Tab tab,
        Action action, String objectId, long amount) implements CustomPacketPayload {
    /** Constants are only ever appended: the ordinal is the wire form. */
    public enum Action {
        QUERY, CLAIM, FULFILL, BID, RELEASE;

        /** Whether the action changes an obligation, and so must be idempotent and tightly budgeted. */
        public boolean moves() { return this != QUERY; }
    }
    public static final Type<OpportunityIntentPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "opportunity_intent"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpportunityIntentPayload> STREAM_CODEC =
            StreamCodec.of(OpportunityIntentPayload::write, OpportunityIntentPayload::read);
    private static void write(RegistryFriendlyByteBuf buf, OpportunityIntentPayload value) {
        buf.writeVarInt(value.containerId); buf.writeUUID(value.requestId); buf.writeByte(value.tab.ordinal());
        buf.writeByte(value.action.ordinal()); buf.writeUtf(NeroEconomyNetwork.clip(value.objectId, 36), 36);
        buf.writeVarLong(value.amount);
    }
    private static OpportunityIntentPayload read(RegistryFriendlyByteBuf buf) {
        int container = buf.readVarInt(); UUID request = buf.readUUID();
        var tabs = OpportunityPagePayload.Tab.values(); var actions = Action.values();
        // An ordinal this build does not know is read as a query, the one action that moves nothing.
        int tab = buf.readUnsignedByte(); int action = buf.readUnsignedByte();
        return new OpportunityIntentPayload(container, request, tabs[Math.min(tab, tabs.length - 1)],
                action < actions.length ? actions[action] : Action.QUERY, buf.readUtf(36),
                Math.max(0L, buf.readVarLong()));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
