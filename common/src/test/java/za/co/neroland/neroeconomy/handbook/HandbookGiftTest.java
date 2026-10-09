package za.co.neroland.neroeconomy.handbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.data.PlayerDataExport;
import za.co.neroland.neroeconomy.economy.EconomySavedData;

/**
 * The Trader's Handbook is given once: never with the option off, while frozen, to a spectator,
 * twice, or past the bound; the one flag kept is exported with the account and erased with it.
 */
class HandbookGiftTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void givenOnlyToANewPlayingAccountWithTheOptionOn() {
        assertTrue(HandbookGift.shouldGive(true, false, false, false, 0));
        assertFalse(HandbookGift.shouldGive(false, false, false, false, 0), "option off");
        assertFalse(HandbookGift.shouldGive(true, true, false, false, 0), "economy frozen");
        assertFalse(HandbookGift.shouldGive(true, false, true, false, 0), "spectator");
        assertFalse(HandbookGift.shouldGive(true, false, false, true, 0), "already given");
        assertFalse(HandbookGift.shouldGive(true, false, false, false, HandbookGift.MAX_FLAGS), "at the bound");
        assertTrue(HandbookGift.shouldGive(true, false, false, false, HandbookGift.MAX_FLAGS - 1));
    }

    @Test void neverGivenTwice() {
        EconomySavedData ledger = new EconomySavedData();
        assertFalse(ledger.handbookGiven(ALICE));
        assertTrue(HandbookGift.shouldGive(true, false, false, ledger.handbookGiven(ALICE), ledger.handbookGivenCount()));
        assertTrue(ledger.markHandbookGiven(ALICE));
        assertFalse(ledger.markHandbookGiven(ALICE), "a second mark changes nothing");
        assertFalse(HandbookGift.shouldGive(true, false, false, ledger.handbookGiven(ALICE), ledger.handbookGivenCount()));
        // Another account is untouched.
        assertFalse(ledger.handbookGiven(BOB));
        assertEquals(1, ledger.handbookGivenCount());
        assertFalse(ledger.markHandbookGiven(null));
    }

    @Test void aSpectatorIsNotRecordedSoALaterJoinStillGetsIt() {
        EconomySavedData ledger = new EconomySavedData();
        // The caller records the gift only when the rule says yes.
        if (HandbookGift.shouldGive(true, false, true, ledger.handbookGiven(ALICE), ledger.handbookGivenCount()))
            ledger.markHandbookGiven(ALICE);
        assertFalse(ledger.handbookGiven(ALICE));
        assertTrue(HandbookGift.shouldGive(true, false, false, ledger.handbookGiven(ALICE), ledger.handbookGivenCount()));
    }

    @Test void theFlagIsExportedAndErasedWithTheAccount() {
        EconomySavedData ledger = new EconomySavedData();
        assertFalse(PlayerDataExport.ledger(ledger.export(ALICE)).get("handbook_given").getAsBoolean());
        ledger.markHandbookGiven(ALICE);
        assertTrue(ledger.export(ALICE).handbookGiven());
        assertTrue(PlayerDataExport.ledger(ledger.export(ALICE)).get("handbook_given").getAsBoolean());
        assertFalse(ledger.export(BOB).handbookGiven());
        ledger.forgetPlayer(ALICE);
        assertFalse(ledger.handbookGiven(ALICE));
        assertEquals(0, ledger.handbookGivenCount());
        assertFalse(PlayerDataExport.ledger(ledger.export(ALICE)).get("handbook_given").getAsBoolean());
    }
}
