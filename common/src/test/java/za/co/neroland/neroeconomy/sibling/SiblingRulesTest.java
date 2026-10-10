package za.co.neroland.neroeconomy.sibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SiblingRulesTest {
    @Test void idsAndReasonsAreNarrow() {
        assertTrue(SiblingRules.validModId("neroquests"));
        assertTrue(SiblingRules.validModId("my_mod.addon-2"));
        assertFalse(SiblingRules.validModId("NeroQuests"));
        assertFalse(SiblingRules.validModId(""));
        assertFalse(SiblingRules.validModId("a".repeat(65)));
        assertTrue(SiblingRules.validReason("quest_reward"));
        assertFalse(SiblingRules.validReason("quest-reward"));
        assertFalse(SiblingRules.validReason("a".repeat(33)));
    }

    @Test void aRequestKeyNeverReadsAsAPlayerId() {
        assertTrue(SiblingRules.validRequestKey("quest:42:stage:3"));
        assertFalse(SiblingRules.validRequestKey("123e4567-e89b-12d3-a456-426614174000"), "a UUID");
        assertFalse(SiblingRules.validRequestKey("q:123e4567e89b12d3a456426614174000"), "32 hex digits in a row");
        assertFalse(SiblingRules.validRequestKey("1-1-1-1-1"), "anything UUID.fromString accepts");
        assertFalse(SiblingRules.validRequestKey("a".repeat(65)));
        assertFalse(SiblingRules.validRequestKey("has space"));
        assertFalse(SiblingRules.validRequestKey(""));
        assertFalse(SiblingRules.validRequestKey(null));
    }

    @Test void originsAreModAndReason() {
        assertEquals("neroquests/quest_reward", SiblingRules.origin("neroquests", "quest_reward"));
        assertTrue(SiblingRules.validOrigin("neroquests/quest_reward"));
        assertFalse(SiblingRules.validOrigin("Alice paid Bob"));
        assertFalse(SiblingRules.validOrigin(""));
        assertEquals("neroquests", SiblingRules.originMod("neroquests/quest_reward"));
    }

    @Test void theFeeRoundsUpAndIsAtLeastOne() {
        assertEquals(5L, SiblingRules.fee(100L, 5.0D));
        assertEquals(2L, SiblingRules.fee(21L, 5.0D), "1.05 rounds up");
        assertEquals(1L, SiblingRules.fee(1L, 5.0D), "at least 1");
        assertEquals(0L, SiblingRules.fee(100L, 0.0D));
        assertEquals(1L, SiblingRules.fee(1L, 50.0D), "never more than the amount");
        assertEquals(Long.MAX_VALUE / 2 + 1, SiblingRules.fee(Long.MAX_VALUE, 50.0D), "exact on the largest amount");
    }

    @Test void overridesParseAndSkipWhatIsBad() {
        Map<String, Long> parsed = SiblingRules.parseBudgets(" neroquests=3000, nerocolonies = 1500,bad, unknown=9,"
                + "neroquests=1, x=-1, y=abc");
        assertEquals(Map.of("neroquests", 3000L, "nerocolonies", 1500L), parsed);
        assertEquals(3000L, SiblingRules.callerLimit("neroquests", 2500L, parsed));
        assertEquals(2500L, SiblingRules.callerLimit("nerofactions", 2500L, parsed));
        assertTrue(SiblingRules.parseBudgets("").isEmpty());
    }

    @Test void claimsAreCheckedOnceAndCloseAtStart() {
        Set<String> loaded = new HashSet<>(Set.of("neroquests", "nerocolonies"));
        CallerRegistry registry = new CallerRegistry(loaded::contains);
        assertThrows(IllegalArgumentException.class, () -> registry.claim("Bad Id", Set.of("x")));
        assertThrows(IllegalArgumentException.class, () -> registry.claim("neroeconomy", Set.of("x")));
        assertThrows(IllegalArgumentException.class, () -> registry.claim("unknown", Set.of("x")));
        assertThrows(IllegalArgumentException.class, () -> registry.claim("neroquests", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.claim("neroquests", Set.of("Bad-Reason")));
        Set<String> many = new HashSet<>();
        for (int index = 0; index < SiblingRules.MAX_REASONS + 1; index++) many.add("r" + index);
        assertThrows(IllegalArgumentException.class, () -> registry.claim("neroquests", many));
        assertThrows(IllegalStateException.class, () -> registry.claim("nerofactions", Set.of("x")), "not loaded");
        assertEquals(Set.of("quest_reward"), registry.claim("neroquests", Set.of("quest_reward")));
        assertThrows(IllegalStateException.class, () -> registry.claim("neroquests", Set.of("x")), "claimed twice");
        assertTrue(registry.claimed("neroquests"));
        registry.close();
        assertThrows(IllegalStateException.class, () -> registry.claim("nerocolonies", Set.of("x")), "after start");
        assertFalse(registry.claimed("nerocolonies"));
    }
}
