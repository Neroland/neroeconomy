package za.co.neroland.neroeconomy.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.PlayerLedgerExport;
import za.co.neroland.neroeconomy.economy.TransactionRecord;
import za.co.neroland.neroeconomy.economy.TransactionType;

class PlayerDataExportTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void ledgerSectionKeepsTheOriginalKeysAndAddsTheNewOnes() {
        PlayerLedgerExport export = new PlayerLedgerExport(ALICE, 250L, "nerolandcore:credits", true,
                List.of(new TransactionRecord(TransactionType.ESCROW_REFUND, 5L, "nerolandcore:credits", 1_000L,
                        "", "abc123")), "abc123", 20_000L, 3);
        JsonObject json = PlayerDataExport.ledger(export);
        assertEquals(ALICE.toString(), json.get("player_uuid").getAsString());
        assertEquals("abc123", json.get("account_reference").getAsString());
        assertEquals("nerolandcore:credits", json.get("currency").getAsString());
        assertEquals(250L, json.get("balance").getAsLong());
        assertTrue(json.get("transaction_logging_opt_out").getAsBoolean());
        JsonObject chips = json.getAsJsonObject("credit_chip_redemptions");
        assertEquals(20_000L, chips.get("economy_day").getAsLong());
        assertEquals(3, chips.get("count").getAsInt());
        // The vesting fields are exported with the account; this export has nothing locked.
        JsonObject vesting = json.getAsJsonObject("starting_balance_vesting");
        assertEquals(0L, vesting.get("locked_starting_balance").getAsLong());
        assertTrue(vesting.get("vested").getAsBoolean());
        JsonObject row = json.getAsJsonArray("transactions").get(0).getAsJsonObject();
        assertEquals("ESCROW_REFUND", row.get("reason").getAsString());
        assertEquals(5L, row.get("amount").getAsLong());
        assertEquals("nerolandcore:credits", row.get("currency").getAsString());
        assertEquals(1_000L, row.get("timestamp").getAsLong());
        assertEquals("", row.get("source").getAsString());
        assertEquals("abc123", row.get("destination").getAsString());
    }

    @Test void aRealLedgerExportNeverCarriesTheCounterpartysPseudonymOrId() {
        EconomySavedData data = new EconomySavedData();
        data.seedReserve(250_000L);
        // Opening each account pays its starting balance; vesting it lets that be paid on.
        for (UUID player : List.of(ALICE, BOB)) {
            data.withdraw(player, NeroEconomyCommon.CREDITS, 0L);
            data.markVested(player);
        }
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 10L));
        String bobReference = data.export(BOB).accountReference();
        String text = PlayerDataExport.ledger(data.export(ALICE)).toString();
        assertFalse(text.contains(bobReference));
        assertFalse(text.contains(BOB.toString()));
        assertTrue(text.contains(data.export(ALICE).accountReference()));
        assertTrue(text.contains(EconomySavedData.COUNTERPARTY));
    }

    @Test void anUnvestedStartingBalanceIsExportedAsLocked() {
        EconomySavedData data = new EconomySavedData();
        data.seedReserve(250_000L);
        data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 0L);
        JsonObject vesting = PlayerDataExport.ledger(data.export(ALICE)).getAsJsonObject("starting_balance_vesting");
        assertEquals(100L, vesting.get("locked_starting_balance").getAsLong());
        assertFalse(vesting.get("vested").getAsBoolean());
        // Erasure takes both fields with the account.
        data.forgetPlayer(ALICE);
        vesting = PlayerDataExport.ledger(data.export(ALICE)).getAsJsonObject("starting_balance_vesting");
        assertEquals(0L, vesting.get("locked_starting_balance").getAsLong());
        assertTrue(vesting.get("vested").getAsBoolean());
    }
}
