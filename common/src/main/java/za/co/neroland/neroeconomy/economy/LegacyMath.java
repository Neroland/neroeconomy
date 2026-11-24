package za.co.neroland.neroeconomy.economy;

public final class LegacyMath {
    private LegacyMath() { }

    public static long ceilDiv(long value, long divisor) {
        long quotient = value / divisor;
        if ((value ^ divisor) >= 0 && value % divisor != 0) {
            return quotient + 1;
        }
        return quotient;
    }
}
