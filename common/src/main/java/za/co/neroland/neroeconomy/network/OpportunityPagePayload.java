package za.co.neroland.neroeconomy.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Server snapshot for terminal jobs/bounties/auctions tabs. */
public record OpportunityPagePayload(int containerId, int revision, Tab tab, List<Line> lines)
        implements CustomPacketPayload {
    public enum Tab { CONTRACTS, DELIVERIES, BOUNTIES, AUCTIONS }
    public record Line(UUID id, String target, long amount, long endsAt, String state) { }
    public static final Type<OpportunityPagePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "opportunity_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpportunityPagePayload> STREAM_CODEC =
            StreamCodec.of(OpportunityPagePayload::write, OpportunityPagePayload::read);
    private static void write(RegistryFriendlyByteBuf buf, OpportunityPagePayload value) {
        buf.writeVarInt(value.containerId); buf.writeVarInt(value.revision); buf.writeByte(value.tab.ordinal());
        buf.writeVarInt(Math.min(20, value.lines.size()));
        for (int i = 0; i < Math.min(20, value.lines.size()); i++) {
            Line line = value.lines.get(i); buf.writeUUID(line.id); buf.writeUtf(line.target, 128);
            buf.writeVarLong(line.amount); buf.writeVarLong(line.endsAt); buf.writeUtf(line.state, 24);
        }
    }
    private static OpportunityPagePayload read(RegistryFriendlyByteBuf buf) {
        int container = buf.readVarInt(), revision = buf.readVarInt();
        Tab tab = Tab.values()[Math.min(buf.readUnsignedByte(), Tab.values().length - 1)];
        int count = Math.min(20, Math.max(0, buf.readVarInt())); List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) lines.add(new Line(buf.readUUID(), buf.readUtf(128),
                Math.max(0L, buf.readVarLong()), Math.max(0L, buf.readVarLong()), buf.readUtf(24)));
        return new OpportunityPagePayload(container, revision, tab, lines);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
