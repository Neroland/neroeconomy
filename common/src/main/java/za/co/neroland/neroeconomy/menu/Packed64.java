package za.co.neroland.neroeconomy.menu;

import java.util.function.IntUnaryOperator;

/**
 * Carries a 64-bit value through vanilla menu data slots.
 *
 * <p>The data-slot packet ({@code ClientboundContainerSetDataPacket}) writes each value with
 * {@code writeShort} and reads it back with {@code readShort}, so a slot delivers 16 bits and the
 * client sees them sign-extended. A balance or price therefore takes {@link #SLOTS} consecutive
 * slots, least significant first. {@link #combine} masks every part, so the sign extension the
 * client receives cannot leak into the neighbouring bits. Pure Java so it is unit tested directly.
 */
public final class Packed64 {
    /** Data slots one 64-bit value occupies. */
    public static final int SLOTS = 4;
    /** Data slots one 32-bit value occupies. */
    public static final int INT_SLOTS = 2;
    private static final long MASK = 0xFFFFL;

    private Packed64() { }

    /** The {@code index}-th 16-bit part of {@code value} (0 = least significant), as 0..65535. */
    public static int get(long value, int index) {
        if (index < 0 || index >= SLOTS) return 0;
        return (int) (value >>> (index * 16) & MASK);
    }

    /** Rebuilds a value from its four parts, each as sent by {@link #get} or as received sign-extended. */
    public static long combine(int a, int b, int c, int d) {
        return a & MASK | (b & MASK) << 16 | (c & MASK) << 32 | (d & MASK) << 48;
    }

    /** Reads the 64-bit value stored in the four slots starting at {@code first}. */
    public static long read(IntUnaryOperator slots, int first) {
        return combine(slots.applyAsInt(first), slots.applyAsInt(first + 1), slots.applyAsInt(first + 2),
                slots.applyAsInt(first + 3));
    }

    /** Reads the 32-bit value stored in the two slots starting at {@code first}. */
    public static int readInt(IntUnaryOperator slots, int first) {
        return (int) combine(slots.applyAsInt(first), slots.applyAsInt(first + 1), 0, 0);
    }
}
