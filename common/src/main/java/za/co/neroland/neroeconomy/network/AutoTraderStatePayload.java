package za.co.neroland.neroeconomy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * Server truth about the rule of the Auto-Trader a player has open: sent to its owner only, when
 * the screen opens and whenever anything in it changes. It names no player and carries nothing
 * but the owner's own rule.
 *
 * @param hasRule     whether the Auto-Trader has a rule; when not, every other field is empty
 * @param tag         whether {@code target} is an item tag (without the leading {@code #})
 * @param total       a buy rule's budget in credits; a sell rule's quantity
 * @param done        a buy rule: credits spent so far; a sell rule: how many sold so far
 * @param filledToday how many it has traded in the current economy-day
 * @param status      the ordinal of the rule's {@code RuleStatus}
 * @param budget      a buy rule: credits still held in escrow for it and not committed to an order
 * @param valueToday  what all of the owner's rules have traded today, in credits
 * @param valueCap    {@code ruleDailyValueCap}
 */
public record AutoTraderStatePayload(int containerId, boolean hasRule, boolean buys, boolean tag, String target,
        long limitPrice, int perDay, long total, long done, int filledToday, int status, boolean paused, long budget,
        long valueToday, long valueCap) implements CustomPacketPayload {
    public static final Type<AutoTraderStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "auto_trader_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AutoTraderStatePayload> STREAM_CODEC =
            StreamCodec.of(AutoTraderStatePayload::write, AutoTraderStatePayload::read);

    /** The state of an Auto-Trader that has no rule. */
    public static AutoTraderStatePayload none(int containerId, long valueToday, long valueCap) {
        return new AutoTraderStatePayload(containerId, false, false, false, "", 0L, 0, 0L, 0L, 0, 0, false, 0L,
                valueToday, valueCap);
    }

    private static void write(RegistryFriendlyByteBuf buf, AutoTraderStatePayload value) {
        buf.writeVarInt(value.containerId);
        buf.writeBoolean(value.hasRule);
        buf.writeBoolean(value.buys);
        buf.writeBoolean(value.tag);
        buf.writeUtf(NeroEconomyNetwork.clip(value.target, AutoTraderPayload.MAX_TARGET_LENGTH),
                AutoTraderPayload.MAX_TARGET_LENGTH);
        buf.writeVarLong(value.limitPrice);
        buf.writeVarInt(value.perDay);
        buf.writeVarLong(value.total);
        buf.writeVarLong(value.done);
        buf.writeVarInt(value.filledToday);
        buf.writeVarInt(value.status);
        buf.writeBoolean(value.paused);
        buf.writeVarLong(value.budget);
        buf.writeVarLong(value.valueToday);
        buf.writeVarLong(value.valueCap);
    }

    private static AutoTraderStatePayload read(RegistryFriendlyByteBuf buf) {
        return new AutoTraderStatePayload(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readUtf(AutoTraderPayload.MAX_TARGET_LENGTH), Math.max(0L, buf.readVarLong()),
                Math.max(0, buf.readVarInt()), Math.max(0L, buf.readVarLong()), Math.max(0L, buf.readVarLong()),
                Math.max(0, buf.readVarInt()), Math.max(0, buf.readVarInt()), buf.readBoolean(),
                Math.max(0L, buf.readVarLong()), Math.max(0L, buf.readVarLong()), Math.max(0L, buf.readVarLong()));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
