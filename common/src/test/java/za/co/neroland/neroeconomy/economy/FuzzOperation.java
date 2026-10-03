package za.co.neroland.neroeconomy.economy;

/**
 * One kind of operation the operation fuzzer can pick. To put a new part of the economy under the
 * fuzzer — the order book, offers, auto-trade rules, the exchange — write a class that implements
 * this and add it to {@link OperationFuzzer#OPERATIONS}; nothing else has to change.
 *
 * <p>An operation does one thing to the {@link FuzzWorld}: it calls the real section it exercises
 * and updates the world's models to say what must now be true (the supply, who is owed what from
 * escrow, where the goods are). The fuzzer checks every invariant against those models after every
 * single operation, whether it succeeded or was refused.
 *
 * <p>Use only {@code world.random} for choices and {@code world.wall} for time, so a run is
 * repeatable from its seed. Keep any state the operation needs between calls in the store or in
 * the world's models: the store is replaced by a reloaded copy at a crash point.
 */
interface FuzzOperation {
    /** A short name, for failure messages and the coverage count. */
    String name();

    /** How often this is picked relative to the others; an ordinary operation is 40. */
    default int weight() { return 40; }

    /**
     * Does one operation.
     *
     * @return a number that says what happened (an amount, a count, 1 for done and 0 for refused);
     *         it is folded into the run's digest, and anything but 0 counts as "did something"
     */
    long run(FuzzWorld world);
}
