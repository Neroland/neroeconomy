package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.StackLimit;

/**
 * The combined store, as far as it goes without the game's registries: the ledger section is fully
 * exercised; the market and obligation sections hold item stacks, so here they are only ever empty.
 */
class EconomyStoreTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void aNewStoreHasEverySectionAndNoSchemaYet() {
        EconomyStore store = new EconomyStore();
        assertEquals(0, store.schema());
        assertTrue(store.ledger().isEmpty());
        assertTrue(store.market().isEmpty());
        assertTrue(store.obligations().isEmpty());
        assertTrue(store.shopOwners().isEmpty());
        assertTrue(store.goods().isEmpty());
        assertTrue(store.vault().isEmpty());
        assertTrue(store.simulation().isEmpty());
        assertTrue(store.sectionsPresent().isEmpty());
        assertEquals(0L, store.escrowObligations());
        assertTrue(store.checkInvariants());
        assertEquals("neroeconomy:economy", EconomyStore.ID.toString());
        assertEquals("neroeconomy:economy_marker", EconomyMarker.ID.toString());
    }

    @Test void markingASectionDirtyMarksTheStoreAndNotTheSection() {
        EconomyStore store = new EconomyStore();
        assertFalse(store.isDirty());
        store.shopOwners().setDirty();
        assertTrue(store.isDirty());
        // The section's own flag stays clear: the file it may once have been read from is not rewritten.
        assertFalse(store.shopOwners().isDirty());
        store.setDirty(false);
        store.ledger().seedReserve(Ledgers.RESERVE);
        assertTrue(store.isDirty());
        assertFalse(store.ledger().isDirty());
        store.setDirty(false);
        store.market().rememberRequest(ALICE, UUID.randomUUID());
        assertTrue(store.isDirty());
        store.setDirty(false);
        // A section that stands alone keeps its own flag, as the old stores did.
        EconomySavedData alone = new EconomySavedData();
        alone.seedReserve(1L);
        assertTrue(alone.isDirty());
    }

    @Test void theLedgerSectionSurvivesTheCombinedRoundTrip() {
        EconomyStore store = new EconomyStore();
        EconomySavedData ledger = store.ledger();
        ledger.seedReserve(Ledgers.RESERVE);
        Ledgers.open(ledger, ALICE, BOB);
        assertTrue(ledger.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 25L));
        assertEquals(LedgerResult.APPLIED, ledger.apply(LedgerPlan.of(new PlayerAccountId(ALICE), SystemAccountId.RESERVE,
                3L, TransactionType.FEE_LISTING)));
        JsonObject encoded = EconomyStore.CODEC.encodeStart(JsonOps.INSTANCE, store).getOrThrow().getAsJsonObject();
        // One file: the ledger is a field of it, and sections that hold nothing are left out.
        assertTrue(encoded.has("ledger"));
        assertFalse(encoded.has("market"));
        assertFalse(encoded.has("obligations"));
        assertFalse(encoded.has("shop_owners"));
        EconomyStore restored = EconomyStore.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(72L, restored.ledger().getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(125L, restored.ledger().getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(Ledgers.RESERVE - 197L, restored.ledger().balance(SystemAccountId.RESERVE));
        assertEquals(ledger.transactionCount(), restored.ledger().transactionCount());
        assertTrue(restored.checkInvariants());
        assertTrue(restored.supplyReport().healthy());
        assertEquals(List.of("ledger"), restored.sectionsPresent());
        // The sections that were left out come back empty, and attached to the restored store.
        assertTrue(restored.market().isEmpty());
        assertFalse(restored.isDirty());
        restored.obligations().setDirty();
        assertTrue(restored.isDirty());
    }

    @Test void theSchemaIsPersistedAndAnOlderFileWithoutNewSectionsStillLoads() {
        EconomyStore store = new EconomyStore();
        store.ledger().seedReserve(Ledgers.RESERVE);
        JsonObject encoded = EconomyStore.CODEC.encodeStart(JsonOps.INSTANCE, store).getOrThrow().getAsJsonObject();
        // Never set up: no schema is written, which is what marks a store as needing set-up.
        assertFalse(encoded.has("schema"));
        encoded.addProperty("schema", EconomyStore.SCHEMA);
        // A field this build does not know, as a later section would be to an older build.
        encoded.add("vault", new JsonObject());
        EconomyStore restored = EconomyStore.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(EconomyStore.SCHEMA, restored.schema());
        assertEquals(Ledgers.RESERVE, restored.ledger().balance(SystemAccountId.RESERVE));
        // An empty file is an empty store, not an error.
        EconomyStore blank = EconomyStore.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
        assertEquals(0, blank.schema());
        assertTrue(blank.ledger().isEmpty());
        JsonObject again = EconomyStore.CODEC.encodeStart(JsonOps.INSTANCE, restored).getOrThrow().getAsJsonObject();
        assertEquals(EconomyStore.SCHEMA, again.get("schema").getAsInt());
    }

    @Test void theVaultAndTheSimulationAreSectionsOfTheSameFile() {
        EconomyStore store = new EconomyStore();
        store.ledger().seedReserve(Ledgers.RESERVE);
        AccountId alice = new PlayerAccountId(ALICE);
        store.vault().deliver(alice, List.of(Lot.of("minecraft:diamond", 70), new Lot("minecraft:diamond_sword", 1, 3)),
                54, 216, StackLimit.of(64));
        store.simulation().useStepLength(600_000L);
        store.simulation().setLastStep(2_900_000L);
        JsonObject encoded = EconomyStore.CODEC.encodeStart(JsonOps.INSTANCE, store).getOrThrow().getAsJsonObject();
        assertTrue(encoded.has("vault"));
        assertTrue(encoded.has("simulation"));
        // No prototype was stored, so the goods section has nothing to save.
        assertFalse(encoded.has("goods"));
        EconomyStore restored = EconomyStore.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(List.of(Lot.of("minecraft:diamond", 64), Lot.of("minecraft:diamond", 6),
                new Lot("minecraft:diamond_sword", 1, 3)), restored.vault().stacks(alice));
        assertEquals(2_900_000L, restored.simulation().lastStep());
        assertEquals(List.of("ledger", "vault", "simulation"), restored.sectionsPresent());
        assertTrue(restored.checkInvariants());
        // A store saved before either section existed loads with both empty.
        encoded.remove("vault");
        encoded.remove("simulation");
        EconomyStore older = EconomyStore.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertTrue(older.vault().isEmpty());
        assertTrue(older.simulation().isEmpty());
        assertEquals(Ledgers.RESERVE, older.ledger().balance(SystemAccountId.RESERVE));
    }

    @Test void everySectionThatHoldsLotsOrEscrowIsCounted() {
        EconomyStore store = new EconomyStore();
        store.vault().deliver(new PlayerAccountId(ALICE), List.of(new Lot("minecraft:diamond_sword", 1, 3)), 54, 216,
                StackLimit.of(64));
        store.vault().deliver(new PlayerAccountId(BOB), List.of(Lot.of("minecraft:diamond", 5)), 54, 216,
                StackLimit.of(64));
        List<Lot> seen = new java.util.ArrayList<>();
        store.forEachLot(seen::add);
        assertEquals(List.of(new Lot("minecraft:diamond_sword", 1, 3), Lot.of("minecraft:diamond", 5)), seen);
        // The market and obligation sections are the escrow holders; with nothing open they owe nothing.
        assertEquals(0L, store.escrowObligations());
        assertTrue(store.market().addCreditClaim(ALICE, 40L, TransactionType.ESCROW_REFUND));
        assertEquals(40L, store.escrowObligations());
        // A market section with no item claims left over from an older save hands the store nothing to move.
        assertTrue(store.market().drainLegacyItemClaims(true).isEmpty());
    }

    @Test void theMarkerHoldsOneFactAndNoPlayerData() {
        EconomyMarker marker = new EconomyMarker();
        assertFalse(marker.exists());
        assertFalse(marker.isDirty());
        marker.markExists(EconomyStore.SCHEMA);
        assertTrue(marker.exists());
        assertTrue(marker.isDirty());
        marker.setDirty(false);
        // Marking it again changes nothing and does not ask for another save.
        marker.markExists(EconomyStore.SCHEMA);
        assertFalse(marker.isDirty());
        JsonObject encoded = EconomyMarker.CODEC.encodeStart(JsonOps.INSTANCE, marker).getOrThrow().getAsJsonObject();
        assertEquals(java.util.Set.of("exists", "schema"), encoded.keySet());
        EconomyMarker restored = EconomyMarker.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertTrue(restored.exists());
        assertEquals(EconomyStore.SCHEMA, restored.schema());
        assertFalse(EconomyMarker.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().exists());
    }

    @Test void theInvariantCheckFailsWhenEscrowHoldsWhatNothingRecords() {
        EconomyStore store = new EconomyStore();
        EconomySavedData ledger = store.ledger();
        ledger.seedReserve(Ledgers.RESERVE);
        assertTrue(store.checkInvariants());
        // Credits in escrow with no offer, claim, job or bid behind them.
        assertEquals(LedgerResult.APPLIED, ledger.apply(LedgerPlan.of(SystemAccountId.RESERVE, SystemAccountId.ESCROW,
                10L, TransactionType.MARKET_ESCROW)));
        assertFalse(store.checkInvariants());
        assertFalse(store.supplyReport().escrowBalanced());
        assertTrue(store.supplyReport().supplyBalanced());
        assertSame(ledger, store.ledger());
    }
}
