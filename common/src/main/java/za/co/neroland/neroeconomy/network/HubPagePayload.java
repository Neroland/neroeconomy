package za.co.neroland.neroeconomy.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * The Economy Hub's page, server to client. Only a {@link HubPage#capped() capped} page is ever
 * written, and the reader applies the same bounds again, so neither side can be sent more than
 * the limits allow. It names no player.
 */
public record HubPagePayload(HubPage page) implements CustomPacketPayload {
    public static final Type<HubPagePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "hub_page"));
    public static final StreamCodec<FriendlyByteBuf, HubPagePayload> STREAM_CODEC =
            StreamCodec.of(HubPagePayload::write, HubPagePayload::read);

    public static void write(FriendlyByteBuf buf, HubPagePayload value) {
        HubPage page = value.page.capped();
        buf.writeVarInt(page.containerId());
        buf.writeVarInt(page.revision());
        buf.writeVarInt(page.tab().ordinal());
        buf.writeVarInt(page.page());
        buf.writeVarInt(page.pageCount());
        buf.writeVarInt(page.rows().size());
        for (HubPage.Row row : page.rows()) {
            buf.writeUUID(row.id());
            buf.writeUtf(row.icon(), HubPage.MAX_ICON);
            buf.writeUtf(row.title(), HubPage.MAX_STRING);
            buf.writeVarInt(row.args().size());
            for (String arg : row.args()) buf.writeUtf(arg, HubPage.MAX_STRING);
            long[] nums = row.nums();
            buf.writeVarInt(nums.length);
            for (long num : nums) buf.writeVarLong(num);
            buf.writeVarInt(row.flags());
        }
        buf.writeVarInt(page.numbers().size());
        for (Map.Entry<String, Long> entry : page.numbers().entrySet()) {
            buf.writeUtf(entry.getKey(), HubPage.MAX_HEADER_NAME);
            buf.writeVarLong(entry.getValue());
        }
        buf.writeVarInt(page.keys().size());
        for (Map.Entry<String, String> entry : page.keys().entrySet()) {
            buf.writeUtf(entry.getKey(), HubPage.MAX_HEADER_NAME);
            buf.writeUtf(entry.getValue(), HubPage.MAX_STRING);
        }
        long[] series = page.series();
        buf.writeVarInt(series.length);
        for (long point : series) buf.writeVarLong(point);
    }

    /** A count read off the wire, refused outright when it is past its bound. */
    private static int count(FriendlyByteBuf buf, int max) {
        int value = buf.readVarInt();
        if (value < 0 || value > max) throw new IllegalArgumentException("Hub page count out of bounds");
        return value;
    }

    public static HubPagePayload read(FriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int revision = buf.readVarInt();
        HubTab tab = HubTab.byOrdinal(buf.readVarInt());
        int page = buf.readVarInt();
        int pageCount = buf.readVarInt();
        int rowCount = count(buf, HubPage.MAX_ROWS);
        List<HubPage.Row> rows = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            var id = buf.readUUID();
            String icon = buf.readUtf(HubPage.MAX_ICON);
            String title = buf.readUtf(HubPage.MAX_STRING);
            int argCount = count(buf, HubPage.MAX_ARGS);
            List<String> args = new ArrayList<>(argCount);
            for (int arg = 0; arg < argCount; arg++) args.add(buf.readUtf(HubPage.MAX_STRING));
            int numCount = count(buf, HubPage.MAX_NUMS);
            long[] nums = new long[numCount];
            for (int num = 0; num < numCount; num++) nums[num] = buf.readVarLong();
            rows.add(new HubPage.Row(id, icon, title, args, nums, buf.readVarInt()));
        }
        int numberCount = count(buf, HubPage.MAX_HEADER_NUMBERS);
        Map<String, Long> numbers = new LinkedHashMap<>();
        for (int index = 0; index < numberCount; index++)
            numbers.put(buf.readUtf(HubPage.MAX_HEADER_NAME), buf.readVarLong());
        int keyCount = count(buf, HubPage.MAX_HEADER_KEYS);
        Map<String, String> keys = new LinkedHashMap<>();
        for (int index = 0; index < keyCount; index++)
            keys.put(buf.readUtf(HubPage.MAX_HEADER_NAME), buf.readUtf(HubPage.MAX_STRING));
        int seriesCount = count(buf, HubPage.MAX_SERIES);
        long[] series = new long[seriesCount];
        for (int index = 0; index < seriesCount; index++) series[index] = buf.readVarLong();
        return new HubPagePayload(new HubPage(containerId, revision, tab, page, pageCount, rows, numbers, keys,
                series));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
