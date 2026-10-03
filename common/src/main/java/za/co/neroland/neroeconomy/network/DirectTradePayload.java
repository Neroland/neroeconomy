package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * What the controls of a direct trade window send. Every one is re-validated by the open server
 * menu, and its request id is recorded in the shared request ledger, so a replay does nothing.
 * Cancelling needs no intent: it is closing the window.
 *
 * @param value the credit amount for {@link Action#SET_CREDITS}; the revision the player was
 *              looking at for {@link Action#CONFIRM}; unused otherwise
 */
public record DirectTradePayload(int containerId, UUID requestId, Action action, long value)
        implements CustomPacketPayload {
    public enum Action {
        /** Set the credits this player gives. */
        SET_CREDITS,
        /** Confirm the trade as it stands at the revision named. */
        CONFIRM,
        /** Take a confirmation back. */
        WITHDRAW
    }

    public static final Type<DirectTradePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "direct_trade"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DirectTradePayload> STREAM_CODEC =
            StreamCodec.of(DirectTradePayload::write, DirectTradePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, DirectTradePayload value) {
        buf.writeVarInt(value.containerId);
        buf.writeUUID(value.requestId);
        buf.writeVarInt(value.action == null ? -1 : value.action.ordinal());
        buf.writeVarLong(value.value);
    }

    private static DirectTradePayload read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID requestId = buf.readUUID();
        int action = buf.readVarInt();
        // An action this build does not know is carried as none, and the menu then does nothing.
        Action[] actions = Action.values();
        return new DirectTradePayload(containerId, requestId, action >= 0 && action < actions.length ? actions[action]
                : null, buf.readVarLong());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
