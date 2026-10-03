package za.co.neroland.neroeconomy.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.obligation.JobState;

/** Server snapshot for terminal jobs/bounties/auctions tabs. */
public record OpportunityPagePayload(int containerId, int revision, Tab tab, List<Line> lines)
        implements CustomPacketPayload {
    public enum Tab { CONTRACTS, DELIVERIES, BOUNTIES, AUCTIONS }
    /**
     * One row.
     *
     * @param target          an item, tag or entity id, or one of the fixed player-bounty tokens; never a player id
     * @param amount          a job's payout, or an auction's standing bid (0 while it has none)
     * @param nextBid         the lowest bid the auction accepts next, as the server computes it; 0 for a job
     * @param remainingMillis time left when the page was built. A duration, not a timestamp: the
     *                        client counts down from when the page arrived, so neither clock matters
     * @param state           sent as its ordinal and shown through its lang key, never by name
     */
    public record Line(UUID id, String target, long amount, long nextBid, long remainingMillis, JobState state) { }
    private static final int MAX_TARGET = 128;
    public static final Type<OpportunityPagePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "opportunity_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpportunityPagePayload> STREAM_CODEC =
            StreamCodec.of(OpportunityPagePayload::write, OpportunityPagePayload::read);
    private static void write(RegistryFriendlyByteBuf buf, OpportunityPagePayload value) {
        buf.writeVarInt(value.containerId); buf.writeVarInt(value.revision); buf.writeByte(value.tab.ordinal());
        buf.writeVarInt(Math.min(20, value.lines.size()));
        for (int i = 0; i < Math.min(20, value.lines.size()); i++) {
            // Clipped, never trusted: one over-long stored target must not fail the whole page.
            Line line = value.lines.get(i); buf.writeUUID(line.id);
            buf.writeUtf(NeroEconomyNetwork.clip(line.target, MAX_TARGET), MAX_TARGET);
            buf.writeVarLong(Math.max(0L, line.amount)); buf.writeVarLong(Math.max(0L, line.nextBid));
            buf.writeVarLong(Math.max(0L, line.remainingMillis)); buf.writeByte(line.state.ordinal());
        }
    }
    private static OpportunityPagePayload read(RegistryFriendlyByteBuf buf) {
        int container = buf.readVarInt(), revision = buf.readVarInt();
        Tab tab = Tab.values()[Math.min(buf.readUnsignedByte(), Tab.values().length - 1)];
        JobState[] states = JobState.values();
        int count = Math.min(20, Math.max(0, buf.readVarInt())); List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) lines.add(new Line(buf.readUUID(), buf.readUtf(MAX_TARGET),
                Math.max(0L, buf.readVarLong()), Math.max(0L, buf.readVarLong()), Math.max(0L, buf.readVarLong()),
                states[Math.min(buf.readUnsignedByte(), states.length - 1)]));
        return new OpportunityPagePayload(container, revision, tab, lines);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
