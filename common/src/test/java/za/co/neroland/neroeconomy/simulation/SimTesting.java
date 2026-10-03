package za.co.neroland.neroeconomy.simulation;

import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * What a test outside this package needs from the simulation's package-private seams: a fixed
 * secret, so two runs can be compared, and a context to hand a {@link SimStep} under test.
 */
public final class SimTesting {
    private SimTesting() { }

    /** A secret that is the same in every run: 32 bytes counted up from {@code seed}. */
    public static byte[] secret(int seed) {
        byte[] secret = new byte[SimulationSection.SECRET_BYTES];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) (seed + index * 7);
        return secret;
    }

    /** Gives the store's simulation section a known secret instead of a random one. */
    public static void fixSecret(EconomyStore store, byte[] secret) {
        store.simulation().secretForTest(secret);
    }

    /** The secret the section holds (made on first use, as in the game). */
    public static byte[] secretOf(SimulationSection section) { return section.secret(); }

    /** A context for running one handler directly: {@code handler.run(context, step)}. */
    public static SimContext context(EconomyStore store, String handler, long step, long stepMillis, long now,
            boolean autonomous) {
        return new SimContext(store, null, store.simulation().secret(), handler, step, stepMillis, now, autonomous);
    }
}
