package za.co.neroland.neroeconomy.client.demo;

/**
 * The part of a tab's view a demo step points at, in the hub's body coordinates (see
 * {@code EconomyHubScreen}). A widget target is exactly a button's or a box's bounds, as the view
 * lays it out; an area target is a readout or a list. Pure Java.
 *
 * @param widget whether the target is a button or a text box (then the view's source lays out
 *               exactly {@code x, y, w, h}); otherwise a readout or list area
 */
public record DemoTarget(int x, int y, int w, int h, boolean widget) {
    public DemoTarget {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("empty target");
    }

    /** A button or a text box at exactly these bounds. */
    public static DemoTarget widget(int x, int y, int w, int h) { return new DemoTarget(x, y, w, h, true); }

    /** A readout or a list. */
    public static DemoTarget area(int x, int y, int w, int h) { return new DemoTarget(x, y, w, h, false); }

    /** The point a demo presses or types at: just inside the top-left corner. */
    public int pointX() { return x + 2; }

    public int pointY() { return y + 2; }

    /** The bounds as the view's source writes them: {@code "x, y, w, h"}. */
    public String bounds() { return x + ", " + y + ", " + w + ", " + h; }
}
