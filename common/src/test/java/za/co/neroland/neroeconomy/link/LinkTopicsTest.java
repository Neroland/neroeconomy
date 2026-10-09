package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

/** The schema-2 live topics: changes become events, at most one per topic per player per second. */
class LinkTopicsTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long SECOND = LinkTopics.MIN_INTERVAL_NANOS;
    private static final BiPredicate<UUID, Long> EVERYONE = (player, now) -> true;

    @Test void theFirstSignatureIsOnlyTheBaselineAndAChangeMakesTheTopicDue() {
        LinkTopics topics = new LinkTopics();
        topics.observe(ALICE, "orders", 1L, 0L);
        assertFalse(topics.hasPending());
        topics.observe(ALICE, "orders", 1L, 10L);
        assertFalse(topics.hasPending(), "an unchanged signature is no event");
        topics.observe(ALICE, "orders", 2L, 20L);
        assertEquals(List.of(new LinkTopics.Due(ALICE, "orders", null)), topics.due(30L, EVERYONE));
        assertFalse(topics.hasPending());
    }

    @Test void atMostOneEventPerTopicPerPlayerPerSecond() {
        LinkTopics topics = new LinkTopics();
        topics.observe(ALICE, "offers", 1L, 0L);
        topics.observe(ALICE, "offers", 2L, 0L);
        assertEquals(1, topics.due(0L, EVERYONE).size());
        topics.observe(ALICE, "offers", 3L, 10L);
        topics.observe(ALICE, "offers", 4L, 20L);
        assertTrue(topics.due(SECOND - 1L, EVERYONE).isEmpty(), "still inside the second");
        assertTrue(topics.hasPending(), "but not forgotten");
        assertEquals(1, topics.due(SECOND, EVERYONE).size(), "the coalesced change goes out once the second is up");
        // Another topic of the same player has its own second.
        topics.mark(ALICE, "alerts", "order_filled");
        assertEquals(List.of(new LinkTopics.Due(ALICE, "alerts", "order_filled")), topics.due(SECOND, EVERYONE));
    }

    @Test void aPlayerWhoseAppStoppedAskingGetsNoEventsAndIsDropped() {
        LinkTopics topics = new LinkTopics();
        topics.mark(BOB, "alerts", "outbid");
        assertTrue(topics.due(0L, (player, now) -> false).isEmpty());
        assertFalse(topics.hasPending());
    }

    @Test void signaturesAreReadAtMostOnceASecondPerPlayer() {
        LinkTopics topics = new LinkTopics();
        assertTrue(topics.checkDue(ALICE, 0L));
        assertFalse(topics.checkDue(ALICE, LinkTopics.CHECK_INTERVAL_NANOS - 1L));
        assertTrue(topics.checkDue(ALICE, LinkTopics.CHECK_INTERVAL_NANOS));
        assertTrue(topics.checkDue(BOB, 1L));
    }

    @Test void erasureAndUnbindLeaveNothingBehind() {
        LinkTopics topics = new LinkTopics();
        topics.observe(ALICE, "orders", 1L, 0L);
        topics.checkDue(ALICE, 0L);
        topics.mark(ALICE, "alerts", "vault_full");
        topics.observe(BOB, "orders", 1L, 0L);
        topics.forget(ALICE);
        assertEquals(1, topics.tracked());
        topics.clear();
        assertEquals(0, topics.tracked());
        assertFalse(topics.hasPending());
    }
}
