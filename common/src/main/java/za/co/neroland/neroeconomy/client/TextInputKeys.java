package za.co.neroland.neroeconomy.client;

/**
 * Where a key press goes while a NeroEconomy screen is open. A container screen treats the inventory
 * key as "close" and the hotbar, drop, pick and swap keys as slot actions, and it does so before any
 * text box hears about the key. Typing a recipient, an amount or a search must not trigger any of
 * that, so while a text box has focus the screen's own hotkeys are bypassed entirely. Pure Java so
 * the routing is unit tested directly.
 */
public final class TextInputKeys {
    public enum Route {
        /** The screen handles the key as usual: hotkeys, focus traversal, close. */
        SCREEN,
        /** The focused text box gets the key and the screen acts on nothing. */
        TEXT,
        /** Tab moves focus to the next field; no hotkey fires. */
        FOCUS
    }

    private TextInputKeys() { }

    /**
     * @param escape      whether the key is Escape, which always closes the screen
     * @param textFocused whether a text box that accepts input has focus
     * @param tab         whether the key is Tab
     */
    public static Route route(boolean escape, boolean textFocused, boolean tab) {
        if (escape || !textFocused) return Route.SCREEN;
        return tab ? Route.FOCUS : Route.TEXT;
    }
}
