package za.co.neroland.neroeconomy.exchange;

/**
 * How an exchange number is written out: a price in hundredths of a credit as {@code 123.45}, a
 * change in hundredths of a percent as {@code +1.50%}. Whole-number arithmetic only, so what a
 * player reads is exactly what is stored.
 */
public final class PriceText {
    private PriceText() { }

    /** {@code 12345} → {@code "123.45"}. */
    public static String credits(long cents) {
        long magnitude = Math.abs(cents);
        long fraction = magnitude % PriceModel.CENTS_PER_CREDIT;
        return (cents < 0L ? "-" : "") + magnitude / PriceModel.CENTS_PER_CREDIT + '.' + (fraction < 10L ? "0" : "")
                + fraction;
    }

    /** {@code 150} → {@code "+1.50%"}, {@code -25} → {@code "-0.25%"}, {@code 0} → {@code "0.00%"}. */
    public static String percent(int basisPoints) {
        long magnitude = Math.abs((long) basisPoints);
        long fraction = magnitude % 100L;
        return (basisPoints > 0 ? "+" : basisPoints < 0 ? "-" : "") + magnitude / 100L + '.'
                + (fraction < 10L ? "0" : "") + fraction + '%';
    }
}
