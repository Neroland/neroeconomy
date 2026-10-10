package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The cross-mod API's payouts, charges and transfers under the operation fuzzer, mixed with every
 * standard operation: every invariant is checked after every step (supply, escrow, no negative
 * balance), and a run with crashes ends exactly as one without.
 */
class SiblingFuzzTest {
    private static final int RUN = 6_000;

    @Test void theApiOperationsHoldEveryInvariantAndSurviveACrash() {
        Random picker = new Random(99L);
        Set<Integer> points = new HashSet<>();
        while (points.size() < 20) points.add(picker.nextInt(RUN));
        OperationFuzzer.Result uninterrupted = OperationFuzzer.run(5L, RUN, points, false, LedgerOperations.api());
        OperationFuzzer.Result crashed = OperationFuzzer.run(5L, RUN, points, true, LedgerOperations.api());
        assertEquals(uninterrupted.effects(), crashed.effects());
        assertEquals(uninterrupted.world().fingerprint(), crashed.world().fingerprint());
        assertTrue(uninterrupted.effects().get("api_pay") > 0, "no payout through the API was applied");
        assertTrue(uninterrupted.effects().get("api_charge") > 0, "no charge through the API was applied");
        assertTrue(uninterrupted.effects().get("api_transfer") > 0, "no transfer through the API was applied");
    }

    @Test void anotherSeedHoldsEveryInvariant() {
        OperationFuzzer.Result run = OperationFuzzer.run(11L, RUN, Set.of(), false, LedgerOperations.api());
        assertTrue(run.effects().get("api_pay") > 0);
    }
}
