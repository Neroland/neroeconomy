package za.co.neroland.neroeconomy.economy;

import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Ledgers in the states the tests start from. The config is at its defaults in a unit test. */
final class Ledgers {
    /** The default {@code reserveInitial}. */
    static final long RESERVE = 250_000L;
    /** The default {@code startingBalance}. */
    static final long STARTING = 100L;

    private Ledgers() { }

    /** A new world's ledger: nothing in it but a freshly filled Reserve. */
    static EconomySavedData seeded() {
        EconomySavedData data = new EconomySavedData();
        data.seedReserve(RESERVE);
        return data;
    }

    /**
     * Opens each account, which pays its starting balance out of the Reserve, and marks it vested so
     * the grant can be spent with other players. Opening is a write of nothing: it moves no credits
     * other than the grant.
     */
    static void open(EconomySavedData data, UUID... players) {
        for (UUID player : players) {
            data.withdraw(player, NeroEconomyCommon.CREDITS, 0L);
            data.markVested(player);
        }
    }

    /** Opens each account and leaves its starting balance locked, as a new player's is. */
    static void openLocked(EconomySavedData data, UUID... players) {
        for (UUID player : players) data.withdraw(player, NeroEconomyCommon.CREDITS, 0L);
    }
}
