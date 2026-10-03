package za.co.neroland.neroeconomy.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.StackLimit;

/** The vault as a section of the store: its saved form, its eraser and its export. */
class VaultSectionTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final AccountId ALICE_ACCOUNT = new PlayerAccountId(ALICE);
    private static final AccountId BOB_ACCOUNT = new PlayerAccountId(BOB);
    private static final StackLimit LIMIT = StackLimit.of(64);
    private static final int CAP = 54;
    private static final int BOUND = 216;

    private static VaultSection roundTrip(VaultSection section) {
        JsonElement encoded = VaultSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow();
        return VaultSection.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    @Test void everyStackSurvivesASaveInOrderWithItsPayload() {
        VaultSection section = new VaultSection();
        section.deliver(ALICE_ACCOUNT, List.of(Lot.of("minecraft:diamond", 100), new Lot("minecraft:diamond_sword", 1, 7)),
                CAP, BOUND, LIMIT);
        section.deliver(SystemAccountId.house("ironworks"), List.of(Lot.of("minecraft:iron_ingot", 12)), CAP, BOUND, LIMIT);
        VaultSection restored = roundTrip(section);
        assertEquals(List.of(Lot.of("minecraft:diamond", 64), Lot.of("minecraft:diamond", 36),
                new Lot("minecraft:diamond_sword", 1, 7)), restored.stacks(ALICE));
        assertEquals(12L, restored.total(SystemAccountId.house("ironworks"), "minecraft:iron_ingot", 0));
        assertEquals(0, restored.stackCount(BOB));
        assertFalse(restored.isEmpty());
        // Saved again it is the same document.
        assertEquals(VaultSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow(),
                VaultSection.CODEC.encodeStart(JsonOps.INSTANCE, restored).getOrThrow());
    }

    @Test void theSavedFormLeavesOutThePayloadOfAnUnmodifiedStack() {
        VaultSection section = new VaultSection();
        section.deliver(ALICE_ACCOUNT, List.of(Lot.of("minecraft:diamond", 3), new Lot("minecraft:diamond", 1, 2)),
                CAP, BOUND, LIMIT);
        JsonObject encoded = VaultSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow().getAsJsonObject();
        JsonObject account = encoded.getAsJsonArray("accounts").get(0).getAsJsonObject();
        assertEquals("player:" + ALICE, account.get("account").getAsString());
        JsonArray stacks = account.getAsJsonArray("stacks");
        assertFalse(stacks.get(0).getAsJsonObject().has("payload"));
        assertEquals(2, stacks.get(1).getAsJsonObject().get("payload").getAsInt());
    }

    @Test void anEmptyOrOlderSaveLoadsAsAnEmptyVault() {
        assertTrue(VaultSection.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().isEmpty());
        assertTrue(roundTrip(new VaultSection()).isEmpty());
    }

    @Test void aBadRowIsSkippedAndTheRestStillLoads() {
        JsonElement saved = JsonParser.parseString("""
                {"accounts": [
                  {"account": "player:00000000-0000-0000-0000-000000000001", "stacks": [
                    {"item": "minecraft:diamond", "count": 5},
                    {"item": "minecraft:diamond", "count": 0},
                    {"item": "", "count": 4},
                    {"item": "minecraft:emerald", "count": 2, "payload": -1},
                    {"item": "minecraft:emerald", "count": 9, "payload": 3}]},
                  {"account": "player:not-a-uuid", "stacks": [{"item": "minecraft:diamond", "count": 1}]},
                  {"account": "nobody", "stacks": [{"item": "minecraft:diamond", "count": 1}]},
                  {"account": "system:not a system account", "stacks": [{"item": "minecraft:diamond", "count": 1}]},
                  {"account": "system:reserve"}]}
                """);
        VaultSection section = VaultSection.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(List.of(Lot.of("minecraft:diamond", 5), new Lot("minecraft:emerald", 9, 3)), section.stacks(ALICE));
        assertEquals(1, section.accounts().size());
    }

    @Test void erasureDestroysOnlyTheErasedPlayersGoods() {
        VaultSection section = new VaultSection();
        section.deliver(ALICE_ACCOUNT, List.of(Lot.of("minecraft:diamond", 70)), CAP, BOUND, LIMIT);
        section.deliver(BOB_ACCOUNT, List.of(Lot.of("minecraft:emerald", 3)), CAP, BOUND, LIMIT);
        assertEquals(List.of(Lot.of("minecraft:diamond", 64), Lot.of("minecraft:diamond", 6)), section.forgetPlayer(ALICE));
        assertEquals(0, section.stackCount(ALICE));
        assertEquals(3L, section.total(BOB_ACCOUNT, "minecraft:emerald", 0));
        // Unknown and already-erased players are tolerated.
        assertEquals(List.of(), section.forgetPlayer(ALICE));
        assertEquals(List.of(), section.forgetPlayer(null));
        assertEquals(0, section.exportFor(ALICE).size());
        assertEquals(0, section.stackCount((UUID) null));
    }

    @Test void theExportNamesItemAndCountAndNothingElse() {
        VaultSection section = new VaultSection();
        section.deliver(ALICE_ACCOUNT, List.of(Lot.of("minecraft:diamond", 70), new Lot("minecraft:diamond_sword", 1, 7)),
                CAP, BOUND, LIMIT);
        section.deliver(BOB_ACCOUNT, List.of(Lot.of("minecraft:emerald", 3)), CAP, BOUND, LIMIT);
        JsonArray export = section.exportFor(ALICE);
        assertEquals(3, export.size());
        for (JsonElement row : export) assertEquals(java.util.Set.of("item", "count"), row.getAsJsonObject().keySet());
        assertEquals("minecraft:diamond_sword", export.get(2).getAsJsonObject().get("item").getAsString());
        // Nobody else's goods, and no payload id: a prototype can carry somebody else's data.
        String text = export.toString();
        assertFalse(text.contains("emerald"));
        assertFalse(text.contains("payload"));
        assertFalse(text.contains(BOB.toString()));
    }

    @Test void aChangeMarksTheStoreDirtyAndARefusalDoesNot() {
        EconomyStore store = new EconomyStore();
        VaultSection section = store.vault();
        assertFalse(store.isDirty());
        assertFalse(section.deposit(ALICE_ACCOUNT, List.of(Lot.of("minecraft:diamond", 64 * 3)), 2, LIMIT));
        assertEquals(0L, section.withdraw(ALICE_ACCOUNT, "minecraft:diamond", 0, 5L));
        assertFalse(section.take(ALICE_ACCOUNT, Lot.of("minecraft:diamond", 1)));
        assertFalse(section.replaceAll(ALICE_ACCOUNT, List.of()));
        assertFalse(store.isDirty());
        assertTrue(section.deposit(ALICE_ACCOUNT, List.of(Lot.of("minecraft:diamond", 5)), 2, LIMIT));
        assertTrue(store.isDirty());
        // The section's own flag stays clear: it is saved as part of the store.
        assertFalse(section.isDirty());
        assertEquals(List.of("vault"), store.sectionsPresent());
    }

    @Test void theSectionReportsEveryLotItHolds() {
        VaultSection section = new VaultSection();
        section.deliver(ALICE_ACCOUNT, List.of(new Lot("minecraft:diamond_sword", 1, 7)), CAP, BOUND, LIMIT);
        section.deliver(BOB_ACCOUNT, List.of(new Lot("minecraft:diamond_sword", 1, 9), Lot.of("minecraft:emerald", 3)),
                CAP, BOUND, LIMIT);
        List<Integer> payloads = new ArrayList<>();
        section.forEachLot(lot -> payloads.add(lot.payload()));
        assertEquals(List.of(7, 9, 0), payloads);
        // Through the store, which is what the prototype table scans.
        EconomyStore store = new EconomyStore();
        store.vault().deliver(ALICE_ACCOUNT, List.of(new Lot("minecraft:diamond_sword", 1, 7)), CAP, BOUND, LIMIT);
        List<Lot> seen = new ArrayList<>();
        store.forEachLot(seen::add);
        assertEquals(List.of(new Lot("minecraft:diamond_sword", 1, 7)), seen);
    }
}
