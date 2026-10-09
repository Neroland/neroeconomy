package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

/** The daily remote spending limit: what a stolen app token can cost a player at most. */
class RemoteSpendSectionTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long LIMIT = 5_000L;
    private static final long DAY = 20_000L;

    /** The way the module uses the section: check the most an action could spend, then count what it did. */
    private static boolean spend(RemoteSpendSection section, UUID player, long day, long bound, long actual) {
        if (!section.allows(player, day, bound, LIMIT)) return false;
        section.record(player, day, actual);
        return true;
    }

    @Test void aStolenTokenCanSpendAtMostTheDailyLimitWhateverItSends() {
        RemoteSpendSection section = new RemoteSpendSection();
        long taken = 0L;
        // Every kind of request an attacker could try: small, large, exactly the rest, past it, overflow-sized.
        long[] attempts = {1L, 999L, 2_500L, 4_999L, 5_000L, 5_001L, Long.MAX_VALUE, 1_499L, 1L, 1L, 3L, 0L};
        for (int round = 0; round < 50; round++)
            for (long attempt : attempts) if (spend(section, ALICE, DAY, attempt, attempt)) taken += attempt;
        assertTrue(taken <= LIMIT, "spent " + taken);
        assertEquals(LIMIT, taken, "an honest player can still use the whole limit");
        assertFalse(section.allows(ALICE, DAY, 1L, LIMIT));
        // The next economy-day starts afresh; the day before counts as nothing.
        assertTrue(section.allows(ALICE, DAY + 1, LIMIT, LIMIT));
        assertEquals(0L, section.spentToday(ALICE, DAY + 1));
    }

    @Test void oneAccountsSpendingNeverCountsAgainstAnother() {
        RemoteSpendSection section = new RemoteSpendSection();
        assertTrue(spend(section, ALICE, DAY, LIMIT, LIMIT));
        assertTrue(section.allows(BOB, DAY, LIMIT, LIMIT));
        assertEquals(0L, section.spentToday(BOB, DAY));
    }

    @Test void aZeroLimitAllowsNothingButActionsThatSpendNothing() {
        RemoteSpendSection section = new RemoteSpendSection();
        assertFalse(section.allows(ALICE, DAY, 1L, 0L));
        assertTrue(section.allows(ALICE, DAY, 0L, 0L));
        assertFalse(section.allows(ALICE, DAY, -1L, LIMIT), "a negative bound is never allowed");
        assertFalse(section.allows(null, DAY, 1L, LIMIT));
    }

    @Test void recordingSaturatesInsteadOfOverflowing() {
        RemoteSpendSection section = new RemoteSpendSection();
        section.record(ALICE, DAY, Long.MAX_VALUE - 1L);
        section.record(ALICE, DAY, 10L);
        assertEquals(Long.MAX_VALUE, section.spentToday(ALICE, DAY));
        assertFalse(section.allows(ALICE, DAY, 1L, Long.MAX_VALUE));
    }

    @Test void onlyTodaysRowsAreKeptAndAFullTableRefusesNewAccountsWithoutResettingOldOnes() {
        RemoteSpendSection section = new RemoteSpendSection();
        section.record(ALICE, DAY - 1, 100L);
        section.record(BOB, DAY, 100L);
        assertEquals(1, section.size(), "yesterday's row went when today's was written");
        for (int index = 0; section.size() < RemoteSpendSection.MAX_ACCOUNTS; index++)
            section.record(new UUID(7L, index), DAY, 1L);
        UUID late = new UUID(9L, 9L);
        assertFalse(section.allows(late, DAY, 1L, LIMIT), "a full table refuses a new account");
        section.record(late, DAY, 1L);
        assertEquals(0L, section.spentToday(late, DAY));
        assertEquals(100L, section.spentToday(BOB, DAY), "nobody already counted is pushed out");
        // The next day the old rows go, and there is room again.
        assertTrue(section.allows(late, DAY + 1, 1L, LIMIT));
    }

    @Test void erasureAndExportCoverThePlayersRowOnly() {
        RemoteSpendSection section = new RemoteSpendSection();
        section.record(ALICE, DAY, 1_234L);
        section.record(BOB, DAY, 10L);
        JsonObject export = section.exportFor(ALICE);
        assertEquals(DAY, export.get("economy_day").getAsLong());
        assertEquals(1_234L, export.get("spent").getAsLong());
        assertEquals(0L, export.get("goods_value").getAsLong());
        assertEquals(3, export.size());
        assertFalse(export.toString().contains(BOB.toString()));
        section.forgetPlayer(ALICE);
        assertEquals(0L, section.spentToday(ALICE, DAY));
        assertEquals(0, section.exportFor(ALICE).size());
        assertEquals(10L, section.spentToday(BOB, DAY));
        section.forgetPlayer(UUID.randomUUID());
    }

    @Test void goodsAreCountedApartFromCreditsAndBoundedTheSameWay() {
        RemoteSpendSection section = new RemoteSpendSection();
        section.record(ALICE, DAY, 4_000L);
        assertTrue(section.allowsGoods(ALICE, DAY, LIMIT, LIMIT), "credits spent do not use up the goods limit");
        section.recordGoods(ALICE, DAY, 4_999L);
        assertEquals(4_000L, section.spentToday(ALICE, DAY));
        assertEquals(4_999L, section.goodsToday(ALICE, DAY));
        assertTrue(section.allowsGoods(ALICE, DAY, 1L, LIMIT));
        assertFalse(section.allowsGoods(ALICE, DAY, 2L, LIMIT));
        assertFalse(section.allowsGoods(ALICE, DAY, -1L, LIMIT));
        assertFalse(section.allowsGoods(ALICE, DAY, 1L, 0L), "a zero limit lets no goods leave");
        assertTrue(section.allowsGoods(ALICE, DAY, 0L, 0L));
        section.recordGoods(ALICE, DAY, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, section.goodsToday(ALICE, DAY), "saturates");
        assertEquals(1_000L + 3_000L, section.spentToday(ALICE, DAY), "the credit sum is left alone");
        assertTrue(section.allowsGoods(ALICE, DAY + 1, LIMIT, LIMIT), "the next day starts afresh");
        assertEquals(Long.MAX_VALUE, section.exportFor(ALICE).get("goods_value").getAsLong());
        section.forgetPlayer(ALICE);
        assertEquals(0L, section.goodsToday(ALICE, DAY));
    }

    @Test void aGoodsOnlyRowSurvivesASaveAndAnOlderRowLoadsWithNoGoods() {
        RemoteSpendSection section = new RemoteSpendSection();
        section.recordGoods(ALICE, DAY, 640L);
        JsonElement encoded = RemoteSpendSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow();
        RemoteSpendSection restored = RemoteSpendSection.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(640L, restored.goodsToday(ALICE, DAY));
        assertEquals(0L, restored.spentToday(ALICE, DAY));
        RemoteSpendSection older = RemoteSpendSection.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"spends\":[{\"player\":\"" + ALICE + "\",\"day\":" + DAY + ",\"spent\":5}]}")).getOrThrow();
        assertEquals(5L, older.spentToday(ALICE, DAY));
        assertEquals(0L, older.goodsToday(ALICE, DAY));
    }

    @Test void itSurvivesASaveAndAnOlderStoreLoadsItEmpty() {
        RemoteSpendSection section = new RemoteSpendSection();
        section.record(ALICE, DAY, 700L);
        JsonElement encoded = RemoteSpendSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow();
        RemoteSpendSection restored = RemoteSpendSection.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(700L, restored.spentToday(ALICE, DAY));
        RemoteSpendSection empty = RemoteSpendSection.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
        assertTrue(empty.isEmpty());
        // A hand-edited row with a bad id or a negative sum is dropped, not trusted.
        RemoteSpendSection edited = RemoteSpendSection.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"spends\":[{\"player\":\"nope\",\"day\":1,\"spent\":5},{\"player\":\"" + BOB
                        + "\",\"day\":1,\"spent\":-5}]}")).getOrThrow();
        assertTrue(edited.isEmpty());
    }
}
