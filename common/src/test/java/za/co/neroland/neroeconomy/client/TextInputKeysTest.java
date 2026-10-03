package za.co.neroland.neroeconomy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.client.TextInputKeys.Route;

class TextInputKeysTest {
    @Test void aKeyTypedIntoATextBoxNeverReachesTheScreenHotkeys() {
        // The inventory key, a hotbar number, drop, pick: all just letters while typing.
        assertEquals(Route.TEXT, TextInputKeys.route(false, true, false));
    }

    @Test void escapeAlwaysClosesTheScreen() {
        assertEquals(Route.SCREEN, TextInputKeys.route(true, true, false));
        assertEquals(Route.SCREEN, TextInputKeys.route(true, false, false));
        assertEquals(Route.SCREEN, TextInputKeys.route(true, true, true));
    }

    @Test void tabMovesBetweenFieldsWithoutFiringAHotkey() {
        assertEquals(Route.FOCUS, TextInputKeys.route(false, true, true));
    }

    @Test void withNoTextBoxFocusedTheScreenBehavesAsUsual() {
        assertEquals(Route.SCREEN, TextInputKeys.route(false, false, false));
        assertEquals(Route.SCREEN, TextInputKeys.route(false, false, true));
    }
}
