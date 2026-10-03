package za.co.neroland.neroeconomy.goods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The prototype table with strings standing in for item stacks: "equal" here ignores case, the way
 * "same item and components" ignores the count in the game.
 */
class PrototypesTest {
    private static Prototypes<String> table() {
        return new Prototypes<>(String::equalsIgnoreCase, value -> value.toLowerCase(Locale.ROOT).hashCode());
    }

    @Test void equalPrototypesShareOneIdAndDifferentOnesNeverDo() {
        Prototypes<String> table = table();
        int sharp = table.intern("Sharpness V sword");
        int named = table.intern("Excalibur");
        assertEquals(1, sharp);
        assertEquals(2, named);
        // Equal under the table's rule, however it is spelled: the same id, and nothing new stored.
        assertEquals(sharp, table.intern("SHARPNESS v SWORD"));
        assertEquals(2, table.size());
        assertNotEquals(sharp, named);
        // Payload 0 is never handed out: it means "unmodified".
        assertNotEquals(Lot.PRISTINE, sharp);
        assertEquals("Excalibur", table.get(named));
        assertNull(table.get(99));
        assertNull(table.get(Lot.PRISTINE));
    }

    @Test void findLooksWithoutStoring() {
        Prototypes<String> table = table();
        assertEquals(Lot.PRISTINE, table.find("Excalibur"));
        assertEquals(0, table.size());
        assertEquals(1, table.nextId());
        int id = table.intern("Excalibur");
        assertEquals(id, table.find("excalibur"));
        assertEquals(2, table.nextId());
    }

    @Test void collidingHashesAreToldApartByEquality() {
        // Every value hashes the same; only equality separates them.
        Prototypes<String> table = new Prototypes<>(String::equals, value -> 42);
        int a = table.intern("a");
        int b = table.intern("b");
        int c = table.intern("c");
        assertEquals(Set.of(1, 2, 3), Set.of(a, b, c));
        assertEquals(b, table.intern("b"));
        table.retain(Set.of(a, c));
        assertEquals(Lot.PRISTINE, table.find("b"));
        assertEquals(a, table.find("a"));
        assertEquals(c, table.find("c"));
    }

    @Test void unusedPrototypesAreDroppedAndTheirIdsAreNeverUsedAgain() {
        Prototypes<String> table = table();
        int one = table.intern("one");
        int two = table.intern("two");
        int three = table.intern("three");
        assertEquals(2, table.retain(Set.of(two)));
        assertFalse(table.contains(one));
        assertTrue(table.contains(two));
        assertFalse(table.contains(three));
        // A lot still naming a dropped id must never come to mean other goods.
        int four = table.intern("four");
        assertEquals(4, four);
        // The dropped value itself gets a fresh id when it is seen again.
        assertEquals(5, table.intern("one"));
        assertEquals(0, table.retain(Set.of(two, four, 5)));
        assertEquals(3, table.retain(Set.of()));
        assertEquals(0, table.size());
        assertEquals(6, table.nextId());
    }

    @Test void aSavedTableComesBackUnderTheSameIds() {
        Prototypes<String> saved = table();
        saved.intern("one");
        int two = saved.intern("two");
        saved.intern("three");
        saved.retain(Set.of(two));
        Map<Integer, String> rows = saved.snapshot();
        Prototypes<String> restored = table();
        rows.forEach((id, value) -> assertTrue(restored.restore(id, value)));
        restored.restoreNextId(saved.nextId());
        assertEquals("two", restored.get(two));
        assertEquals(two, restored.find("TWO"));
        // Ids handed out before the save stay used, even though their prototypes are gone.
        assertEquals(4, restored.intern("four"));
    }

    @Test void aBadSavedRowCannotShadowAnother() {
        Prototypes<String> table = table();
        assertTrue(table.restore(5, "five"));
        assertFalse(table.restore(5, "impostor"));
        assertFalse(table.restore(0, "zero"));
        assertFalse(table.restore(-2, "negative"));
        assertFalse(table.restore(6, null));
        assertEquals("five", table.get(5));
        // The next id is past the highest restored one, with or without a saved counter.
        assertEquals(6, table.nextId());
        table.restoreNextId(3);
        assertEquals(6, table.nextId());
        table.restoreNextId(9);
        assertEquals(9, table.intern("nine"));
    }
}
