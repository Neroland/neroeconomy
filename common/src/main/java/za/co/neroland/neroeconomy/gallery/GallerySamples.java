package za.co.neroland.neroeconomy.gallery;

import java.util.List;
import java.util.Locale;

/**
 * The made-up data the gallery shows. Pure: no game classes, so every rule here is tested without
 * Minecraft. Nothing in this class is a price, a balance or a rule of the real economy; it is
 * sample text and sample rows that live only in the {@link GallerySandbox}.
 */
public final class GallerySamples {
    private GallerySamples() { }

    // --- board rows --------------------------------------------------------------------------

    /** One sample Market Board row: what it offers or wants, how many and at what unit price. */
    public record BoardRow(boolean sell, String itemId, int quantity, long unitPrice) { }

    /** Two sell rows and two buy rows, as the four-cluster gallery always showed. */
    public static final List<BoardRow> BOARD_ROWS = List.of(
            new BoardRow(true, "minecraft:diamond", 16, 210L),
            new BoardRow(true, "minecraft:copper_ingot", 64, 4L),
            new BoardRow(false, "minecraft:redstone", 64, 3L),
            new BoardRow(false, "minecraft:emerald", 16, 90L));

    // --- tickers -----------------------------------------------------------------------------

    /** A sample exchange ticker. The symbols are invented and match no listed stock. */
    public record Ticker(String symbol, long basePrice) { }

    public static final List<Ticker> TICKERS = List.of(
            new Ticker("SMPL", 120L),
            new Ticker("DEMO", 45L),
            new Ticker("TEST", 18L));

    /** The sample walk every ticker follows, offset per ticker: a small wobble around its base price. */
    private static final int[] WALK = {0, 2, 3, 1, -1, -3, -2, 1, 4, 2, 0, -2};

    /** A ticker's sample price at a refresh step; never below one credit. Unknown index: 0. */
    public static long tickerPrice(int index, long step) {
        if (index < 0 || index >= TICKERS.size()) return 0L;
        long base = TICKERS.get(index).basePrice();
        return Math.max(1L, base + WALK[walkSlot(index, step)]);
    }

    /** -1, 0 or 1: how the ticker moved since the step before. */
    public static int tickerMove(int index, long step) {
        if (index < 0 || index >= TICKERS.size()) return 0;
        return Long.signum(tickerPrice(index, step) - tickerPrice(index, step - 1L));
    }

    private static int walkSlot(int index, long step) {
        return (int) Math.floorMod(step + index * 3L, (long) WALK.length);
    }

    /** The lang key for a ticker's move: up, down or flat. */
    public static String moveKey(int move) {
        return "command.neroeconomy.gallery.label.move." + (move > 0 ? "up" : move < 0 ? "down" : "flat");
    }

    // --- the Auto-Trader loop ----------------------------------------------------------------

    /** Copper ingots the sample rule "sells" each cycle. */
    public static final int LOOP_SELL_QUANTITY = 16;
    /** Iron ingots the sample rule "buys" each cycle. */
    public static final int LOOP_BUY_QUANTITY = 4;
    /** How long each phase of the sample loop shows. */
    public static final long LOOP_PHASE_MILLIS = 8_000L;

    /** What the sample rule is shown doing. */
    public enum LoopPhase {
        SELLING, BUYING, WAITING;

        /** The lang key of the phase's word on the label. */
        public String key() {
            return "command.neroeconomy.gallery.label.loop." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** The sample status and counters of the loop: shown on a label, never a rule, never a trade. */
    public record LoopState(LoopPhase phase, long sold, long bought) { }

    /**
     * Where the sample loop stands {@code elapsedMillis} after the gallery was built. A phase
     * counts once it is over: the copper is "sold" when the selling phase ends, the iron "bought"
     * when the buying phase ends. A negative time (a clock that went back) reads as the start.
     */
    public static LoopState loopAt(long elapsedMillis) {
        long steps = Math.max(0L, elapsedMillis) / LOOP_PHASE_MILLIS;
        LoopPhase phase = LoopPhase.values()[(int) (steps % 3L)];
        long cycles = steps / 3L;
        long sold = (cycles + (phase.ordinal() >= 1 ? 1L : 0L)) * LOOP_SELL_QUANTITY;
        long bought = (cycles + (phase.ordinal() >= 2 ? 1L : 0L)) * LOOP_BUY_QUANTITY;
        return new LoopState(phase, sold, bought);
    }
}
