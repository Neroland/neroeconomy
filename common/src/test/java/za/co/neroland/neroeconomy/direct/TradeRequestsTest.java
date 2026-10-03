package za.co.neroland.neroeconomy.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.direct.TradeRequests.Request;
import za.co.neroland.neroeconomy.direct.TradeRequests.Result;

/** Waiting trade requests: the timeout, the per-requester cooldown, one per pair, and the flood cap. */
class TradeRequestsTest {
    private static final UUID ALICE = new UUID(1L, 1L);
    private static final UUID BOB = new UUID(1L, 2L);
    private static final UUID CAROL = new UUID(1L, 3L);
    private final TradeRequests requests = new TradeRequests();

    @Test void aRequestWaitsThirtySecondsForItsAnswer() {
        assertEquals(30_000L, TradeRequests.TIMEOUT_MILLIS);
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 1_000L));
        assertEquals(List.of(ALICE), requests.askers(BOB, 30_999L));
        assertTrue(requests.askers(ALICE, 30_999L).isEmpty());
        // At thirty seconds it has lapsed, and the requester is the one to be told.
        assertEquals(List.of(new Request(ALICE, BOB, 31_000L)), requests.expire(31_000L));
        assertTrue(requests.expire(31_000L).isEmpty());
        assertNull(requests.answer(BOB, ALICE, 31_000L));
        assertEquals(0, requests.size());
    }

    @Test void anAnswerTakesTheRequestOutOfTheListExactlyOnce() {
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 0L));
        assertEquals(Result.SENT, requests.request(CAROL, BOB, true, 100L));
        // Only the player who was asked can answer, and only for a request that is there.
        assertNull(requests.answer(ALICE, BOB, 200L));
        assertNull(requests.answer(BOB, BOB, 200L));
        assertNull(requests.answer(null, ALICE, 200L));
        assertEquals(new Request(ALICE, BOB, 30_000L), requests.answer(BOB, ALICE, 200L));
        assertNull(requests.answer(BOB, ALICE, 200L));
        // With nobody named, the newest request waiting is the one answered.
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 6_000L));
        assertEquals(ALICE, requests.answer(BOB, null, 6_100L).from());
        assertEquals(CAROL, requests.answer(BOB, null, 6_100L).from());
        assertNull(requests.answer(BOB, null, 6_100L));
    }

    @Test void aPlayerCannotAskThemselves() {
        assertEquals(Result.SELF, requests.request(ALICE, ALICE, true, 0L));
        assertEquals(Result.SELF, requests.request(null, ALICE, true, 0L));
        assertEquals(Result.SELF, requests.request(ALICE, null, true, 0L));
        assertEquals(0, requests.size());
        // A refusal of that kind does not start the cooldown.
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 0L));
    }

    @Test void oneRequesterSendsOneRequestEveryFiveSeconds() {
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 0L));
        assertEquals(Result.COOLDOWN, requests.request(ALICE, CAROL, true, 4_999L));
        assertEquals(Result.SENT, requests.request(ALICE, CAROL, true, 5_000L));
        // Somebody else is not held back by it.
        assertEquals(Result.SENT, requests.request(BOB, CAROL, true, 5_001L));
        // A refused request uses the cooldown up too, so muting cannot be probed quickly.
        UUID dave = new UUID(1L, 4L);
        assertEquals(Result.NOT_ACCEPTING, requests.request(CAROL, dave, false, 20_000L));
        assertEquals(Result.COOLDOWN, requests.request(CAROL, dave, true, 20_001L));
        assertEquals(Result.SENT, requests.request(CAROL, dave, true, 25_000L));
    }

    @Test void onlyOneRequestWaitsBetweenTwoPlayers() {
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 0L));
        // The same request again, however long after: it is still waiting, and nothing is sent.
        assertEquals(Result.PAIR_PENDING, requests.request(ALICE, BOB, true, 1L));
        assertEquals(Result.PAIR_PENDING, requests.request(ALICE, BOB, true, 20_000L));
        assertEquals(1, requests.size());
        // Once it has lapsed a new one can be sent.
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 30_000L));
        assertEquals(1, requests.size());
    }

    @Test void askingSomebodyWhoAlreadyAskedYouIsAnAcceptance() {
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 0L));
        assertEquals(Result.CROSSED, requests.request(BOB, ALICE, true, 500L));
        // The request is answered: nothing waits between them any more.
        assertEquals(0, requests.size());
        assertNull(requests.answer(BOB, ALICE, 600L));
        // Even when the one answering has muted requests: they asked for this trade themselves.
        assertEquals(Result.SENT, requests.request(CAROL, BOB, true, 0L));
        assertEquals(Result.CROSSED, requests.request(BOB, CAROL, false, 700L));
    }

    @Test void aMutedOrFloodedPlayerIsNotAskedAndTheRequesterIsToldTheSameEitherWay() {
        assertEquals(Result.NOT_ACCEPTING, requests.request(ALICE, BOB, false, 0L));
        assertTrue(requests.askers(BOB, 0L).isEmpty());
        // Eight requests wait for one player; the ninth is refused in the same words.
        for (int index = 0; index < TradeRequests.MAX_WAITING; index++)
            assertEquals(Result.SENT, requests.request(new UUID(9L, index), CAROL, true, 100L));
        assertEquals(Result.NOT_ACCEPTING, requests.request(new UUID(9L, 99L), CAROL, true, 100L));
        assertEquals(TradeRequests.MAX_WAITING, requests.askers(CAROL, 100L).size());
        // One answered makes room for one more.
        assertEquals(new UUID(9L, 3L), requests.answer(CAROL, new UUID(9L, 3L), 200L).from());
        assertEquals(Result.SENT, requests.request(new UUID(9L, 100L), CAROL, true, 200L));
    }

    @Test void nothingAboutAPlayerOutlivesTheirLogoutOrTheServer() {
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 0L));
        assertEquals(Result.SENT, requests.request(CAROL, ALICE, true, 0L));
        assertEquals(Result.SENT, requests.request(BOB, CAROL, true, 0L));
        assertEquals(3, requests.tracked());
        requests.forget(ALICE);
        // Both what she asked and what she was asked are gone, and so is her cooldown.
        assertEquals(1, requests.size());
        assertEquals(2, requests.tracked());
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 1L));
        requests.forget(null);
        requests.clear();
        assertEquals(0, requests.size());
        assertEquals(0, requests.tracked());
        // The memory of a cooldown is dropped as soon as it has run out.
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, 0L));
        requests.expire(40_000L);
        assertEquals(0, requests.tracked());
    }

    @Test void aClockThatDoesNotMoveOrRunsToTheEndNeverBreaksARequest() {
        assertEquals(Result.SENT, requests.request(ALICE, BOB, true, Long.MAX_VALUE - 5L));
        assertEquals(Long.MAX_VALUE, requests.answer(BOB, ALICE, Long.MAX_VALUE - 5L).expiresAt());
        assertEquals(Result.COOLDOWN, requests.request(ALICE, CAROL, true, Long.MAX_VALUE - 5L));
    }
}
