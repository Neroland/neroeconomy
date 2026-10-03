package za.co.neroland.neroeconomy.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The simulation's random source: the same four inputs give the same numbers, and nothing else does. */
class SimRandomTest {
    private static final byte[] SECRET = SimTesting.secret(1);
    private static final byte[] OTHER_SECRET = SimTesting.secret(2);

    @Test void theSameInputsGiveTheSameBytes() {
        byte[] first = SimRandom.bytes(SECRET, "exchange", 1_000L, "price:acme");
        byte[] second = SimRandom.bytes(SECRET, "exchange", 1_000L, "price:acme");
        assertArrayEquals(first, second);
        assertEquals(32, first.length);
        // And the same stream, value after value, however it is read.
        SimRandom a = SimRandom.of(SECRET, "exchange", 1_000L, "price:acme");
        SimRandom b = SimRandom.of(SECRET.clone(), "exchange", 1_000L, "price:acme");
        for (int index = 0; index < 200; index++) assertEquals(a.nextLong(), b.nextLong());
    }

    @Test void anyDifferentInputGivesDifferentBytes() {
        String base = hex(SimRandom.bytes(SECRET, "exchange", 1_000L, "price:acme"));
        Set<String> seen = new HashSet<>();
        seen.add(base);
        assertTrue(seen.add(hex(SimRandom.bytes(SECRET, "exchange", 1_001L, "price:acme"))));
        assertTrue(seen.add(hex(SimRandom.bytes(SECRET, "exchange", 1_000L, "price:bolt"))));
        assertTrue(seen.add(hex(SimRandom.bytes(SECRET, "houses", 1_000L, "price:acme"))));
        assertTrue(seen.add(hex(SimRandom.bytes(OTHER_SECRET, "exchange", 1_000L, "price:acme"))));
        // The two strings are not simply glued together: moving a character across is a different input.
        assertTrue(seen.add(hex(SimRandom.bytes(SECRET, "exchangep", 1_000L, "rice:acme"))));
        assertTrue(seen.add(hex(SimRandom.bytes(SECRET, "", 1_000L, "exchangeprice:acme"))));
        // Many steps in a row never repeat a block.
        for (long step = 2_000L; step < 2_500L; step++)
            assertTrue(seen.add(hex(SimRandom.bytes(SECRET, "exchange", step, "price:acme"))));
    }

    @Test void theBytesMatchAPlainHmacOfTheDocumentedMessage() throws Exception {
        // Pins the derivation: HMAC-SHA256(secret, len(handler) ‖ handler ‖ step ‖ len(label) ‖ label ‖ counter).
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET, "HmacSHA256"));
        java.nio.ByteBuffer message = java.nio.ByteBuffer.allocate(4 + 2 + 8 + 4 + 1 + 8);
        message.putInt(2).put("hx".getBytes(java.nio.charset.StandardCharsets.UTF_8)).putLong(77L)
                .putInt(1).put("l".getBytes(java.nio.charset.StandardCharsets.UTF_8)).putLong(0L);
        assertArrayEquals(mac.doFinal(message.array()), SimRandom.bytes(SECRET, "hx", 77L, "l"));
    }

    @Test void aStreamRunsPastOneBlockWithoutRepeating() {
        SimRandom stream = SimRandom.of(SECRET, "exchange", 5L, "long");
        byte[] first = new byte[32];
        byte[] second = new byte[32];
        stream.nextBytes(first);
        stream.nextBytes(second);
        assertFalse(Arrays.equals(first, second));
        // Reading it in one go gives the same bytes as reading it in two halves.
        byte[] whole = new byte[64];
        SimRandom.of(SECRET, "exchange", 5L, "long").nextBytes(whole);
        assertArrayEquals(first, Arrays.copyOfRange(whole, 0, 32));
        assertArrayEquals(second, Arrays.copyOfRange(whole, 32, 64));
    }

    @Test void boundedNumbersStayInRangeAndCoverIt() {
        SimRandom stream = SimRandom.of(SECRET, "test", 1L, "range");
        int[] hits = new int[7];
        for (int index = 0; index < 7_000; index++) {
            int value = stream.nextInt(7);
            assertTrue(value >= 0 && value < 7);
            hits[value]++;
        }
        // Roughly even: each of seven values about a thousand times.
        for (int count : hits) assertTrue(count > 800 && count < 1_200, Arrays.toString(hits));
        for (int index = 0; index < 1_000; index++) {
            long big = stream.nextLong(1_000_000_007L);
            assertTrue(big >= 0L && big < 1_000_000_007L);
            double unit = stream.nextDouble();
            assertTrue(unit >= 0.0D && unit < 1.0D);
            assertTrue(Double.isFinite(stream.nextGaussian()));
        }
        assertEquals(0, stream.nextInt(1));
        assertThrows(IllegalArgumentException.class, () -> stream.nextInt(0));
        assertThrows(IllegalArgumentException.class, () -> stream.nextLong(-1L));
    }

    @Test void aSourceNeedsASecretAndNames() {
        assertThrows(IllegalArgumentException.class, () -> SimRandom.of(null, "h", 1L, "l"));
        assertThrows(IllegalArgumentException.class, () -> SimRandom.of(new byte[0], "h", 1L, "l"));
        assertThrows(IllegalArgumentException.class, () -> SimRandom.of(SECRET, null, 1L, "l"));
        assertThrows(IllegalArgumentException.class, () -> SimRandom.of(SECRET, "h", 1L, null));
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
