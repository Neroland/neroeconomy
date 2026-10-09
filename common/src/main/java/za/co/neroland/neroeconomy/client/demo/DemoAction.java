package za.co.neroland.neroeconomy.client.demo;

/**
 * One thing a demo step does to its sample view before it is shown, the way a player would: type
 * into a box, press a button, or click a row. It only ever changes the demo view on screen; the demo
 * view's intents go to {@link DemoIntents}, which sends nothing. Pure Java.
 *
 * @param kind   what is done
 * @param target where, in body coordinates
 * @param text   for {@link Kind#TYPE} the text typed; for {@link Kind#TYPE_TEXT} the lang key of the
 *               text typed; empty otherwise
 */
public record DemoAction(Kind kind, DemoTarget target, String text) {
    /** What a demo action does. */
    public enum Kind {
        /** Types {@code text} (digits) into the box at the target. */
        TYPE,
        /** Types the translation of the lang key {@code text} into the box at the target. */
        TYPE_TEXT,
        /** Presses the button at the target, if it is enabled. */
        PRESS,
        /** Clicks the view at the target's point, as a click on a row or a readout. */
        CLICK
    }

    public DemoAction {
        if (kind == null || target == null) throw new IllegalArgumentException("incomplete action");
        text = text == null ? "" : text;
    }

    public static DemoAction type(DemoTarget box, String digits) { return new DemoAction(Kind.TYPE, box, digits); }

    public static DemoAction typeText(DemoTarget box, String key) { return new DemoAction(Kind.TYPE_TEXT, box, key); }

    public static DemoAction press(DemoTarget button) { return new DemoAction(Kind.PRESS, button, ""); }

    /** Clicks at a body point: a row of a list, or a cell of a strip. */
    public static DemoAction click(int x, int y) { return new DemoAction(Kind.CLICK, DemoTarget.area(x, y, 1, 1), ""); }
}
