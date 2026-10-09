package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubRouter;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * The one intent every Economy Hub tab sends: an action on a tab, perhaps on a row of the page the
 * player was sent, with two numbers and one bounded string. Everything in it is checked again by
 * the open hub ({@link HubRouter}); the request id makes a value-moving intent act at most once.
 * An action or tab this build does not know is carried as none, and the hub then does nothing.
 *
 * @param target a row id from the last page, or null
 */
public record HubIntentPayload(int containerId, UUID requestId, HubTab tab, HubAction action, UUID target, long a,
        long b, String text) implements CustomPacketPayload {
    public static final Type<HubIntentPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "hub_intent"));
    public static final StreamCodec<FriendlyByteBuf, HubIntentPayload> STREAM_CODEC =
            StreamCodec.of(HubIntentPayload::write, HubIntentPayload::read);

    public static void write(FriendlyByteBuf buf, HubIntentPayload value) {
        buf.writeVarInt(value.containerId);
        buf.writeUUID(value.requestId == null ? new UUID(0L, 0L) : value.requestId);
        buf.writeVarInt(value.tab == null ? -1 : value.tab.ordinal());
        buf.writeVarInt(value.action == null ? -1 : value.action.ordinal());
        buf.writeBoolean(value.target != null);
        if (value.target != null) buf.writeUUID(value.target);
        buf.writeVarLong(value.a);
        buf.writeVarLong(value.b);
        buf.writeUtf(NeroEconomyNetwork.clip(value.text, HubRouter.MAX_TEXT), HubRouter.MAX_TEXT);
    }

    public static HubIntentPayload read(FriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID requestId = buf.readUUID();
        HubTab tab = HubTab.byOrdinal(buf.readVarInt());
        HubAction action = HubAction.byOrdinal(buf.readVarInt());
        UUID target = buf.readBoolean() ? buf.readUUID() : null;
        return new HubIntentPayload(containerId, requestId, tab, action, target, buf.readVarLong(), buf.readVarLong(),
                buf.readUtf(HubRouter.MAX_TEXT));
    }

    /** The intent as the router reads it. */
    public HubRouter.Intent intent() {
        return new HubRouter.Intent(requestId, tab, action, target, a, b, text);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
