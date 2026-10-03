package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** The subject-side view of the ledger that the companion-app module reads. */
class SubjectHistoryTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void everyRowIsSignedFromTheSubjectsSideOfIt() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        // Alice: +100 starting balance, -40 paid to Bob, +5 paid by another mod, -10 charged by one.
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 40, TransactionType.PLAYER_PAYMENT));
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 5));
        assertTrue(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 10));
        List<SubjectLedgerRow> alice = data.subjectHistory(ALICE, 100);
        assertEquals(4, alice.size());
        long sum = alice.stream().mapToLong(SubjectLedgerRow::signedAmount).sum();
        // The signed rows add up to the balance, which is what lets the app's sparkline be rebuilt.
        assertEquals(data.getBalance(ALICE, NeroEconomyCommon.CREDITS), sum);
        assertEquals(55L, sum);
        SubjectLedgerRow payment = alice.stream().filter(row -> row.type() == TransactionType.PLAYER_PAYMENT)
                .findFirst().orElseThrow();
        assertFalse(payment.incoming());
        assertEquals(-40L, payment.signedAmount());
        // The same transfer, seen from the other side.
        SubjectLedgerRow received = data.subjectHistory(BOB, 100).stream()
                .filter(row -> row.type() == TransactionType.PLAYER_PAYMENT).findFirst().orElseThrow();
        assertTrue(received.incoming());
        assertEquals(40L, received.signedAmount());
        assertEquals(NeroEconomyCommon.CREDITS.id().toString(), received.currency());
    }

    @Test void itFollowsTheSameLimitsAsHistoryAndNeverInventsRows() {
        EconomySavedData data = Ledgers.seeded();
        assertTrue(data.subjectHistory(ALICE, 100).isEmpty());
        assertTrue(data.subjectHistory(null, 100).isEmpty());
        for (int index = 0; index < 5; index++) assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 1));
        assertTrue(data.subjectHistory(ALICE, 0).isEmpty());
        assertEquals(3, data.subjectHistory(ALICE, 3).size());
        assertEquals(data.history(ALICE, 100).size(), data.subjectHistory(ALICE, 100).size());
        // Newest first, like history().
        List<SubjectLedgerRow> rows = data.subjectHistory(ALICE, 100);
        for (int index = 1; index < rows.size(); index++)
            assertTrue(rows.get(index - 1).timestamp() >= rows.get(index).timestamp());
        // An account that opted out of logging has no rows of its own to show.
        data.setLoggingOptOut(BOB, true);
        assertTrue(data.deposit(BOB, NeroEconomyCommon.CREDITS, 9));
        assertTrue(data.subjectHistory(BOB, 100).isEmpty());
    }

    @Test void theRowTypeCannotCarryAnAccountReference() {
        // The record has no field that could hold a pseudonym: reason, amount, direction, currency, time.
        var components = SubjectLedgerRow.class.getRecordComponents();
        assertEquals(List.of("type", "amount", "incoming", "currency", "timestamp"),
                java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).toList());
        for (var component : components)
            if (component.getType() == String.class) assertEquals("currency", component.getName());
    }
}
