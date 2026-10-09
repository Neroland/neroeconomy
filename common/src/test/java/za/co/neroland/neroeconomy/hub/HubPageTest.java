package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.network.HubPagePayload;

/** A page never grows past its caps, however much a builder hands it, and survives the wire intact. */
class HubPageTest {
    private static String repeat(char c, int n) { return String.valueOf(c).repeat(n); }

    private static HubPage oversized() {
        List<HubPage.Row> rows = new ArrayList<>();
        for (int index = 0; index < HubPage.MAX_ROWS + 5; index++) {
            rows.add(new HubPage.Row(new UUID(1L, index), repeat('i', HubPage.MAX_ICON + 40),
                    repeat('t', HubPage.MAX_STRING + 40), List.of("a", "b", "c", "d", "e", repeat('x', 500)),
                    new long[] { 1, 2, 3, 4, 5, 6, 7, 8 }, HubPage.FLAG_A));
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        for (int index = 0; index < HubPage.MAX_HEADER_NUMBERS + 10; index++)
            numbers.put("n" + index + repeat('k', 40), (long) index);
        Map<String, String> keys = new LinkedHashMap<>();
        for (int index = 0; index < HubPage.MAX_HEADER_KEYS + 10; index++) keys.put("k" + index, repeat('v', 300));
        return new HubPage(3, 7, HubTab.MARKET, 0, 1, rows, numbers, keys);
    }

    @Test void cappingKeepsEveryListAndStringWithinItsLimit() {
        HubPage capped = oversized().capped();
        assertEquals(HubPage.MAX_ROWS, capped.rows().size());
        for (HubPage.Row row : capped.rows()) {
            assertTrue(row.icon().length() <= HubPage.MAX_ICON);
            assertTrue(row.title().length() <= HubPage.MAX_STRING);
            assertEquals(HubPage.MAX_ARGS, row.args().size());
            for (String arg : row.args()) assertTrue(arg.length() <= HubPage.MAX_STRING);
            assertEquals(HubPage.MAX_NUMS, row.nums().length);
        }
        assertEquals(HubPage.MAX_HEADER_NUMBERS, capped.numbers().size());
        for (String name : capped.numbers().keySet()) assertTrue(name.length() <= HubPage.MAX_HEADER_NAME);
        assertEquals(HubPage.MAX_HEADER_KEYS, capped.keys().size());
        for (String value : capped.keys().values()) assertTrue(value.length() <= HubPage.MAX_STRING);
        assertEquals(3, capped.containerId());
        assertEquals(7, capped.revision());
    }

    @Test void aPageWithinItsCapsIsUnchangedByCapping() {
        HubPage page = new HubPage(1, 2, HubTab.WALLET, 0, 1,
                List.of(new HubPage.Row(new UUID(0L, 1L), "minecraft:stone", "gui.neroeconomy.hub.vault.row",
                        List.of(), new long[] { 5 }, 0)), Map.of("balance", 10L), Map.of());
        assertEquals(page, page.capped());
    }

    @Test void theEncoderCapsAnOversizedPageAndTheDecoderReadsItBack() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HubPagePayload.write(buf, new HubPagePayload(oversized()));
        HubPage read = HubPagePayload.read(buf).page();
        assertEquals(oversized().capped(), read);
        assertEquals(0, buf.readableBytes());
    }

    @Test void pageCountsAndClampingStayInRange() {
        assertEquals(1, HubPage.pages(0, 7));
        assertEquals(1, HubPage.pages(7, 7));
        assertEquals(2, HubPage.pages(8, 7));
        assertEquals(1, HubPage.pages(5, 0));
        assertEquals(0, HubPage.clampPage(-4, 3));
        assertEquals(2, HubPage.clampPage(99, 3));
        assertEquals(0, HubPage.clampPage(Long.MAX_VALUE, 0));
    }

    @Test void rowsKeepTheirNumbersPrivateFromCallers() {
        long[] nums = { 1, 2 };
        HubPage.Row row = new HubPage.Row(null, null, null, null, nums, 0);
        nums[0] = 99;
        row.nums()[1] = 99;
        assertEquals(1, row.num(0));
        assertEquals(2, row.num(1));
        assertEquals(0, row.num(5));
        assertEquals("", row.arg(0));
    }
}
