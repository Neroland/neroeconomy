package za.co.neroland.neroeconomy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RequestLedgerTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long NOW = 5_000_000_000L;

    @Test void aReplayedIdIsRefusedForTheSamePlayerOnly() {
        RequestLedger ledger = new RequestLedger();
        UUID request = UUID.randomUUID();
        assertTrue(ledger.remember(ALICE, request, NOW));
        assertFalse(ledger.remember(ALICE, request, NOW + 1L));
        assertFalse(ledger.remember(ALICE, request, NOW + RequestLedger.TTL_MILLIS - 1L));
        // Ids are scoped to the player, so another player may use the same value.
        assertTrue(ledger.remember(BOB, request, NOW));
        assertFalse(ledger.remember(null, request, NOW));
        assertFalse(ledger.remember(ALICE, null, NOW));
    }

    @Test void anIdIsUsableAgainOnceItsTimeToLiveHasPassed() {
        RequestLedger ledger = new RequestLedger();
        UUID request = UUID.randomUUID();
        assertTrue(ledger.remember(ALICE, request, NOW));
        assertTrue(ledger.remember(ALICE, request, NOW + RequestLedger.TTL_MILLIS));
        assertFalse(ledger.remember(ALICE, request, NOW + RequestLedger.TTL_MILLIS + 1L));
    }

    @Test void expireDropsOnlyEntriesOlderThanTheTimeToLive() {
        RequestLedger ledger = new RequestLedger();
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        ledger.remember(ALICE, old, NOW);
        ledger.remember(ALICE, fresh, NOW + 300_000L);
        ledger.remember(BOB, old, NOW);
        assertFalse(ledger.expire(NOW + RequestLedger.TTL_MILLIS - 1L));
        assertTrue(ledger.expire(NOW + RequestLedger.TTL_MILLIS));
        assertEquals(List.of(fresh), new ArrayList<>(ledger.of(ALICE).keySet()));
        // A player with nothing left is removed entirely, not kept as an empty row.
        assertFalse(ledger.snapshot().containsKey(BOB));
    }

    @Test void eachPlayerKeepsAtMostTheCapAndLosesTheOldestFirst() {
        RequestLedger ledger = new RequestLedger();
        UUID first = UUID.randomUUID();
        assertTrue(ledger.remember(ALICE, first, NOW));
        for (int i = 0; i < RequestLedger.MAX_PER_PLAYER; i++) assertTrue(ledger.remember(ALICE, UUID.randomUUID(), NOW + i));
        assertEquals(RequestLedger.MAX_PER_PLAYER, ledger.of(ALICE).size());
        assertFalse(ledger.of(ALICE).containsKey(first));
    }

    @Test void erasureForgetsOnePlayerAndToleratesUnknownOnes() {
        RequestLedger ledger = new RequestLedger();
        UUID request = UUID.randomUUID();
        ledger.remember(ALICE, request, NOW);
        ledger.remember(BOB, request, NOW);
        assertTrue(ledger.forget(ALICE));
        assertFalse(ledger.forget(ALICE));
        assertFalse(ledger.forget(UUID.randomUUID()));
        assertFalse(ledger.forget(null));
        assertTrue(ledger.of(ALICE).isEmpty());
        assertEquals(1, ledger.of(BOB).size());
    }

    @Test void aSnapshotRestoresIntoAnEquivalentLedger() {
        RequestLedger ledger = new RequestLedger();
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        ledger.remember(ALICE, one, NOW);
        ledger.remember(ALICE, two, NOW + 5L);
        RequestLedger restored = new RequestLedger();
        ledger.snapshot().forEach((player, ids) -> ids.forEach((id, at) -> restored.restore(player, id, at)));
        assertEquals(ledger.snapshot(), restored.snapshot());
        // A replay across the "restart" is still refused, and the restored age still counts.
        assertFalse(restored.remember(ALICE, one, NOW + 10L));
        assertTrue(restored.expire(NOW + RequestLedger.TTL_MILLIS + 4L));
        assertEquals(List.of(two), new ArrayList<>(restored.of(ALICE).keySet()));
    }
}
