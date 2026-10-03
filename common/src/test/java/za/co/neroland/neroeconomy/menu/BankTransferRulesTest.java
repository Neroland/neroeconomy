package za.co.neroland.neroeconomy.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.menu.BankTransferRules.Check;

class BankTransferRulesTest {
    @Test void aKnownRecipientAndEnoughCreditsPass() {
        assertEquals(Check.OK, BankTransferRules.check(100L, true, false, 100L));
        assertEquals(Check.OK, BankTransferRules.check(1L, true, false, Long.MAX_VALUE));
    }

    @Test void anUnknownRecipientIsRefusedBeforeFundsAreEvenLookedAt() {
        // The terminal must not reach the ledger for an id nobody has played as: the ledger would
        // open and fund that account. A rich sender changes nothing.
        assertEquals(Check.UNKNOWN_RECIPIENT, BankTransferRules.check(100L, false, false, Long.MAX_VALUE));
        assertEquals(Check.UNKNOWN_RECIPIENT, BankTransferRules.check(100L, false, false, 0L));
    }

    @Test void aShortBalanceIsRefusedBeforeTheLedgerIsCalled() {
        assertEquals(Check.INSUFFICIENT_FUNDS, BankTransferRules.check(101L, true, false, 100L));
        assertEquals(Check.INSUFFICIENT_FUNDS, BankTransferRules.check(1L, true, false, 0L));
    }

    @Test void payingYourselfIsRefused() {
        assertEquals(Check.SELF, BankTransferRules.check(10L, true, true, 100L));
    }

    @Test void aNonPositiveAmountIsRefusedFirst() {
        assertEquals(Check.INVALID_AMOUNT, BankTransferRules.check(0L, true, false, 100L));
        assertEquals(Check.INVALID_AMOUNT, BankTransferRules.check(-5L, false, true, 0L));
    }

    @Test void refusalsHaveTheirOwnLangKeys() {
        assertEquals("gui.neroeconomy.bank.refused.unknown_recipient", Check.UNKNOWN_RECIPIENT.translationKey());
        assertEquals("gui.neroeconomy.bank.refused.insufficient_funds", Check.INSUFFICIENT_FUNDS.translationKey());
    }
}
