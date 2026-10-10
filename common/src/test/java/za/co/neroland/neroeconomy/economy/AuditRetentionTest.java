package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F2: a flood of small rows cannot push the audit's minting, burning and large rows out of the bound. */
class AuditRetentionTest {
    private record Row(String name, boolean kept) { }

    @Test void adminMintBurnAndLargeRowsAreTheOnesKept() {
        assertTrue(AuditRetention.kept(TransactionType.ADMIN_GRANT, 1L));
        assertTrue(AuditRetention.kept(TransactionType.ADMIN_TAKE, 1L));
        assertTrue(AuditRetention.kept(TransactionType.ADMIN_SET, 1L));
        assertTrue(AuditRetention.kept(TransactionType.RESERVE_MINT, 1L));
        assertTrue(AuditRetention.kept(TransactionType.PLAYER_PAYMENT, AuditRetention.LARGE_AMOUNT));
        assertFalse(AuditRetention.kept(TransactionType.PLAYER_PAYMENT, AuditRetention.LARGE_AMOUNT - 1L));
        assertFalse(AuditRetention.kept(null, Long.MAX_VALUE));
    }

    @Test void aFloodOfSmallRowsDropsTheOldestSmallRowsAndNeverAnAdminRow() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("admin-grant", true));
        for (int i = 0; i < 10_000; i++) rows.add(new Row("pay-" + i, false));
        assertEquals(1, AuditRetention.trim(rows, 10_000, Row::kept));
        assertEquals("admin-grant", rows.get(0).name());
        // The oldest small row went, the order of the rest is kept.
        assertEquals("pay-1", rows.get(1).name());
        assertEquals("pay-9999", rows.get(rows.size() - 1).name());
        // An attacker paying 1 credit 20 000 more times never rotates the admin row out.
        for (int i = 0; i < 20_000; i++) {
            rows.add(new Row("spam-" + i, false));
            AuditRetention.trim(rows, 10_000, Row::kept);
        }
        assertEquals(10_000, rows.size());
        assertEquals("admin-grant", rows.get(0).name());
    }

    @Test void keptRowsGoOnlyWhenTheyAlonePassTheBoundOldestFirst() {
        List<Row> rows = new ArrayList<>(List.of(new Row("k1", true), new Row("o1", false), new Row("k2", true),
                new Row("k3", true)));
        assertEquals(2, AuditRetention.trim(rows, 2, Row::kept));
        assertEquals(List.of(new Row("k2", true), new Row("k3", true)), rows);
        assertEquals(0, AuditRetention.trim(rows, 5, Row::kept));
        assertEquals(2, AuditRetention.trim(rows, 0, Row::kept));
        assertTrue(rows.isEmpty());
    }
}
