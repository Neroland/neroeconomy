package za.co.neroland.neroeconomy.simulation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The simulation's only source of randomness. A stream is derived from exactly four things — the
 * world's secret, the name of the handler asking, the step index and a label the handler chooses —
 * by HMAC-SHA256, so:
 *
 * <ul>
 *   <li>the same four inputs always give the same numbers, which makes a step a pure function of
 *       (state, step index, secret) and lets it be replayed and unit tested;</li>
 *   <li>different steps, handlers or labels give unrelated numbers;</li>
 *   <li>nobody without the secret can predict the next number from the ones already seen, which is
 *       what keeps the exchange from leaking tomorrow's price.</li>
 * </ul>
 *
 * <p>A stream is a counter-mode generator: block {@code n} is the HMAC of the four inputs and
 * {@code n}. It is not thread-safe; derive one per use.
 */
public final class SimRandom {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int BLOCK = 32;

    private final Mac mac;
    private final byte[] prefix;
    private final byte[] block = new byte[BLOCK];
    private long counter;
    private int used = BLOCK;

    private SimRandom(byte[] secret, String handler, long step, String label) {
        if (secret == null || secret.length == 0 || handler == null || label == null)
            throw new IllegalArgumentException("random source");
        try {
            mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", impossible);
        }
        byte[] handlerBytes = handler.getBytes(StandardCharsets.UTF_8);
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        // Each string is written with its length, so ("ab", "c") and ("a", "bc") never collide.
        prefix = ByteBuffer.allocate(Integer.BYTES + handlerBytes.length + Long.BYTES + Integer.BYTES
                + labelBytes.length).putInt(handlerBytes.length).put(handlerBytes).putLong(step)
                .putInt(labelBytes.length).put(labelBytes).array();
    }

    /** The stream for one (secret, handler, step, label). */
    public static SimRandom of(byte[] secret, String handler, long step, String label) {
        return new SimRandom(secret, handler, step, label);
    }

    /** The first 32 bytes of the stream for one (secret, handler, step, label). */
    public static byte[] bytes(byte[] secret, String handler, long step, String label) {
        byte[] out = new byte[BLOCK];
        of(secret, handler, step, label).nextBytes(out);
        return out;
    }

    public void nextBytes(byte[] out) {
        for (int index = 0; index < out.length; index++) out[index] = nextByte();
    }

    public long nextLong() {
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) value = value << 8 | nextByte() & 0xFFL;
        return value;
    }

    public int nextInt() { return (int) (nextLong() >>> 32); }

    /** Uniform in {@code [0, bound)}, without the bias a plain remainder would have. */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound");
        return (int) nextLong(bound);
    }

    /** Uniform in {@code [0, bound)}, without the bias a plain remainder would have. */
    public long nextLong(long bound) {
        if (bound <= 0L) throw new IllegalArgumentException("bound");
        long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound + 1L) % bound;
        while (true) {
            long value = nextLong() & Long.MAX_VALUE;
            if (value <= limit) return value % bound;
        }
    }

    /** Uniform in {@code [0, 1)}. */
    public double nextDouble() { return (nextLong() >>> 11) * 0x1.0p-53; }

    public boolean nextBoolean() { return (nextByte() & 1) != 0; }

    /** Normally distributed with mean 0 and standard deviation 1 (Box–Muller). */
    public double nextGaussian() {
        double u = 1.0D - nextDouble();
        double v = nextDouble();
        return Math.sqrt(-2.0D * Math.log(u)) * Math.cos(2.0D * Math.PI * v);
    }

    private byte nextByte() {
        if (used == BLOCK) {
            mac.update(prefix);
            mac.update(ByteBuffer.allocate(Long.BYTES).putLong(counter++).array());
            byte[] next = mac.doFinal();
            System.arraycopy(next, 0, block, 0, BLOCK);
            used = 0;
        }
        return block[used++];
    }
}
