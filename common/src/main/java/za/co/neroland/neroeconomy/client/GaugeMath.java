package za.co.neroland.neroeconomy.client;

import java.util.Locale;

/**
 * Pure number formatting and rate maths behind {@link GaugeTooltips} (every gauge tooltip
 * gives name, amount / capacity with units, and rate). No game classes, so the unit tests run
 * on a bare JVM. Everything is machine-scoped numbers; nothing here touches player data.
 */
public final class GaugeMath {

    /** Client ticks per second (rates are shown per tick for energy, per second for heat and tanks). */
    public static final int TICKS_PER_SECOND = 20;

    private GaugeMath() {
    }

    /** {@code 1,234,567} — the exact amount with grouping (Locale.ROOT, so it never depends on the OS). */
    public static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * Compact amount: {@code 950}, {@code 1.2k}, {@code 12.5k}, {@code 125k}, {@code 3.4M}, {@code 1.1G}.
     * One decimal below 100 of a unit, none above; a trailing {@code .0} is dropped. Negative values keep
     * their sign.
     */
    public static String compact(long value) {
        if (value == Long.MIN_VALUE) {
            return "-" + compact(Long.MAX_VALUE);
        }
        long abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (abs < 1_000L) {
            return sign + abs;
        }
        String[] suffix = {"k", "M", "G", "T", "P", "E"};
        double scaled = abs;
        int unit = -1;
        while (scaled >= 1_000D && unit < suffix.length - 1) {
            scaled /= 1_000D;
            unit++;
        }
        // Rounding can carry 999.95k up to 1000k: move to the next unit instead.
        if (scaled >= 999.95D && unit < suffix.length - 1) {
            scaled /= 1_000D;
            unit++;
        }
        return sign + decimal(scaled, scaled < 100D ? 1 : 0) + suffix[unit];
    }

    /** A rate with an explicit sign: {@code +120}, {@code -80}, {@code 0}; compact above 1000. */
    public static String signedRate(double perUnit) {
        if (Double.isNaN(perUnit) || Double.isInfinite(perUnit)) {
            return "0";
        }
        double abs = Math.abs(perUnit);
        String body;
        if (abs < 0.05D) {
            return "0";
        } else if (abs < 10D) {
            body = decimal(abs, 1);
        } else if (abs < 1_000D) {
            body = Long.toString(Math.round(abs));
        } else {
            body = compact(Math.round(abs));
        }
        return (perUnit > 0 ? "+" : "-") + body;
    }

    /** An unsigned rate magnitude: {@code 120}, {@code 2.5}, {@code 1.2k}. */
    public static String rate(double perUnit) {
        String signed = signedRate(Math.abs(perUnit));
        return signed.startsWith("+") ? signed.substring(1) : signed;
    }

    /** Whole percent of {@code amount / capacity}, clamped to 0..100; 0 for a zero or negative capacity. */
    public static int percent(long amount, long capacity) {
        if (capacity <= 0) {
            return 0;
        }
        long pct = Math.round(Math.max(0L, amount) * 100D / capacity);
        return (int) Math.max(0L, Math.min(100L, pct));
    }

    /** An amount from a permille gauge and its capacity (legacy tank gauges sync permille only). */
    public static long fromPermille(int permille, long capacity) {
        if (capacity <= 0) {
            return 0L;
        }
        int p = Math.max(0, Math.min(1000, permille));
        return Math.round(capacity * (p / 1000D));
    }

    /** Gas units for an mB amount (one unit is {@code unitMb} mB; 0 when the unit is not positive). */
    public static double units(long mb, int unitMb) {
        return unitMb <= 0 ? 0D : (double) mb / unitMb;
    }

    /** A unit count: {@code 12}, {@code 2.5}; compact above 1000. */
    public static String unitCount(double units) {
        if (units < 0) {
            return "-" + unitCount(-units);
        }
        if (units >= 1_000D) {
            return compact(Math.round(units));
        }
        double rounded = Math.round(units * 10D) / 10D;
        return decimal(rounded, rounded == Math.rint(rounded) ? 0 : 1);
    }

    /**
     * Ticks until a progress gauge finishes at the current rate, or -1 when it cannot be estimated.
     * A positive rate counts up to 1000 (processing); a negative rate counts down to 0 (a generator's
     * remaining burn).
     */
    public static long ticksRemaining(int permille, double permillePerTick) {
        if (Double.isNaN(permillePerTick) || Math.abs(permillePerTick) < 1.0E-6D) {
            return -1L;
        }
        int p = Math.max(0, Math.min(1000, permille));
        double left = permillePerTick > 0 ? (1000 - p) / permillePerTick : p / -permillePerTick;
        return (long) Math.ceil(left);
    }

    /** {@code 0.5s}, {@code 9.5s}, {@code 42s}, {@code 3m 05s}, {@code 2h 03m}; {@code ?} for a negative input. */
    public static String duration(long ticks) {
        if (ticks < 0) {
            return "?";
        }
        double seconds = ticks / (double) TICKS_PER_SECOND;
        if (seconds < 10D) {
            return decimal(Math.max(0.1D, Math.ceil(seconds * 10D) / 10D), 1) + "s";
        }
        long whole = (long) Math.ceil(seconds);
        if (whole < 60L) {
            return whole + "s";
        }
        if (whole < 3_600L) {
            return String.format(Locale.ROOT, "%dm %02ds", whole / 60L, whole % 60L);
        }
        return String.format(Locale.ROOT, "%dh %02dm", whole / 3_600L, (whole % 3_600L) / 60L);
    }

    /** -1 cooling, 0 steady, +1 heating; a rate inside {@code ±deadband} counts as steady. */
    public static int trend(double perSecond, double deadband) {
        if (Double.isNaN(perSecond) || Math.abs(perSecond) <= Math.abs(deadband)) {
            return 0;
        }
        return perSecond > 0 ? 1 : -1;
    }

    /** The change from {@code first} to {@code last} in basis points (0 when {@code first} is not positive). */
    public static int changeBasisPoints(long first, long last) {
        if (first <= 0L) {
            return 0;
        }
        double bps = (last - first) * 10_000D / first;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(bps)));
    }

    /** Clamp a long into the int range (for 15-bit ContainerData splitting). */
    public static int clampInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static String decimal(double value, int places) {
        String text = String.format(Locale.ROOT, "%." + places + "f", value);
        if (places > 0 && text.endsWith(".0")) {
            return text.substring(0, text.length() - 2);
        }
        return text;
    }

    /**
     * A sliding window over the last ~{@value #WINDOW_TICKS} client ticks of one synced value, giving its
     * rate of change per tick from the oldest and newest samples. One sample per game tick (extra frames in
     * the same tick are ignored); a gap longer than {@value #GAP_TICKS} ticks (pause, lag spike, reopened
     * menu) or a jump larger than the reset threshold (a job boundary) starts the window again.
     */
    public static final class RateWindow {

        /** Ticks the window spans. */
        public static final int WINDOW_TICKS = 20;
        /** A gap at least this long between samples resets the window. */
        public static final int GAP_TICKS = 40;

        private final long[] ticks = new long[WINDOW_TICKS + 1];
        private final long[] values = new long[WINDOW_TICKS + 1];
        private final long jumpReset;
        private int head;
        private int size;

        /** A window that never resets on a jump. */
        public RateWindow() {
            this(Long.MAX_VALUE);
        }

        /** A window that resets when one tick's change exceeds {@code jumpReset} in magnitude. */
        public RateWindow(long jumpReset) {
            this.jumpReset = jumpReset <= 0 ? Long.MAX_VALUE : jumpReset;
        }

        /** Record {@code value} at game tick {@code tick}. */
        public void sample(long tick, long value) {
            if (this.size > 0) {
                int last = Math.floorMod(this.head - 1, this.ticks.length);
                long lastTick = this.ticks[last];
                if (tick == lastTick) {
                    return;
                }
                long delta = value - this.values[last];
                if (tick < lastTick || tick - lastTick >= GAP_TICKS
                        || (this.jumpReset != Long.MAX_VALUE && Math.abs(delta) > this.jumpReset)) {
                    reset();
                }
            }
            this.ticks[this.head] = tick;
            this.values[this.head] = value;
            this.head = (this.head + 1) % this.ticks.length;
            this.size = Math.min(this.ticks.length, this.size + 1);
            // Drop samples older than the window span.
            while (this.size > 2 && tick - this.ticks[oldestIndex()] > WINDOW_TICKS) {
                this.size--;
            }
        }

        /** Forget every sample. */
        public void reset() {
            this.size = 0;
            this.head = 0;
        }

        /** Number of samples held. */
        public int size() {
            return this.size;
        }

        /** Ticks between the oldest and newest sample (0 with fewer than two). */
        public long span() {
            if (this.size < 2) {
                return 0L;
            }
            return this.ticks[Math.floorMod(this.head - 1, this.ticks.length)] - this.ticks[oldestIndex()];
        }

        /** Change per tick over the window, or 0 with fewer than two samples. */
        public double perTick() {
            long span = span();
            if (span <= 0L) {
                return 0D;
            }
            long newest = this.values[Math.floorMod(this.head - 1, this.ticks.length)];
            long oldest = this.values[oldestIndex()];
            return (newest - oldest) / (double) span;
        }

        /** Change per second over the window. */
        public double perSecond() {
            return perTick() * TICKS_PER_SECOND;
        }

        private int oldestIndex() {
            return Math.floorMod(this.head - this.size, this.ticks.length);
        }
    }
}
