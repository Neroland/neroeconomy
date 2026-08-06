package za.co.neroland.neroeconomy.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Server truth for one terminal results page. */
public record MarketPagePayload(int containerId, int revision, int page, int total, List<Line> lines)
        implements CustomPacketPayload {
    public record Line(UUID listingId, Identifier itemId, int stock, long unitPrice, boolean buyOffer) { }
    public static final Type<MarketPagePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "market_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketPagePayload> STREAM_CODEC =
            StreamCodec.of(MarketPagePayload::write, MarketPagePayload::read);
    private static void write(RegistryFriendlyByteBuf buf, MarketPagePayload value) {
        buf.writeVarInt(value.containerId); buf.writeVarInt(value.revision); buf.writeVarInt(value.page);
        buf.writeVarInt(value.total); buf.writeVarInt(Math.min(20, value.lines.size()));
        for (int index = 0; index < Math.min(20, value.lines.size()); index++) {
            Line line = value.lines.get(index);
            buf.writeUUID(line.listingId); buf.writeIdentifier(line.itemId); buf.writeVarInt(line.stock);
            buf.writeVarLong(line.unitPrice); buf.writeBoolean(line.buyOffer);
        }
    }
    private static MarketPagePayload read(RegistryFriendlyByteBuf buf) {
        int container = buf.readVarInt(), revision = buf.readVarInt(), page = buf.readVarInt(), total = buf.readVarInt();
        int count = Math.min(20, Math.max(0, buf.readVarInt()));
        List<Line> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) lines.add(new Line(buf.readUUID(), buf.readIdentifier(),
                Math.max(0, buf.readVarInt()), Math.max(1L, buf.readVarLong()), buf.readBoolean()));
        return new MarketPagePayload(container, revision, page, total, lines);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
