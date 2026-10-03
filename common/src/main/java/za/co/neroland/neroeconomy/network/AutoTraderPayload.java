package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * What the rule editor of an Auto-Trader sends. Everything in it is re-validated by the open
 * server menu and by the rules section, and its request id is recorded in the shared request
 * ledger, so a replay does nothing. The rule's fields are only read for {@link Action#APPLY}.
 *
 * @param buy    whether the rule buys; otherwise it sells
 * @param tag    whether {@code target} is an item tag (without the leading {@code #})
 * @param target the item id or tag id
 * @param total  a buy rule's budget in credits; a sell rule's quantity
 */
public record AutoTraderPayload(int containerId, UUID requestId, Action action, boolean buy, boolean tag,
        String target, long limitPrice, int perDay, long total) implements CustomPacketPayload {
    public enum Action {
        /** Set the rule to the fields carried, replacing the one there is. */
        APPLY,
        PAUSE,
        RESUME,
        /** Delete the rule: its budget and its unsold goods come back. */
        DELETE
    }

    /** The longest item or tag id the editor sends: the same bound a lot's item id has. */
    public static final int MAX_TARGET_LENGTH = 256;

    public static final Type<AutoTraderPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "auto_trader"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AutoTraderPayload> STREAM_CODEC =
            StreamCodec.of(AutoTraderPayload::write, AutoTraderPayload::read);

    /** An intent that carries no rule: pause, resume or delete. */
    public static AutoTraderPayload of(int containerId, UUID requestId, Action action) {
        return new AutoTraderPayload(containerId, requestId, action, false, false, "", 0L, 0, 0L);
    }

    private static void write(RegistryFriendlyByteBuf buf, AutoTraderPayload value) {
        buf.writeVarInt(value.containerId);
        buf.writeUUID(value.requestId);
        buf.writeVarInt(value.action == null ? -1 : value.action.ordinal());
        buf.writeBoolean(value.buy);
        buf.writeBoolean(value.tag);
        buf.writeUtf(NeroEconomyNetwork.clip(value.target, MAX_TARGET_LENGTH), MAX_TARGET_LENGTH);
        buf.writeVarLong(value.limitPrice);
        buf.writeVarInt(value.perDay);
        buf.writeVarLong(value.total);
    }

    private static AutoTraderPayload read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID requestId = buf.readUUID();
        int action = buf.readVarInt();
        // An action this build does not know is carried as none, and the menu then does nothing.
        Action[] actions = Action.values();
        return new AutoTraderPayload(containerId, requestId, action >= 0 && action < actions.length ? actions[action]
                : null, buf.readBoolean(), buf.readBoolean(), buf.readUtf(MAX_TARGET_LENGTH), buf.readVarLong(),
                buf.readVarInt(), buf.readVarLong());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
