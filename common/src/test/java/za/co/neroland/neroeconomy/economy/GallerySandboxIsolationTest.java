package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.gallery.GallerySandbox;
import za.co.neroland.neroeconomy.gallery.GallerySamples;
import za.co.neroland.neroeconomy.link.LinkPayloads;

/**
 * The gallery's sandbox never reaches the real economy. A busy fuzzed store is snapshotted — the
 * supply report, all three invariants, every link section of every player and every other section
 * of the saved store — then a gallery is seeded into it with blocks and labels recorded, and the
 * snapshot must come out byte for byte the same. Clearing it leaves the saved store exactly as it
 * was before the gallery, gallery field and all.
 */
class GallerySandboxIsolationTest {
    @AfterEach void realClockAgain() { StoreTesting.wallClock(null); }

    /** Everything a player, the app or an operator's supply check could see, as one string. */
    private static String snapshot(FuzzWorld world) {
        EconomyStore store = world.store;
        long now = world.ledger().now();
        StringBuilder out = new StringBuilder();
        SupplyReport report = store.supplyReport();
        out.append("supply=").append(report).append('\n');
        out.append("invariants=").append(store.checkInvariants()).append(' ').append(report.healthy()).append(' ')
                .append(store.escrowObligations()).append(' ').append(store.exchangeObligations()).append('\n');
        String credits = NeroEconomyCommon.CREDITS.id().toString();
        EconomySavedData ledger = store.ledger();
        for (UUID player : world.players) {
            List<LinkPayloads.Entry> rows = new ArrayList<>();
            for (SubjectLedgerRow row : ledger.subjectHistory(player, 200))
                rows.add(new LinkPayloads.Entry(row.type(), row.amount(), row.incoming(), row.currency(),
                        row.timestamp(), row.timestamp()));
            long balance = ledger.getBalance(player, NeroEconomyCommon.CREDITS);
            out.append("balance=").append(LinkPayloads.balance(credits, balance,
                    rows.subList(0, Math.min(rows.size(), LinkPayloads.HISTORY_POINTS)), now)).append('\n');
            out.append("transactions=").append(LinkPayloads.transactions(rows, 0, LinkPayloads.MAX_PAGE_SIZE, now))
                    .append('\n');
        }
        // The market section of the app is built from the book's open orders and the market's own rows.
        List<LinkPayloads.Row> sells = new ArrayList<>();
        for (SellOrder order : store.book().sellsMatching(item -> true, now))
            sells.add(new LinkPayloads.Row(order.id().toString(), order.item(), "", (int) Math.min(Integer.MAX_VALUE,
                    order.lot().count()), order.unitPrice(), false, true));
        List<LinkPayloads.Row> buys = new ArrayList<>();
        for (BuyOrder order : store.book().buysMatching(item -> true, now))
            buys.add(new LinkPayloads.Row(order.id().toString(), order.item(), "", order.remaining(),
                    order.unitPrice(), false, true));
        out.append("market=").append(LinkPayloads.market(sells, buys, 0, LinkPayloads.DEFAULT_PAGE_SIZE, sells.size(),
                buys.size(), now)).append('\n');
        JsonObject saved = StoreTesting.encode(store).getAsJsonObject().deepCopy();
        saved.remove("gallery");
        out.append("store=").append(saved).append('\n');
        return out.toString();
    }

    @Test void buildingAGalleryChangesNothingTheEconomyCanSee() {
        FuzzWorld world = OperationFuzzer.run(34L, 600, Set.of(), false).world();
        EconomyStore store = world.store;
        String before = snapshot(world);
        String savedBefore = StoreTesting.encode(store).toString();
        assertFalse(StoreTesting.encode(store).getAsJsonObject().has("gallery"));

        GallerySandbox gallery = store.gallery();
        gallery.seed(world.ledger().now());
        for (long pos = 0L; pos < 1_500L; pos++) assertTrue(gallery.recordBlock(pos * 31L, "minecraft:smooth_quartz"));
        assertTrue(gallery.recordLabel(new UUID(7L, 1L), GallerySandbox.ROLE_LOOP));
        assertTrue(gallery.recordLabel(new UUID(7L, 2L), GallerySandbox.ROLE_TICKER + 0));
        assertTrue(store.gallery().built());
        assertTrue(store.sectionsPresent().contains("gallery"));

        // The invariants are part of the snapshot: whatever they read before, they read the same now.
        assertEquals(before, snapshot(world));
        // No sample row can be looked up as a listing or a buy offer, so none can be bought or filled.
        for (int index = 0; index < GallerySamples.BOARD_ROWS.size(); index++) {
            UUID id = GallerySandbox.rowId(index);
            assertNull(store.market().listing(id));
            assertNull(store.market().buyOffer(id));
            assertNull(store.book().sell(id));
            assertNull(store.book().buy(id));
        }
        // The sandbox is saved with the store, and a restart keeps it apart just the same.
        EconomyStore restarted = StoreTesting.reload(store);
        assertTrue(restarted.gallery().built());
        assertEquals(store.gallery().blocks(), restarted.gallery().blocks());
        assertEquals(store.supplyReport(), restarted.supplyReport());

        GallerySandbox.Cleared cleared = gallery.clear();
        assertEquals(GallerySamples.BOARD_ROWS.size(), cleared.rows());
        assertEquals(1_500, cleared.blocks().size());
        assertEquals(2, cleared.labels().size());
        assertEquals(before, snapshot(world));
        assertEquals(savedBefore, StoreTesting.encode(store).toString());
        assertNotEquals(-1, before.indexOf("supply="));
    }
}
